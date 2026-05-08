package com.rateLimiter.token.middleware;

import com.rateLimiter.token.config.ClientTier;
import com.rateLimiter.token.core.RateLimitResult;
import com.rateLimiter.token.core.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Servlet Filter that enforces rate limits on every incoming HTTP request.
 *
 * <p><b>Placement (HLD §14):</b> Acts as API Gateway middleware — a single enforcement
 * point for external traffic. Can be extracted to a sidecar proxy for inter-service limits.
 *
 * <p><b>Response headers set (HLD §9):</b>
 * <ul>
 *   <li>{@code X-RateLimit-Limit} — configured limit for the matched rule</li>
 *   <li>{@code X-RateLimit-Remaining} — requests remaining in the current window</li>
 *   <li>{@code X-RateLimit-Reset} — Unix timestamp when the window resets</li>
 *   <li>{@code Retry-After} — seconds to wait before retrying (on 429 only)</li>
 *   <li>{@code X-RateLimit-Violated-Rule} — identifier of the breached rule (on 429 only)</li>
 * </ul>
 *
 * <p>Actuator and health-check endpoints are excluded from rate limiting.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String HEADER_X_CLIENT_TIER   = "X-Client-Tier";
    private static final String HEADER_LIMIT            = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING        = "X-RateLimit-Remaining";
    private static final String HEADER_RESET            = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER      = "Retry-After";
    private static final String HEADER_VIOLATED_RULE    = "X-RateLimit-Violated-Rule";

    private final RateLimiterService rateLimiterService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String endpoint = request.getRequestURI();
        ClientTier tier = resolveTier(request);

        RateLimitResult result = rateLimiterService.evaluate(request, tier, endpoint);

        // Always set informational headers so clients can self-regulate
        setRateLimitHeaders(response, result);

        if (!result.isAllowed()) {
            log.info("Request DENIED [429] endpoint={} tier={} rule={}",
                    endpoint, tier, result.getViolatedRuleDescription());
            sendTooManyRequestsResponse(response, result);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Skip rate limiting for infrastructure endpoints.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/health");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the client tier from the request header.
     * In production this should be validated from an API key lookup or JWT claim.
     * Defaults to FREE tier for unauthenticated/unknown clients.
     */
    private ClientTier resolveTier(HttpServletRequest request) {
        String tierHeader = request.getHeader(HEADER_X_CLIENT_TIER);
        if (tierHeader == null) return ClientTier.FREE;
        try {
            return ClientTier.valueOf(tierHeader.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown client tier header '{}'; defaulting to FREE", tierHeader);
            return ClientTier.FREE;
        }
    }

    private void setRateLimitHeaders(HttpServletResponse response, RateLimitResult result) {
        if (result.getLimit() >= 0) {
            response.setHeader(HEADER_LIMIT,     String.valueOf(result.getLimit()));
            response.setHeader(HEADER_REMAINING, String.valueOf(result.getRemaining()));
            response.setHeader(HEADER_RESET,     String.valueOf(result.getResetAtEpochSec()));
        }
    }

    private void sendTooManyRequestsResponse(HttpServletResponse response,
                                             RateLimitResult result) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.getRetryAfterSec()));

        if (result.getViolatedRuleDescription() != null) {
            response.setHeader(HEADER_VIOLATED_RULE, result.getViolatedRuleDescription());
        }

        response.setContentType("application/json");
        response.getWriter().write(buildErrorBody(result));
    }

    private String buildErrorBody(RateLimitResult result) {
        return """
                {
                  "status": 429,
                  "error": "Too Many Requests",
                  "message": "Rate limit exceeded. Please retry after %d seconds.",
                  "retryAfter": %d,
                  "violatedRule": "%s"
                }
                """.formatted(
                result.getRetryAfterSec(),
                result.getRetryAfterSec(),
                result.getViolatedRuleDescription() != null ? result.getViolatedRuleDescription() : ""
        );
    }
}
