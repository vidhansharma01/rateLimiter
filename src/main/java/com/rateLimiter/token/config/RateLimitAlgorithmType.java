package com.rateLimiter.token.config;

/**
 * Supported rate limiting algorithms.
 * Stored in the rule config table so each rule can independently choose its algorithm.
 */
public enum RateLimitAlgorithmType {
    TOKEN_BUCKET,
    SLIDING_WINDOW_COUNTER,
    SLIDING_WINDOW_LOG,
    FIXED_WINDOW
}
