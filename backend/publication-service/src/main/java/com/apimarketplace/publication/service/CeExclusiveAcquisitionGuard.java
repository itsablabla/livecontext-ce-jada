package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.utils.CeExclusiveFeatureDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Install gate for a publication that carries a feature managed cloud cannot simply run.
 *
 * <p><b>There are two such features and, since 2026-09-03, only one of them is a wall.</b>
 * A local-CLI agent has no host on managed cloud at any price, so an app that uses one is refused
 * outright and the refusal is terminal. Vector search used to be in the same bucket and no longer
 * is: it runs on cloud from the plan an admin set on {@code feature:vector_search}, so an app that
 * uses embeddings is now refused only for a workspace whose plan does not include them, and that
 * refusal names the plan that lifts it. Collapsing the two was what made a table with an embedding
 * column un-installable on cloud at any price.
 *
 * <p><b>Why the vector case must be refused at all, rather than let through.</b> The clone path
 * quietly strips vector columns a workspace may not keep, so letting a plan-less install proceed
 * would hand back an app missing the one thing it is built around, with nothing said. Refusing and
 * naming the plan is the honest outcome; the strip stays as the last line of defence for snapshots
 * that reach the clone by another route.
 *
 * <p><b>Scope - which paths this must and must not cover.</b> Every path that installs INTO a
 * workspace runs through here (workflow, agent, and standalone resource acquisition). The CE
 * download path ({@code /api/ce-marketplace/**}) deliberately does NOT: that endpoint runs on
 * cloud but SERVES self-hosted installs, and it is what makes these publications "CE exclusive"
 * rather than simply unavailable.
 *
 * <p>On a self-hosted deployment {@link AppEditionProvider#isManagedCloud()} is false, so this
 * guard is a no-op and a self-hosted user installs everything, including from their own local
 * marketplace.
 */
@Component
public class CeExclusiveAcquisitionGuard {

    private static final Logger logger = LoggerFactory.getLogger(CeExclusiveAcquisitionGuard.class);

    /**
     * Single user-facing message for the terminal refusal. States the constraint and the only move
     * that resolves it; it carries no REST paths or internals because it surfaces verbatim in the
     * marketplace UI and in agent tool results.
     */
    public static final String BLOCKED_MESSAGE =
            "This app is Community Edition exclusive: it uses a local CLI agent, which only runs on "
                    + "a self-hosted install. Install it on a self-hosted deployment to use it.";

    private final boolean managedCloud;
    /**
     * Dedicated cloud is single-tenant, so it is not priced for using its own database. Mirrors
     * {@code VectorFeatureGate}, which short-circuits on the same pair of editions; the two must
     * agree or an app installs here and then cannot be used.
     */
    private final boolean vectorsArePriced;
    private final PlanFeatureGate planFeatureGate;

    public CeExclusiveAcquisitionGuard(AppEditionProvider editionProvider,
                                       @Nullable PlanFeatureGate planFeatureGate) {
        this.managedCloud = editionProvider.isManagedCloud();
        this.vectorsArePriced = editionProvider.isCloud();
        this.planFeatureGate = planFeatureGate;
        logger.info("[CeExclusiveAcquisitionGuard] CE-exclusive publications {}",
                managedCloud ? "BLOCKED (managed cloud)" : "installable (self-hosted)");
    }

    /**
     * Refuse the install when this deployment, or this workspace's plan, cannot run the
     * publication.
     *
     * @param acquiringTenantId the workspace installing the app; blank is an internal or system
     *                          install and is never plan-gated
     * @throws CeExclusivePublicationException on managed cloud for an app that needs a local CLI
     *                                         agent (terminal)
     * @throws PublicationPlanUpgradeRequiredException on managed cloud for an app that needs
     *                                                 vector search when the workspace's plan does
     *                                                 not include it (lifted by upgrading)
     */
    public void check(WorkflowPublicationEntity publication, String acquiringTenantId) {
        if (publication == null || !managedCloud) {
            return;
        }
        List<String> features = publication.getCeExclusiveFeatures();
        // The feature list decides, not the boolean, because the boolean no longer distinguishes
        // "impossible here" from "not bought yet". The one exception is a row that claims to be
        // exclusive while naming nothing: that is drifted data, and the safe reading of it is the
        // old one. Refusing an install is recoverable; letting a CLI-agent app through is not.
        boolean blocked = CeExclusiveFeatureDetector.blocksInstall(features)
                || (publication.isCeExclusive() && (features == null || features.isEmpty()));
        if (blocked) {
            logger.info("[CeExclusiveAcquisitionGuard] refusing install of CE-exclusive publication {} (features={})",
                    publication.getId(), features);
            throw new CeExclusivePublicationException(BLOCKED_MESSAGE, features);
        }
        requirePlanForVectorSearch(publication, acquiringTenantId, features);
    }

    /**
     * The vector half: priced, not forbidden. Fails OPEN on a lookup failure like every other plan
     * gate in the product.
     *
     * <p><b>It does NOT fail open when the plan gate is absent</b>, and that asymmetry is
     * deliberate: {@code VectorFeatureGate} refuses on shared cloud with no gate wired, so letting
     * the install through here would deliver an app whose vector columns the clone then strips in
     * silence, which is the exact outcome this refusal exists to prevent. An assembly that cannot
     * ask the question must not hand out the capability either.
     */
    private void requirePlanForVectorSearch(WorkflowPublicationEntity publication,
                                            String acquiringTenantId,
                                            List<String> features) {
        if (!vectorsArePriced || features == null
                || !features.contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH)) {
            return;
        }
        if (planFeatureGate == null) {
            // No plan can be NAMED here: we have no way to read the requirement. Naming one anyway
            // would send the customer to buy a tier that may not be the one an admin configured.
            logger.info("[CeExclusiveAcquisitionGuard] refusing install of vector publication {}: "
                    + "no plan gate wired on shared cloud", publication.getId());
            throw new PublicationPlanUpgradeRequiredException(null, features);
        }
        String required;
        try {
            required = planFeatureGate.upgradeRequiredFor(
                    acquiringTenantId, List.of(VECTOR_SEARCH_FEATURE_KEY));
        } catch (Exception e) {
            logger.warn("[CeExclusiveAcquisitionGuard] plan lookup failed for tenant {} - allowing: {}",
                    acquiringTenantId, e.getMessage());
            return;
        }
        if (required == null) {
            return;
        }
        logger.info("[CeExclusiveAcquisitionGuard] refusing install of vector publication {} for tenant {}: needs {}",
                publication.getId(), acquiringTenantId, required);
        throw new PublicationPlanUpgradeRequiredException(required, features);
    }

    /**
     * Mirrors {@code VectorFeatureGate.FEATURE_KEY}. Duplicated rather than shared because the
     * gate lives in datasource-service and this service must not depend on it; the pair is pinned
     * by a test so a rename cannot silently un-gate one side.
     */
    public static final String VECTOR_SEARCH_FEATURE_KEY = "feature:vector_search";
}
