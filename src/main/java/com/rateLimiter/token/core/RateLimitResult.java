package com.rateLimiter.token.core;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Immutable result of a rate limit check.
 *
 * <p>Carries everything the middleware layer needs to:
 * <ul>
 *   <li>Allow or reject the request</li>
 *   <li>Populate standard rate limit response headers (HLD §9)</li>
 *   <li>Set the {@code Retry-After} and {@code X-RateLimit-Violated-Rule} headers on 429</li>
 * </ul>
 */
@Getter
@Builder
public class RateLimitResult {

    /** Whether the request is allowed to proceed. */
    private final boolean allowed;

    /**
     * {@code X-RateLimit-Limit} — the limit enforced by the most restrictive rule.
     */
    private final int limit;

    /**
     * {@code X-RateLimit-Remaining} — requests remaining in the current window.
     * Will be 0 when {@code allowed == false}.
     */
    private final long remaining;

    /**
     * {@code X-RateLimit-Reset} — Unix timestamp (seconds) when the window resets.
     */
    private final long resetAtEpochSec;

    /**
     * {@code Retry-After} — seconds the client should wait before retrying.
     * Includes jitter to prevent thundering herd (HLD §16).
     * Only meaningful when {@code allowed == false}.
     */
    private final long retryAfterSec;

    /**
     * {@code X-RateLimit-Violated-Rule} — human-readable identifier of the
     * rule that triggered the 429. Useful for debugging.
     * Only set when {@code allowed == false}.
     */
    private final String violatedRuleDescription;

    /**
     * Flag indicating this request was rate-limited in shadow mode (observe only).
     * When true, the request is still allowed but a {@code shadow_429} metric is emitted.
     */
    private final boolean shadowLimited;

    /** Convenience factory: allowed result with standard headers. */
    public static RateLimitResult allow(int limit, long remaining, long resetAtEpochSec) {
        return RateLimitResult.builder()
                .allowed(true)
                .limit(limit)
                .remaining(remaining)
                .resetAtEpochSec(resetAtEpochSec)
                .retryAfterSec(0)
                .build();
    }

    /** Convenience factory: denied result (429). */
    public static RateLimitResult deny(int limit, long resetAtEpochSec, long retryAfterSec,
                                       String violatedRule) {
        return RateLimitResult.builder()
                .allowed(false)
                .limit(limit)
                .remaining(0)
                .resetAtEpochSec(resetAtEpochSec)
                .retryAfterSec(retryAfterSec)
                .violatedRuleDescription(violatedRule)
                .build();
    }

    /** Convenience factory: fail-open result (Redis unavailable). */
    public static RateLimitResult failOpen() {
        long now = System.currentTimeMillis() / 1000;
        return RateLimitResult.builder()
                .allowed(true)
                .limit(-1)
                .remaining(-1)
                .resetAtEpochSec(now)
                .build();
    }
}
