package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.license.EnterpriseLicenseResourceLimit;
import com.apimarketplace.auth.service.license.EnterpriseLicenseService;
import com.apimarketplace.common.plan.CloudPlanAccess;
import com.apimarketplace.common.web.AppEditionProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a user's per-plan resource creation limit.
 * Self-Hosted Enterprise uses the signed local license before subscription lookup.
 *
 * <p>Lookup chain: providerId (Keycloak sub) → User row → active Subscription
 * → Plan → {@code Plan.getResourceLimit(resourceType)}.
 *
 * <p>Results are cached in-process for 60 seconds (Caffeine). The cache is
 * acceptable to be slightly stale after a plan upgrade - the user's next API
 * call within the next minute may still see the old limit, then it refreshes.
 *
 * <p>{@code null} from {@link #getLimit(String, String)} means <em>unlimited</em>
 * (consistent with the existing {@code Plan.included_*} pattern). Callers must
 * treat null as "no limit".
 */
@Service
public class PlanLimitService {

    private static final Logger log = LoggerFactory.getLogger(PlanLimitService.class);

    /** All-digits = the gateway's internal user id. Keycloak subs are UUIDs. */
    private static final java.util.regex.Pattern NUMERIC_ID = java.util.regex.Pattern.compile("[0-9]{1,19}");

    /** Sentinel: user has no active subscription. Treated as FREE-equivalent (default block). */
    public static final String NO_SUBSCRIPTION = "__NONE__";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AppEditionProvider editionProvider;
    private final EnterpriseLicenseService enterpriseLicenseService;

    /**
     * CE↔Cloud pricing delegation: when present (CE / {@code marketplace.mode=remote}),
     * a CLOUD-sourced install's plan is governed by the bound cloud account. Optional -
     * {@code null} in the cloud deployment, where the local plan is authoritative.
     */
    private CloudPlanAccess cloudPlanAccess;

    @Autowired(required = false)
    public void setCloudPlanAccess(CloudPlanAccess cloudPlanAccess) {
        this.cloudPlanAccess = cloudPlanAccess;
        if (cloudPlanAccess != null) {
            log.info("CloudPlanAccess wired - CE plan resolution delegates to the bound cloud account");
        }
    }

    /** Cache key: providerId + ":" + resourceType. Value: limit (-1 = unlimited sentinel). */
    private final Cache<String, Integer> limitCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    /** Cache key: providerId. Value: planCode (or NO_SUBSCRIPTION). */
    private final Cache<String, String> planCodeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public PlanLimitService(UserRepository userRepository,
                             SubscriptionRepository subscriptionRepository,
                             AppEditionProvider editionProvider,
                             EnterpriseLicenseService enterpriseLicenseService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.editionProvider = editionProvider;
        this.enterpriseLicenseService = enterpriseLicenseService;
    }

    /**
     * Returns the resource limit for the given user and resource type.
     *
     * @return the limit, or {@code null} if unlimited.
     */
    public Integer getLimit(String providerId, String resourceType) {
        if (providerId == null || providerId.isBlank() || resourceType == null) {
            return null;
        }
        if (editionProvider.isSelfHostedEnterprise()) {
            EnterpriseLicenseResourceLimit licenseLimit = enterpriseLicenseService.resolveResourceLimit(resourceType);
            if (!licenseLimit.licensed()) {
                return 0;
            }
            return licenseLimit.limit();
        }
        String key = providerId + ":" + resourceType.toUpperCase();
        Integer cached = limitCache.getIfPresent(key);
        if (cached != null) {
            return cached == Integer.MIN_VALUE ? null : cached;
        }
        Integer fresh = loadLimit(providerId, resourceType);
        // Use MIN_VALUE as in-cache representation for null (unlimited),
        // because Caffeine cannot store null values.
        limitCache.put(key, fresh == null ? Integer.MIN_VALUE : fresh);
        return fresh;
    }

    /**
     * Returns the plan code currently active for the user, or {@link #NO_SUBSCRIPTION}.
     */
    public String getPlanCode(String providerId) {
        if (editionProvider.isSelfHostedEnterprise()) {
            var status = enterpriseLicenseService.currentStatus();
            return status.active() ? status.planCode() : NO_SUBSCRIPTION;
        }
        if (providerId == null || providerId.isBlank()) {
            return NO_SUBSCRIPTION;
        }
        return planCodeCache.get(providerId, this::loadPlanCode);
    }

    /**
     * Invalidate cached entries for a user. Call after subscription changes
     * (upgrade, downgrade, cancel) so the next read sees the new plan.
     */
    public void invalidate(String providerId) {
        if (providerId == null) return;
        planCodeCache.invalidate(providerId);
        // Limit cache keys are prefixed with providerId
        limitCache.asMap().keySet().removeIf(k -> k.startsWith(providerId + ":"));
    }

    // ===== private helpers =====

    private Integer loadLimit(String providerId, String resourceType) {
        Optional<User> userOpt = userRepository.findByProviderId(providerId);
        if (userOpt.isEmpty()) {
            log.debug("No user found for providerId={}, treating as no-limit", providerId);
            return null;
        }
        Optional<Subscription> subOpt = subscriptionRepository.findActiveByUserId(userOpt.get().getId());
        if (subOpt.isEmpty()) {
            log.debug("No active subscription for user={}, treating as no-limit", providerId);
            return null;
        }
        Plan plan = subOpt.get().getPlan();
        if (plan == null) {
            return null;
        }
        return plan.getResourceLimit(resourceType);
    }

    /**
     * The caller's user row, whichever id shape they were addressed by.
     *
     * <p><b>Two shapes reach auth-service and they are not interchangeable.</b> The
     * gateway sets {@code X-User-ID} to the INTERNAL numeric id and puts the Keycloak
     * sub in {@code X-Provider-Id}; a service calling auth-service internally forwards
     * whatever it holds, which is that same numeric id. Resolving by provider id alone
     * therefore found NOBODY for any request that came through the gateway, and
     * answered {@link #NO_SUBSCRIPTION} for a paying customer - which reads as "free"
     * to every caller and refused a TEAM account the endpoints it had paid for.
     *
     * <p>An all-digits value is an internal id: Keycloak subs are UUIDs, so the two
     * shapes cannot collide.
     *
     * <p><b>Deliberately used by the plan-code path only.</b> {@link #loadLimit} has
     * the same mismatch and is left as it is on purpose: it fails OPEN (unknown user
     * -> null -> unlimited), so repairing it here would silently switch per-plan
     * resource quotas ON for every cloud account, including accounts already over a
     * limit. That is a product decision, not a side effect of a bug fix.
     */
    private Optional<User> resolveUserForPlanCode(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        if (NUMERIC_ID.matcher(trimmed).matches()) {
            try {
                Optional<User> byId = userRepository.findById(Long.parseLong(trimmed));
                if (byId.isPresent()) {
                    return byId;
                }
            } catch (NumberFormatException ignored) {
                // Longer than a long: fall through and try it as a provider id.
            }
        }
        return userRepository.findByProviderId(trimmed);
    }

    /**
     * The plan code AND whether the account behind {@code id} was found at all.
     *
     * <p><b>Why this exists.</b> {@link #getPlanCode(String)} answers
     * {@link #NO_SUBSCRIPTION} for two situations that are not the same thing:
     * "this account exists and is on no paid plan" and "no account matched this
     * id". Every entitlement caller may safely conflate them, because both mean
     * "grant nothing extra" and the cost of being wrong is a refused feature.
     *
     * <p>A caller that DELETES cannot conflate them: a job that applies the
     * shortest window to a FREE account would erase a paying customer's data on
     * the strength of a failed lookup - the 2026-08 id-form incident, destructive
     * instead of merely blocking. {@code accountResolved} is what lets such a
     * caller retain instead. (Execution-log retention was the first such caller;
     * it now resolves per WORKSPACE through {@code PlanResolutionService} and no
     * longer uses this method, but the distinction stays for the next one.)
     *
     * @param id either id shape, exactly as {@link #getPlanCode(String)} accepts
     */
    public PlanCodeResolution resolvePlanCode(String id) {
        if (editionProvider.isSelfHostedEnterprise()) {
            var status = enterpriseLicenseService.currentStatus();
            if (!status.active()) {
                // NOT resolved. This branch answers for ANY id, including one no
                // account matches, so reporting it as resolved would let a
                // destructive caller read the lapsed licence as "free tier" and
                // apply the shortest window to the whole install. A licence lapse
                // must degrade features, never shorten retention.
                return new PlanCodeResolution(false, NO_SUBSCRIPTION, null);
            }
            return new PlanCodeResolution(true, status.planCode(), null);
        }
        Optional<User> userOpt = resolveUserForPlanCode(id);
        if (userOpt.isEmpty()) {
            return new PlanCodeResolution(false, NO_SUBSCRIPTION, null);
        }
        Long userId = userOpt.get().getId();
        return new PlanCodeResolution(true, planCodeForUser(userId), userId);
    }

    /**
     * Outcome of a plan lookup.
     *
     * @param accountResolved whether an account actually matched the id; when
     *                        {@code false}, {@code planCode} carries no
     *                        information about the customer and a destructive
     *                        caller must not act on it
     * @param planCode        the effective plan code, or {@link #NO_SUBSCRIPTION}
     * @param userId          the internal id of the account that matched, so a
     *                        caller that needs to go on and read something else
     *                        about that account does not have to redo the
     *                        two-id-shape resolution and risk doing it
     *                        differently; {@code null} when nothing matched, and
     *                        also on the self-hosted-enterprise branch, which
     *                        answers from a licence rather than from a user row
     */
    public record PlanCodeResolution(boolean accountResolved, String planCode, Long userId) {
    }

    private String loadPlanCode(String providerId) {
        Optional<User> userOpt = resolveUserForPlanCode(providerId);
        if (userOpt.isEmpty()) {
            return NO_SUBSCRIPTION;
        }
        return planCodeForUser(userOpt.get().getId());
    }

    private String planCodeForUser(Long userId) {
        String localPlan = subscriptionRepository.findActiveByUserId(userId)
                .map(s -> s.getPlan() != null ? s.getPlan().getCode() : NO_SUBSCRIPTION)
                .orElse(NO_SUBSCRIPTION);

        // CE↔Cloud delegation: a CLOUD-sourced linked install is governed by the bound
        // cloud account's plan, so paying on the cloud unlocks the plan's features here.
        // cloudPlanAccess is null in the cloud deployment (no-op). governingPlanCode only
        // returns a value when the install is CLOUD-sourced; EffectivePlanResolver fails
        // safe to the local plan when it is absent/unknown, so a transient cloud outage
        // never strips entitlements.
        if (cloudPlanAccess != null) {
            String cloudPlan = cloudPlanAccess.governingPlanCode(userId).orElse(null);
            return EffectivePlanResolver.resolve(localPlan, cloudPlan != null, true, cloudPlan);
        }
        return localPlan;
    }
}
