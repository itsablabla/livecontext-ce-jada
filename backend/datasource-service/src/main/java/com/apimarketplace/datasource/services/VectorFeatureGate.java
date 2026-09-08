package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan gate for vector (embedding) columns and similarity search.
 *
 * <p><b>What changed on 2026-09-03, and why the answer is now per user.</b> Vectors used to be a
 * self-hosted-only feature: this class computed one boolean at construction from the deployment's
 * edition and refused everything on managed cloud. The reason was resource contention, an
 * unbounded RAG corpus on the shared Postgres competing with every other schema, including the
 * execution engine's hot path. That reason has not gone away; what changed is that it is now
 * priced instead of forbidden. Vectors are available on managed cloud from a paid plan upward,
 * and the bar is set by an admin in {@code auth.plan_feature_requirement} under the key
 * {@link #FEATURE_KEY}, not by this code, so it can move without a deploy.
 *
 * <p><b>Whose plan decides: the table's OWNER, never the caller.</b> Every decision point here is
 * handed the tenant that owns the datasource, because a table's capabilities belong to the
 * workspace that owns it. A workspace on a plan that includes vectors keeps working for every
 * member and every workflow run, whatever the individual reading the row is paying. That also
 * matches how a run is gated elsewhere: the orchestrator's node gate asks about the run owner.
 *
 * <p><b>Two editions are never priced, and for the same reason: they own their database.</b>
 * Self-hosted, obviously. And DEDICATED cloud, which is a single-tenant instance: the contention
 * this gate exists to price does not exist there, and those customers are on an enterprise
 * contract already. Both short-circuit below, before any plan lookup.
 *
 * <p>Do not simplify that to "PlanFeatureGate is disabled off managed cloud" - it is not.
 * {@code AuthClientConfig} enables it on {@code isCloud()}, which is the shared CLOUD edition
 * ONLY, not {@code isManagedCloud()}. Relying on the bean's own flag would have made dedicated
 * cloud free by accident rather than by decision, which is how it briefly was.
 *
 * <p><b>Fail-open, like every other plan gate in the product.</b> An unreadable requirement map,
 * an unknown plan code, a blank tenant: all resolve to allowed. Refusing a paying customer's
 * similarity search because auth-service blinked would fail a run for a reason its owner cannot
 * act on. The one exception is a deployment where the plan gate is not wired at all on cloud
 * ({@code planFeatureGate == null}), which is refused: that is not a lookup failure, it is an
 * assembly with no way to ask the question, and it must not silently open the shared database.
 */
@Component
public class VectorFeatureGate {

    private static final Logger log = LoggerFactory.getLogger(VectorFeatureGate.class);

    /**
     * The admin-configurable requirement key. A missing row means "everyone", so the seed
     * migration is what actually puts vectors behind a plan; this constant only names the switch.
     */
    public static final String FEATURE_KEY = "feature:vector_search";

    private static final List<String> FEATURE_KEYS = List.of(FEATURE_KEY);

    /**
     * Rejection text when we cannot name a plan: the gate is unwired, or the deployment refuses
     * vectors for a reason the caller cannot price their way out of. Written for the chat agent,
     * so it states the constraint and the one move that is available, and nothing the agent
     * cannot act on.
     */
    public static final String DISABLED_MESSAGE =
            "Vector (embedding) columns are not available on this deployment. Use text columns "
                    + "with keyword filters instead of similarity search.";

    /** Editions that own their own database, and are therefore never priced for using it. */
    private final boolean ownsItsDatabase;
    private final PlanFeatureGate planFeatureGate;

    public VectorFeatureGate(AppEditionProvider editionProvider,
                             @Nullable PlanFeatureGate planFeatureGate) {
        this.ownsItsDatabase = editionProvider.isSelfHosted() || editionProvider.isDedicatedCloud();
        this.planFeatureGate = planFeatureGate;
        log.info("[VectorFeatureGate] vector columns {}",
                ownsItsDatabase ? "ENABLED (single-tenant database, never priced)"
                        : planFeatureGate == null ? "DISABLED (shared cloud, no plan gate wired)"
                        : "PLAN-GATED (shared cloud, key " + FEATURE_KEY + ")");
    }

    /**
     * Whether the workspace that owns the data may use vector columns and similarity search.
     *
     * @param ownerTenantId the tenant that OWNS the datasource, not the caller. Blank is treated
     *                      as an internal or system execution and is never gated, matching
     *                      {@code PlanFeatureGate}'s own contract.
     */
    public boolean isVectorAllowed(String ownerTenantId) {
        if (ownsItsDatabase) {
            return true;
        }
        if (planFeatureGate == null) {
            return false;
        }
        try {
            return planFeatureGate.allows(ownerTenantId, FEATURE_KEYS);
        } catch (Exception e) {
            // Fail open: a gate that cannot read its own configuration must not be the reason a
            // paid workspace's RAG workflow stops.
            log.warn("[VectorFeatureGate] plan lookup failed for tenant {} - allowing: {}",
                    ownerTenantId, e.getMessage());
            return true;
        }
    }

    /**
     * The plan the owning workspace would have to be on, or {@code null} when it may already use
     * vectors. Exists so a refusal can NAME the plan instead of describing a wall.
     */
    public String upgradeRequiredFor(String ownerTenantId) {
        if (ownsItsDatabase || planFeatureGate == null) {
            return null;
        }
        try {
            return planFeatureGate.upgradeRequiredFor(ownerTenantId, FEATURE_KEYS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The rejection to hand back, for a caller that has already found the gate closed. Names the
     * plan when one is known, because "upgrade to PRO" is actionable and "not available" is not.
     */
    public String deniedMessage(String ownerTenantId) {
        String required = upgradeRequiredFor(ownerTenantId);
        if (required == null) {
            return DISABLED_MESSAGE;
        }
        return "Vector (embedding) columns and similarity search are available from the "
                + required + " plan. Upgrade the workspace to use them, or use text columns with "
                + "keyword filters instead of similarity search.";
    }

    /**
     * Snapshot-clone sanitizer: returns the mappingSpec with VECTOR columns removed when the
     * owning workspace may not use them (identity otherwise, including null). Used by the
     * publication acquire/clone path: rejecting the whole clone because the published table
     * carries an embedding column would break the acquisition of an otherwise-valid marketplace
     * workflow, so the non-vector columns survive and the workflow's similarity steps fail at run
     * time with {@link #deniedMessage}.
     */
    public Map<String, ColumnMappingSpec> stripDisallowedVectorColumns(
            String ownerTenantId, Map<String, ColumnMappingSpec> mappingSpec) {
        List<String> vectorColumns = disallowedVectorColumns(ownerTenantId, mappingSpec);
        if (vectorColumns.isEmpty()) {
            return mappingSpec;
        }
        Map<String, ColumnMappingSpec> filtered = new LinkedHashMap<>(mappingSpec);
        vectorColumns.forEach(filtered::remove);
        log.warn("[VectorFeatureGate] stripped {} vector column(s) {} from cloned snapshot "
                        + "(tenant {} may not use vectors)",
                vectorColumns.size(), vectorColumns, ownerTenantId);
        return filtered;
    }

    /**
     * Vector column names the owning workspace may not keep (empty when allowed or none present).
     * Companion of {@link #stripDisallowedVectorColumns} so callers can also purge the same names
     * from {@code columnOrder}: a stripped column left in the order list renders as a ghost column
     * in the table UI.
     */
    public List<String> disallowedVectorColumns(
            String ownerTenantId, Map<String, ColumnMappingSpec> mappingSpec) {
        if (mappingSpec == null || mappingSpec.isEmpty() || isVectorAllowed(ownerTenantId)) {
            return List.of();
        }
        return mappingSpec.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().type() == ColumnType.VECTOR)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * First VECTOR column name in the supplied mappingSpec, or null. Pure schema inspection with
     * no plan in it: it drives HNSW index create/drop events, which must follow the data whether
     * or not the workspace may still query it.
     */
    public static String findVectorColumn(Map<String, ColumnMappingSpec> mappingSpec) {
        if (mappingSpec == null) {
            return null;
        }
        return mappingSpec.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().type() == ColumnType.VECTOR)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
