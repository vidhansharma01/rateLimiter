package com.rateLimiter.token.core;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds the Redis key that identifies a client in the rate limiter.
 *
 * <p><b>Priority order (HLD §5):</b>
 * <ol>
 *   <li>API key — identifies a developer / business tier client</li>
 *   <li>Authenticated user ID — for logged-in users</li>
 *   <li>IP address — fallback for unauthenticated / public traffic</li>
 * </ol>
 *
 * <p>Key format: {@code ratelimit:{dimension}:{id}:{endpoint}}
 * <br>Example: {@code ratelimit:user:U123:/api/search}
 */
public final class ClientKeyBuilder {

    // Header names — follow common API gateway conventions
    private static final String HEADER_API_KEY   = "X-Api-Key";
    private static final String HEADER_USER_ID   = "X-User-Id";
    private static final String HEADER_FORWARDED = "X-Forwarded-For";

    /** Redis key prefix to namespace all rate limit counters. */
    public static final String KEY_PREFIX = "ratelimit";

    private ClientKeyBuilder() {}

    /**
     * Builds a client-identifying Redis key for the given request and endpoint.
     *
     * @param request  the incoming HTTP request
     * @param endpoint the normalized request path (e.g., {@code /api/search})
     * @return Redis key string (e.g., {@code ratelimit:api:abc123:/api/search})
     */
    public static String buildKey(HttpServletRequest request, String endpoint) {
        String dimension = resolveDimension(request);
        // Sanitize endpoint: strip trailing slashes, lowercase
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        return String.format("%s:%s:%s", KEY_PREFIX, dimension, normalizedEndpoint);
    }

    /**
     * Builds a key for a specific dimension string (pre-resolved).
     * Used when dimension is already known (e.g., global tier rules).
     */
    public static String buildKey(String dimensionKey, String endpoint) {
        return String.format("%s:%s:%s", KEY_PREFIX, dimensionKey, normalizeEndpoint(endpoint));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String resolveDimension(HttpServletRequest request) {
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return "api:" + apiKey;
        }

        String userId = request.getHeader(HEADER_USER_ID);
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }

        return "ip:" + extractClientIp(request);
    }

    /**
     * Extracts the real client IP, respecting X-Forwarded-For for reverse proxy deployments.
     * Takes the first (original client) IP from the forwarded chain.
     */
    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_FORWARDED);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "global";
        return endpoint.toLowerCase().replaceAll("/+$", "");
    }
}
