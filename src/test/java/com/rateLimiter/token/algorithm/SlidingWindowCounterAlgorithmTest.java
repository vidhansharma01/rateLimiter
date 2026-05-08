package com.rateLimiter.token.algorithm;

import com.rateLimiter.token.core.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlidingWindowCounterAlgorithm}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlidingWindowCounterAlgorithmTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private SlidingWindowCounterAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        algorithm = new SlidingWindowCounterAlgorithm(redisTemplate);
    }

    @Test
    @DisplayName("Allow request within sliding window limit")
    void allowsWithinLimit() {
        // Lua script returns: [allowed=1, curr_count=30, prev_count=80, curr_window_start]
        long windowStart = (System.currentTimeMillis() / 60_000) * 60_000;
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), any(String.class), any(String.class)))
                .thenReturn(List.of(1L, 30L, 80L, windowStart));

        RateLimitResult result = algorithm.checkAndIncrement(
                "ratelimit:user:U1:/api/search", 100, 60);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimit()).isEqualTo(100);
    }

    @Test
    @DisplayName("Deny request when sliding window rate exceeds limit")
    void deniesWhenLimitExceeded() {
        long windowStart = (System.currentTimeMillis() / 60_000) * 60_000;
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), any(String.class), any(String.class)))
                .thenReturn(List.of(0L, 100L, 90L, windowStart));

        RateLimitResult result = algorithm.checkAndIncrement(
                "ratelimit:user:U1:/api/search", 100, 60);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemaining()).isEqualTo(0L);
        assertThat(result.getRetryAfterSec()).isGreaterThan(0);
        assertThat(result.getViolatedRuleDescription()).contains("sliding_window_counter");
    }

    @Test
    @DisplayName("Fail open when Redis returns null")
    void failsOpenOnNullResponse() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), any(String.class), any(String.class)))
                .thenReturn(null);

        RateLimitResult result = algorithm.checkAndIncrement(
                "ratelimit:user:U1:/api/search", 100, 60);

        assertThat(result.isAllowed()).isTrue();
    }
}
