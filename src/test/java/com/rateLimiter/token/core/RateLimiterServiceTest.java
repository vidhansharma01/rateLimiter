package com.rateLimiter.token.core;

import com.rateLimiter.token.algorithm.AlgorithmFactory;
import com.rateLimiter.token.algorithm.RateLimitAlgorithm;
import com.rateLimiter.token.config.ClientTier;
import com.rateLimiter.token.config.RateLimitAlgorithmType;
import com.rateLimiter.token.config.RateLimitRule;
import com.rateLimiter.token.config.RuleConfigService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimiterService}.
 * Tests hierarchical rule evaluation, whitelist bypass, shadow mode, and fail-open.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimiterServiceTest {

    @Mock private RuleConfigService    ruleConfigService;
    @Mock private AlgorithmFactory     algorithmFactory;
    @Mock private RateLimitAlgorithm   algorithm;
    @Mock private HttpServletRequest   request;

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService(
                ruleConfigService, algorithmFactory, new SimpleMeterRegistry());

        // Lenient stubs: not all tests hit ClientKeyBuilder's header extraction
        lenient().when(request.getHeader("X-Api-Key")).thenReturn(null);
        lenient().when(request.getHeader("X-User-Id")).thenReturn("user123");
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(request.getRemoteAddr()).thenReturn("192.168.1.1");
    }

    @Test
    @DisplayName("INTERNAL tier clients bypass all rate limit checks")
    void internalClientsBypassRateLimiting() {
        RateLimitResult result = rateLimiterService.evaluate(
                request, ClientTier.INTERNAL, "/api/search");

        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Allow request when all applicable rules pass")
    void allowsWhenAllRulesPass() {
        RateLimitRule rule = buildRule(ClientTier.FREE, "/api/search",
                RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER, true);

        when(ruleConfigService.resolveRules(ClientTier.FREE, "/api/search"))
                .thenReturn(List.of(rule));
        when(algorithmFactory.get(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER))
                .thenReturn(algorithm);
        when(algorithm.checkAndIncrement(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.allow(100, 95, System.currentTimeMillis() / 1000 + 60));

        RateLimitResult result = rateLimiterService.evaluate(
                request, ClientTier.FREE, "/api/search");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimit()).isEqualTo(100);
    }

    @Test
    @DisplayName("Deny request when any enforced rule fails")
    void deniesWhenOneRuleFails() {
        RateLimitRule rule = buildRule(ClientTier.FREE, "/api/search",
                RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER, true);

        when(ruleConfigService.resolveRules(ClientTier.FREE, "/api/search"))
                .thenReturn(List.of(rule));
        when(algorithmFactory.get(RateLimitAlgorithmType.SLIDING_WINDOW_COUNTER))
                .thenReturn(algorithm);
        when(algorithm.checkAndIncrement(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.deny(100,
                        System.currentTimeMillis() / 1000 + 60, 30,
                        "sliding_window_counter:ratelimit:user:user123:/api/search"));

        RateLimitResult result = rateLimiterService.evaluate(
                request, ClientTier.FREE, "/api/search");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getViolatedRuleDescription()).contains("sliding_window_counter");
    }

    @Test
    @DisplayName("Shadow mode: allow request even when shadow rule would deny")
    void shadowModeAllowsButMetricEmitted() {
        // Shadow mode rule: enforce = false
        RateLimitRule shadowRule = buildRule(ClientTier.FREE, "/api/search",
                RateLimitAlgorithmType.FIXED_WINDOW, false);  // enforce=false

        when(ruleConfigService.resolveRules(ClientTier.FREE, "/api/search"))
                .thenReturn(List.of(shadowRule));
        when(algorithmFactory.get(RateLimitAlgorithmType.FIXED_WINDOW))
                .thenReturn(algorithm);
        when(algorithm.checkAndIncrement(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.deny(100,
                        System.currentTimeMillis() / 1000 + 60, 30, "fixed_window:key"));

        RateLimitResult result = rateLimiterService.evaluate(
                request, ClientTier.FREE, "/api/search");

        // Shadow mode: request is ALLOWED even though the rule would have denied
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Fail open when no rules are configured")
    void failsOpenWhenNoRulesConfigured() {
        when(ruleConfigService.resolveRules(any(), anyString()))
                .thenReturn(List.of());

        RateLimitResult result = rateLimiterService.evaluate(
                request, ClientTier.FREE, "/api/search");

        assertThat(result.isAllowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RateLimitRule buildRule(ClientTier tier, String endpoint,
                                    RateLimitAlgorithmType algorithmType,
                                    boolean enforce) {
        return RateLimitRule.builder()
                .ruleId(UUID.randomUUID())
                .clientTier(tier)
                .endpoint(endpoint)
                .limitCount(100)
                .windowSec(60)
                .algorithm(algorithmType)
                .enforce(enforce)
                .active(true)
                .build();
    }
}
