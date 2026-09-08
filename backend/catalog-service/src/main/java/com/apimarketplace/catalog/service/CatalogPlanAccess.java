package com.apimarketplace.catalog.service;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.catalog.service.exception.PlanUpgradeRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * "Does this account's plan include this integration?" - asked once, immediately
 * before a catalog tool runs.
 *
 * <p><b>Why here and not in the workflow engine.</b> A step's tool reference has
 * three shapes in the wild ({@code uuid}, {@code apiSlug/toolSlug}, bare
 * {@code toolSlug}), and only two of them contain the API slug the gate is keyed
 * by. This is the one place that has already resolved the tool to its row, so it
 * is the only place that can answer for all three - and it covers the agent and
 * chat callers too, which never go through the engine at all.
 *
 * <p>Precedence is {@code tool:} then {@code api:}: a single endpoint can be
 * held back further than the integration it belongs to, but never the reverse.
 */
@Service
public class CatalogPlanAccess {

    private static final Logger log = LoggerFactory.getLogger(CatalogPlanAccess.class);

    /**
     * Optional: absent in test slices and in any deployment assembled without
     * auth-client. A missing gate means nothing is gated, which is the same
     * answer every other failure mode in this path gives.
     */
    private PlanFeatureGate planFeatureGate;

    @Autowired(required = false)
    public void setPlanFeatureGate(PlanFeatureGate planFeatureGate) {
        this.planFeatureGate = planFeatureGate;
    }

    /**
     * @throws PlanUpgradeRequiredException when the user's plan is below the
     *         requirement stored for this tool or its API
     */
    public void assertAllowed(String userId, String apiSlug, String toolSlug, String featureLabel) {
        String required = upgradeRequiredFor(userId, apiSlug, toolSlug);
        if (required != null) {
            log.info("[PlanGate] Refusing tool '{}' (api '{}') for user {} - requires {}",
                    toolSlug, apiSlug, userId, required);
            throw new PlanUpgradeRequiredException(required, featureLabel);
        }
    }

    /**
     * @return the plan the user would need, or {@code null} when they may run it
     *         (including every failure mode: no gate bean, no user, no plan).
     */
    public String upgradeRequiredFor(String userId, String apiSlug, String toolSlug) {
        if (planFeatureGate == null || !planFeatureGate.isEnabled()) {
            return null;
        }
        List<String> keys = candidateKeys(apiSlug, toolSlug);
        if (keys.isEmpty()) {
            return null;
        }
        return planFeatureGate.upgradeRequiredFor(userId, keys);
    }

    /** Package-visible for the unit test that pins the precedence order. */
    static List<String> candidateKeys(String apiSlug, String toolSlug) {
        List<String> keys = new ArrayList<>(2);
        if (toolSlug != null && !toolSlug.isBlank()) {
            keys.add("tool:" + toolSlug.trim().toLowerCase());
        }
        if (apiSlug != null && !apiSlug.isBlank()) {
            keys.add("api:" + apiSlug.trim().toLowerCase());
        }
        return keys;
    }
}
