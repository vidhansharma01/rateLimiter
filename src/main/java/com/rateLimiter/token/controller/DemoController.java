package com.rateLimiter.token.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo controller to exercise rate limit enforcement.
 *
 * <p>All endpoints are rate-limited by the {@link com.rateLimiter.token.middleware.RateLimitFilter}.
 * Use the {@code X-Client-Tier} header to simulate different tiers.
 * Use {@code X-Api-Key} or {@code X-User-Id} headers to simulate different clients.
 *
 * <p>Example curl:
 * <pre>
 *   curl -H "X-Client-Tier: FREE" -H "X-User-Id: user123" http://localhost:8080/api/search
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/search")
    public ResponseEntity<Map<String, String>> search() {
        return ResponseEntity.ok(Map.of(
                "endpoint", "/api/search",
                "result", "Search results returned successfully"
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login() {
        return ResponseEntity.ok(Map.of(
                "endpoint", "/api/login",
                "result", "Login successful"
        ));
    }

    @PostMapping("/payments")
    public ResponseEntity<Map<String, String>> payments() {
        return ResponseEntity.ok(Map.of(
                "endpoint", "/api/payments",
                "result", "Payment processed"
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status() {
        return ResponseEntity.ok(Map.of(
                "service", "rate-limiter",
                "status", "UP"
        ));
    }
}
