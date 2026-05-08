package com.rateLimiter.token.algorithm;

import com.rateLimiter.token.config.RateLimitAlgorithmType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Factory that maps a {@link RateLimitAlgorithmType} to its concrete strategy implementation.
 *
 * <p>Using a factory (rather than injecting all strategies everywhere) keeps the
 * {@link com.rateLimiter.token.core.RateLimiterService} decoupled from Spring bean wiring details.
 */
@Component
@RequiredArgsConstructor
public class AlgorithmFactory {

    private final TokenBucketAlgorithm          tokenBucket;
    private final SlidingWindowCounterAlgorithm slidingWindowCounter;
    private final SlidingWindowLogAlgorithm     slidingWindowLog;
    private final FixedWindowAlgorithm          fixedWindow;

    /**
     * Returns the algorithm strategy for the given type.
     *
     * @param type the algorithm type specified in the rate limit rule
     * @return the corresponding {@link RateLimitAlgorithm} implementation
     * @throws IllegalArgumentException if the type is not supported
     */
    public RateLimitAlgorithm get(RateLimitAlgorithmType type) {
        return switch (type) {
            case TOKEN_BUCKET              -> tokenBucket;
            case SLIDING_WINDOW_COUNTER    -> slidingWindowCounter;
            case SLIDING_WINDOW_LOG        -> slidingWindowLog;
            case FIXED_WINDOW              -> fixedWindow;
        };
    }
}
