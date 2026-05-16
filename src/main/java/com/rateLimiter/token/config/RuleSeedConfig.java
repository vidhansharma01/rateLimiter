package com.rateLimiter.token.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Seeds the H2 rule config store with representative rules on startup.
 *
 * <p>These rules model the examples discussed in the HLD:
 * <ul>
 *   <li>/login endpoint — strict limit (5/min FREE), token bucket</li>
 *   <li>/api/search endpoint — generous limits per tier, sliding window counter</li>
 *   <li>/api/payments — tight fixed window, all tiers</li>
 *   <li>Tier-level global rules — baseline for all endpoints</li>
 *   <li>Global catch-all — system-wide cap (50K req/min)</li>
 * </ul>
 *
 * <p>In production these rules would be loaded from MySQL and managed via
 * an admin API or config management tool.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RuleSeedConfig {

    @Bean
    CommandLineRunner seedRules(RateLimitRuleRepository repo,
                                RuleConfigService ruleConfigService) {
        return args -> {
            List<RateLimitRule> rules = List.of(

                // ── /login endpoint — Token Bucket, strict, per tier ────────────
                RateLimitRule.builder()
                        .clientTier(ClientTier.FREE)
                        .endpoint("/api/login")
                        .limitCount(5)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.TOKEN_BUCKET)
                        .burstSize(5)
                        .refillRate(1.0 / 12.0)  // 1 token every 12 sec = 5/min sustained
                        .enforce(true)
                        .build(),

                RateLimitRule.builder()
                        .clientTier(ClientTier.PRO)
                        .endpoint("/api/login")
                        .limitCount(20)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.TOKEN_BUCKET)
                        .burstSize(20)
                        .refillRate(20.0 / 60.0)
                        .enforce(true)
                        .build(),

                // ── /api/search — Sliding Window Counter, per tier ──────────────
                RateLimitRule.builder()
                        .clientTier(ClientTier.FREE)
                        .endpoint("/api/search")
                        .limitCount(100)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER)
                        .enforce(true)
                        .build(),

                RateLimitRule.builder()
                        .clientTier(ClientTier.PRO)
                        .endpoint("/api/search")
                        .limitCount(500)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER)
                        .enforce(true)
                        .build(),

                RateLimitRule.builder()
                        .clientTier(ClientTier.MAX)
                        .endpoint("/api/search")
                        .limitCount(2000)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER)
                        .enforce(true)
                        .build(),

                // ── /api/payments — Sliding Window Log (strict accuracy) ─────────
                RateLimitRule.builder()
                        .clientTier(ClientTier.FREE)
                        .endpoint("/api/payments")
                        .limitCount(3)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_LOG)
                        .enforce(true)
                        .build(),

                RateLimitRule.builder()
                        .clientTier(ClientTier.PRO)
                        .endpoint("/api/payments")
                        .limitCount(30)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_LOG)
                        .enforce(true)
                        .build(),

                // ── Tier-level global rules (no endpoint) ───────────────────────
                RateLimitRule.builder()
                        .clientTier(ClientTier.FREE)
                        .endpoint(null)          // applies to all endpoints
                        .limitCount(200)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER)
                        .enforce(true)
                        .build(),

                RateLimitRule.builder()
                        .clientTier(ClientTier.PRO)
                        .endpoint(null)
                        .limitCount(1000)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER)
                        .enforce(true)
                        .build(),

                // ── Global system-wide cap (no tier, no endpoint) ─────────────
                RateLimitRule.builder()
                        .clientTier(null)        // applies to ALL tiers
                        .endpoint(null)          // applies to ALL endpoints
                        .limitCount(50_000)
                        .windowSec(60)
                        .algorithm(RateLimitAlgorithmType.FIXED_WINDOW)
                        .enforce(true)
                        .build()
            );

            repo.saveAll(rules);
            // Trigger immediate cache load (instead of waiting for scheduled refresh)
            ruleConfigService.refreshRuleCache();

            log.info("Seeded {} rate limit rules into the rule config store.", rules.size());
        };
    }
}
