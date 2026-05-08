package com.rateLimiter.token.config;

/**
 * Client tiers that map to different rate limit rules.
 * INTERNAL clients bypass rate limiting entirely (whitelist).
 */
public enum ClientTier {
    FREE,
    PRO,
    ENTERPRISE,
    INTERNAL   // whitelisted — rate limiting is skipped
}
