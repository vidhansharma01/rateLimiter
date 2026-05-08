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
 * Unit tests for {@link TokenBucketAlgorithm}.
 * Redis interactions are mocked; the algorithm logic is exercised independently.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenBucketAlgorithmTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private TokenBucketAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        algorithm = new TokenBucketAlgorithm(redisTemplate);
    }

    @Test
    @DisplayName("Allow request when bucket has tokens")
    void allowsWhenTokensAvailable() {
        // Script returns: [allowed=1, tokens_remaining=4, ms_until_token=1000]
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(String.class),
                any(String.class), any(String.class), any(String.class)))
                .thenReturn(List.of(1L, 4L, 1000L));

        RateLimitResult result = algorithm.checkAndIncrement(
                "ratelimit:user:U1:/api/search", 10, 60, 10, 1.0);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getRemaining()).isEqualTo(4L);
        assertThat(result.getLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("Deny request when bucket is empty")
    void deniesWhenBucketEmpty() {
        // Script returns: [allowed=0, tokens_remaining=0, ms_until_token=5000]
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(String.class),
                any(String.class), any(String.class), any(String.class)))
                .thenReturn(List.of(0L, 0L, 5000L));

        RateLimitResult result = algorithm.checkAndIncrement(
                "ratelimit:user:U1:/api/search", 10, 60, 10, 1.0);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemaining()).isEqualTo(0L);
        assertThat(result.getRetryAfterSec()).isGreaterThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("Fail open when Redis returns null")
    void failsOpenOnRedisNullResponse() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(String.class),
                any(String.class), any(String.class), any(String.class)))
                .thenReturn(null);

        RateLimitResult result = algorithm.checkAndIncrement(
                "ratelimit:user:U1:/api/search", 10, 60, 10, 1.0);

        // Fail open: request is allowed even though Redis returned null
        assertThat(result.isAllowed()).isTrue();
    }
}
