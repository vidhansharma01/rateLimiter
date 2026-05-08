package com.rateLimiter.token.core;

import com.rateLimiter.token.algorithm.AlgorithmFactory;
import com.rateLimiter.token.algorithm.RateLimitAlgorithm;
import com.rateLimiter.token.config.ClientTier;
import com.rateLimiter.token.config.RateLimitAlgorithmType;
import com.rateLimiter.token.config.RateLimitRule;
import com.rateLimiter.token.config.RuleConfigService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Core orchestrator for the distributed rate limiter (HLD §3, §6, §8).
 *
 * <p><b>Request processing flow (HLD §8):</b>
 * <ol>
 *   <li>Build client identity key from request headers.</li>
 *   <li>Resolve the ordered list of applicable rules (most specific first).</li>
 *   <li>Short-circuit for INTERNAL tier clients (whitelist).</li>
 *   <li>For each applicable rule, invoke the configured algorithm against Redis.</li>
 *   <li>If ANY rule denies the request → return 429 with the violated rule info.</li>
 *   <li>If the rule is in shadow mode ({@code enforce=false}) → allow but emit metric.</li>
 *   <li>If Redis is unavailable → fail open (allow all traffic).</li>
 * </ol>
 *
 * <p><b>Multi-rule hierarchical evaluation (HLD §6):</b>
 * All applicable rules are checked. The most restrictive one that denies wins.
 * Example: User U123 (Enterprise) hits POST /payments — all four rules
 * (user limit, tier limit, endpoint global cap, system-wide cap) are evaluated.
 *
 * <p><b>Metrics emitted:</b>
 * <ul>
 *   <li>{@code rate_limiter.allowed} — request was allowed</li>
 *   <li>{@code rate_limiter.denied} — request was denied (real 429)</li>
 *   <li>{@code rate_limiter.shadow_429} — shadow mode: would-have-been denied</li>
 *   <li>{@code rate_limiter.fail_open} — Redis was unavailable; request allowed</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RuleConfigService ruleConfigService;
    private final AlgorithmFactory  algorithmFactory;
    private final MeterRegistry     meterRegistry;

    /**
     * Evaluates all applicable rate limit rules for the incoming request.
     *
     * @param request  the incoming HTTP request (used to extract client identity)
     * @param tier     the client's tier (determines which rules apply)
     * @param endpoint the normalized request path (e.g., {@code /api/search})
     * @return a {@link RateLimitResult} with allow/deny decision and response headers
     */
    public RateLimitResult evaluate(HttpServletRequest request, ClientTier tier, String endpoint) {

        // Whitelisted INTERNAL clients bypass all rate limits
        if (tier == ClientTier.INTERNAL) {
            log.debug("INTERNAL client; bypassing rate limit check for endpoint={}", endpoint);
            return RateLimitResult.failOpen(); // reuse failOpen as "always allow, no limits"
        }

        String clientKey = ClientKeyBuilder.buildKey(request, endpoint);
        List<RateLimitRule> rules = ruleConfigService.resolveRules(tier, endpoint);

        if (rules.isEmpty()) {
            log.debug("No rate limit rules found for tier={}, endpoint={}; allowing", tier, endpoint);
            return RateLimitResult.failOpen();
        }

        RateLimitResult firstDeny = null;

        for (RateLimitRule rule : rules) {
            RateLimitResult result = evaluateSingleRule(clientKey, rule);

            if (!result.isAllowed()) {
                if (!rule.isEnforce()) {
                    // Shadow mode: log and metric but do NOT enforce the 429
                    log.info("SHADOW_429 rule={} client={} endpoint={}", rule.getRuleId(), clientKey, endpoint);
                    recordMetric("rate_limiter.shadow_429", rule, endpoint);
                    continue;
                }

                // Real deny: record which rule was breached and return immediately
                log.info("RATE_LIMITED rule={} client={} endpoint={}", rule.getRuleId(), clientKey, endpoint);
                recordMetric("rate_limiter.denied", rule, endpoint);
                return result; // first enforced denial wins
            }

            // Track the "tightest" allow result to surface correct remaining count
            if (firstDeny == null || result.getRemaining() < (firstDeny.isAllowed() ? result.getRemaining() : 0)) {
                firstDeny = result;
            }
        }

        // All rules passed — allowed
        recordMetric("rate_limiter.allowed", null, endpoint);

        // Return result from the most restrictive rule that still allowed
        return firstDeny != null ? firstDeny : RateLimitResult.allow(Integer.MAX_VALUE, Long.MAX_VALUE, 0);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Evaluates a single rule by delegating to the rule's configured algorithm.
     */
    private RateLimitResult evaluateSingleRule(String clientKey, RateLimitRule rule) {
        RateLimitAlgorithm algorithm = algorithmFactory.get(rule.getAlgorithm());

        try {
            Object[] extraArgs = buildAlgorithmArgs(rule);
            return algorithm.checkAndIncrement(clientKey, rule.getLimitCount(),
                    rule.getWindowSec(), extraArgs);
        } catch (Exception ex) {
            // Redis failure → fail open; HLD §10
            log.error("Redis error during rate limit check for key={}; failing open. Error: {}",
                    clientKey, ex.getMessage());
            recordMetric("rate_limiter.fail_open", rule, clientKey);
            return RateLimitResult.failOpen();
        }
    }

    /**
     * Extracts algorithm-specific extra arguments from the rule configuration.
     */
    private Object[] buildAlgorithmArgs(RateLimitRule rule) {
        if (rule.getAlgorithm() == RateLimitAlgorithmType.TOKEN_BUCKET) {
            int    burstSize  = rule.getBurstSize()  != null ? rule.getBurstSize()  : rule.getLimitCount();
            double refillRate = rule.getRefillRate() != null ? rule.getRefillRate() : 1.0;
            return new Object[]{ burstSize, refillRate };
        }
        // Other algorithms need no extra args
        return new Object[]{};
    }

    private void recordMetric(String metricName, RateLimitRule rule, String endpoint) {
        try {
            Counter.builder(metricName)
                    .tag("endpoint", endpoint != null ? endpoint : "unknown")
                    .tag("rule_id", rule != null ? rule.getRuleId().toString() : "none")
                    .register(meterRegistry)
                    .increment();
        } catch (Exception ex) {
            log.warn("Failed to record metric {}: {}", metricName, ex.getMessage());
        }
    }
}
