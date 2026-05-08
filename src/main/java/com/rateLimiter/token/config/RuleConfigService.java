package com.rateLimiter.token.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads rate limit rules from the database and caches them locally.
 *
 * <p><b>Design rationale (from HLD §6):</b>
 * Rules are cached in each Rate Limiter Service node with a 60-second TTL.
 * Rule changes propagate to all nodes within ~1 minute — acceptable for
 * operational rule updates. This eliminates per-request DB lookups.
 *
 * <p>Shadow-mode rollout (HLD §17): rules with {@code enforce=false} are
 * evaluated but never block requests; they emit {@code shadow_429} metrics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleConfigService {

    private final RateLimitRuleRepository ruleRepository;

    /**
     * Local in-memory cache of active rules.
     * CopyOnWriteArrayList gives safe reads from many threads without locking
     * while the refresh thread periodically replaces the entire list.
     */
    private volatile List<RateLimitRule> cachedRules = new CopyOnWriteArrayList<>();

    /**
     * Refresh the local rule cache every 60 seconds.
     * Scheduled after an initial delay so the first load happens at startup.
     */
    @Scheduled(fixedDelayString = "${rate-limiter.rule-cache-ttl-ms:60000}",
               initialDelayString = "${rate-limiter.rule-cache-initial-delay-ms:0}")
    public void refreshRuleCache() {
        try {
            List<RateLimitRule> freshRules = ruleRepository.findAllByActiveTrue();
            cachedRules = new CopyOnWriteArrayList<>(freshRules);
            log.debug("Rule cache refreshed: {} active rules loaded", freshRules.size());
        } catch (Exception ex) {
            // Stale cache is served on DB failure — HLD §10 failure handling.
            log.error("Failed to refresh rule cache; serving stale rules. Error: {}", ex.getMessage());
        }
    }

    /**
     * Resolve the ordered list of applicable rules for a given client context.
     *
     * <p>Priority (most specific wins, all matching rules are returned
     * for hierarchical multi-rule evaluation):
     * <ol>
     *   <li>Tier + Endpoint specific</li>
     *   <li>Tier-level (endpoint wildcard)</li>
     *   <li>Global default (tier + endpoint both null)</li>
     * </ol>
     *
     * @param tier     client's tier (FREE / PRO / ENTERPRISE)
     * @param endpoint request path (e.g., {@code /api/search})
     * @return ordered list of applicable rules (most specific first)
     */
    public List<RateLimitRule> resolveRules(ClientTier tier, String endpoint) {
        return cachedRules.stream()
                .filter(r -> matchesTier(r, tier) && matchesEndpoint(r, endpoint))
                .sorted(this::bySpecificity)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean matchesTier(RateLimitRule rule, ClientTier tier) {
        return rule.getClientTier() == null || rule.getClientTier() == tier;
    }

    private boolean matchesEndpoint(RateLimitRule rule, String endpoint) {
        return rule.getEndpoint() == null || rule.getEndpoint().equals(endpoint);
    }

    /**
     * Sort order: rules with both tier AND endpoint set are the most specific.
     * Rules with only tier OR endpoint are less specific.
     * Global rules (both null) are least specific.
     */
    private int bySpecificity(RateLimitRule a, RateLimitRule b) {
        return Integer.compare(specificity(b), specificity(a));
    }

    private int specificity(RateLimitRule rule) {
        int score = 0;
        if (rule.getClientTier() != null) score += 2;
        if (rule.getEndpoint() != null)   score += 1;
        return score;
    }
}
