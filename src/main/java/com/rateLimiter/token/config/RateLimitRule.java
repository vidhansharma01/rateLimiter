package com.rateLimiter.token.config;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Represents one rate limit rule stored in the database.
 *
 * <p>Schema mirrors the HLD's {@code rate_limit_rules} table:
 * <pre>
 *   CREATE TABLE rate_limit_rules (
 *       rule_id       UUID PRIMARY KEY,
 *       client_tier   ENUM('FREE','PRO','ENTERPRISE','INTERNAL'),
 *       endpoint      VARCHAR(200),   -- NULL = applies to ALL endpoints
 *       limit_count   INT,
 *       window_sec    INT,
 *       algorithm     ENUM(...),
 *       burst_size    INT,            -- token bucket burst ceiling
 *       refill_rate   DECIMAL,        -- tokens/sec (token bucket)
 *       is_active     BOOLEAN
 *   );
 * </pre>
 *
 * <p>Priority resolution (most specific wins):
 * <ol>
 *   <li>User + Endpoint specific rule</li>
 *   <li>API Key + Endpoint rule</li>
 *   <li>IP + Endpoint rule</li>
 *   <li>Tier-level rule (FREE / PRO / ENTERPRISE)</li>
 *   <li>Global default rule</li>
 * </ol>
 */
@Entity
@Table(name = "rate_limit_rules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "rule_id", updatable = false, nullable = false)
    private UUID ruleId;

    /** Which client tier this rule applies to. NULL means global (all tiers). */
    @Enumerated(EnumType.STRING)
    @Column(name = "client_tier")
    private ClientTier clientTier;

    /**
     * Endpoint path (e.g., {@code /api/search}). NULL means applies to all endpoints.
     * Supports exact match; wildcard matching can be added later.
     */
    @Column(name = "endpoint")
    private String endpoint;

    /** Maximum allowed requests within the time window. */
    @Column(name = "limit_count", nullable = false)
    private int limitCount;

    /** Size of the time window in seconds. */
    @Column(name = "window_sec", nullable = false)
    private int windowSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false)
    private RateLimitAlgorithmType algorithm;

    /**
     * Token bucket: max tokens the bucket can hold (burst ceiling).
     * Irrelevant for non-token-bucket algorithms.
     */
    @Column(name = "burst_size")
    private Integer burstSize;

    /**
     * Token bucket: tokens added per second (sustained throughput control).
     * Irrelevant for non-token-bucket algorithms.
     */
    @Column(name = "refill_rate")
    private Double refillRate;

    /**
     * Shadow mode: when false, the rule is evaluated but never enforces a 429.
     * Used for safe rule rollout — observe impact before enforcement.
     */
    @Column(name = "enforce", nullable = false)
    @Builder.Default
    private boolean enforce = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
