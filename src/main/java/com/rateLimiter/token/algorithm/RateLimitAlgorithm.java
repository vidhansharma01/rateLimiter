package com.rateLimiter.token.algorithm;

import com.rateLimiter.token.core.RateLimitResult;

/**
 * Strategy interface for rate limiting algorithms.
 *
 * <p>Each implementation encapsulates a specific algorithm's logic and
 * its Redis key structure. The {@link com.rateLimiter.token.core.RateLimiterService}
 * selects the correct strategy based on the rule's {@code algorithm} field.
 *
 * <p><b>Contract:</b> Implementations MUST be stateless — all state lives in Redis.
 * The service is horizontally scalable; no per-instance state is allowed.
 */
public interface RateLimitAlgorithm {

    /**
     * Checks whether the request identified by {@code redisKey} is within the rate limit,
     * and atomically increments the counter if allowed.
     *
     * @param redisKey    the Redis key uniquely identifying this client + endpoint + window
     * @param limitCount  maximum allowed requests in the window
     * @param windowSec   size of the time window in seconds
     * @param extraArgs   algorithm-specific parameters (e.g., burst size, refill rate)
     * @return a {@link RateLimitResult} with allow/deny decision and header values
     */
    RateLimitResult checkAndIncrement(String redisKey, int limitCount, int windowSec,
                                      Object... extraArgs);
}
