package com.rateLimiter.token.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * JPA repository for persisting and querying rate limit rules.
 */
@Repository
public interface RateLimitRuleRepository extends JpaRepository<RateLimitRule, UUID> {

    /**
     * Fetch all active rules applicable to the given tier and endpoint.
     * Returns rules where endpoint matches exactly OR endpoint is NULL (wildcard).
     */
    @Query("""
            SELECT r FROM RateLimitRule r
            WHERE r.active = true
              AND (r.clientTier = :tier OR r.clientTier IS NULL)
              AND (r.endpoint = :endpoint OR r.endpoint IS NULL)
            ORDER BY r.clientTier NULLS LAST, r.endpoint NULLS LAST
            """)
    List<RateLimitRule> findApplicableRules(
            @Param("tier") ClientTier tier,
            @Param("endpoint") String endpoint
    );

    /** Load all active rules — used for seeding the local in-memory cache. */
    List<RateLimitRule> findAllByActiveTrue();
}
