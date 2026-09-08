package com.apimarketplace.auth.client.entitlement;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.AuthClient.PlanFeatureResponse;
import com.apimarketplace.common.plan.PlanTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "May this user use this feature on their plan?" - the client half of the
 * plan gate, shared by every service that has to answer it.
 *
 * <p><b>Two caches, deliberately different.</b> The requirement map is global
 * and small, so it is cached once for 60 s and reused by every user. The plan
 * code is per user and cached for 30 s, matching {@code CreditConsumptionClient}
 * so a whole workflow epoch normally costs one auth-service round-trip. Both are
 * bounded by time only: an admin's change reaches a running service within a
 * minute, which is the point of editing the gate at runtime.
 *
 * <p><b>Everything fails OPEN.</b> A lookup failure, an unknown plan code, an
 * unknown required plan and a disabled gate all resolve to "allowed". Blocking a
 * node because auth-service blinked would fail a run for a reason its owner
 * cannot act on, and the gate protects revenue, not safety.
 *
 * <p><b>The gate is cloud-only.</b> {@link #setEnabled(boolean)} is how a
 * self-hosted deployment turns it off wholesale; {@link PlanTier} additionally
 * treats the {@code CE} plan code as unrestricted, so a CE install that somehow
 * left it on is still not gated.
 */
public class PlanFeatureGate {

    private static final Logger log = LoggerFactory.getLogger(PlanFeatureGate.class);

    private static final long REQUIREMENTS_TTL_MS = 60_000L;
    private static final long PLAN_TTL_MS = 30_000L;
    /**
     * Retry window while the map has NEVER loaded. Without it, an auth-service
     * outage would make every gated node re-attempt the fetch, turning one outage
     * into a request storm. Short, because until it succeeds nothing is gated.
     */
    private static final long NEVER_LOADED_RETRY_MS = 5_000L;

    private final AuthClient authClient;
    private volatile boolean enabled;

    private volatile Map<String, String> cachedRequirements = Map.of();
    private volatile long requirementsFetchedAt = 0L;
    private volatile boolean requirementsEverLoaded = false;

    private final ConcurrentHashMap<String, CachedPlan> planCache = new ConcurrentHashMap<>();

    private record CachedPlan(String planCode, long fetchedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - fetchedAt > PLAN_TTL_MS;
        }
    }

    public PlanFeatureGate(AuthClient authClient, boolean enabled) {
        this.authClient = authClient;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The plan required for the FIRST of {@code candidateKeys} that carries a
     * requirement, so callers express precedence by ordering their keys (a
     * {@code tool:} key before the {@code api:} key it belongs to).
     *
     * @return the required plan code, or {@code null} when nothing is required
     */
    public String requiredPlanFor(List<String> candidateKeys) {
        if (!enabled || candidateKeys == null || candidateKeys.isEmpty()) {
            return null;
        }
        Map<String, String> requirements = requirements();
        if (requirements.isEmpty()) {
            return null;
        }
        for (String key : candidateKeys) {
            if (key == null) continue;
            String required = PlanTier.normalizeRequirement(requirements.get(key));
            if (required != null) {
                return required;
            }
        }
        return null;
    }

    /**
     * The plan the user must be on before they can use any of
     * {@code candidateKeys}, or {@code null} when they may already use them.
     *
     * @param providerId the user's Keycloak sub; blank means an internal or
     *                   system execution, which is never gated
     */
    public String upgradeRequiredFor(String providerId, List<String> candidateKeys) {
        String required = requiredPlanFor(candidateKeys);
        if (required == null) {
            return null;
        }
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        String planCode = planCodeOf(providerId);
        if (planCode == null) {
            // Lookup failed. Fail open rather than block on a plan we could not read.
            return null;
        }
        return PlanTier.meets(planCode, required) ? null : required;
    }

    /** Convenience inverse of {@link #upgradeRequiredFor}. */
    public boolean allows(String providerId, List<String> candidateKeys) {
        return upgradeRequiredFor(providerId, candidateKeys) == null;
    }

    /** Drops both caches. For tests and for an admin-triggered refresh. */
    public void invalidate() {
        requirementsFetchedAt = 0L;
        requirementsEverLoaded = false;
        cachedRequirements = Map.of();
        planCache.clear();
    }

    // ===== internals =====

    private Map<String, String> requirements() {
        long age = System.currentTimeMillis() - requirementsFetchedAt;
        long ttl = requirementsEverLoaded ? REQUIREMENTS_TTL_MS : NEVER_LOADED_RETRY_MS;
        if (age < ttl) {
            return cachedRequirements;
        }
        PlanFeatureResponse response = authClient.getPlanFeatures(null);
        if (response == null) {
            // Keep serving the last good map rather than opening every gate on one
            // failed call; if we never had one, an empty map means nothing is gated.
            requirementsFetchedAt = System.currentTimeMillis();
            return cachedRequirements;
        }
        cachedRequirements = response.requirements() != null ? response.requirements() : Map.of();
        requirementsFetchedAt = System.currentTimeMillis();
        requirementsEverLoaded = true;
        return cachedRequirements;
    }

    private String planCodeOf(String providerId) {
        CachedPlan cached = planCache.get(providerId);
        if (cached != null && !cached.isExpired()) {
            return cached.planCode();
        }
        PlanFeatureResponse response = authClient.getPlanFeatures(providerId);
        if (response == null || response.planCode() == null) {
            log.debug("[PlanFeatureGate] No plan code for user={} - failing open", providerId);
            return null;
        }
        planCache.put(providerId, new CachedPlan(response.planCode(), System.currentTimeMillis()));
        return response.planCode();
    }
}
