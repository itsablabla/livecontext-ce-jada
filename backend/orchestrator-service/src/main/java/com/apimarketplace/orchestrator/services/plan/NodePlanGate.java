package com.apimarketplace.orchestrator.services.plan;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.TriggerNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-execution gate for node types an admin has put behind a paid plan.
 *
 * <p>Applied exactly where {@code NodeCreditGate} is applied, and for the same
 * reason: a refusal has to be VISIBLE at the node that caused it. The node is
 * persisted FAILED carrying the plan that would unlock it, and the engine's
 * ordinary failure cascade marks everything downstream SKIPPED - so the run view
 * shows where it stopped and what to do, instead of a workflow that silently
 * does less than it says.
 *
 * <p><b>This gate covers node TYPES only</b> ({@code node:media},
 * {@code node:browser_agent}, {@code node:webhook}, ...). Per-integration gates
 * ({@code api:youtube-data-api}, {@code tool:...}) are enforced in catalog-service:
 * a step's tool reference has three shapes in the wild (UUID,
 * {@code apiSlug/toolSlug}, bare slug) and only the catalog can resolve all three
 * to the row the gate is keyed by. Splitting it this way also covers agent and
 * chat tool calls, which never reach this engine.
 */
@Service
public class NodePlanGate {

    private static final Logger logger = LoggerFactory.getLogger(NodePlanGate.class);

    /** Machine token on the node output, mirroring the catalog's own refusal. */
    public static final String ERROR_CODE = "PLAN_UPGRADE_REQUIRED";

    /**
     * Optional: absent in unit-test slices and in any assembly without auth-client.
     * A missing gate gates nothing, which is what every other failure mode here does.
     */
    private PlanFeatureGate planFeatureGate;

    @Autowired(required = false)
    public void setPlanFeatureGate(PlanFeatureGate planFeatureGate) {
        this.planFeatureGate = planFeatureGate;
    }

    /**
     * @param tenantId the run owner; blank means an internal or system execution,
     *                 which is never gated
     * @return a FAILED result to persist instead of running {@code node}, or
     *         {@code null} when it may run
     */
    public NodeExecutionResult denyOrNull(ExecutionNode node, String tenantId) {
        if (planFeatureGate == null || !planFeatureGate.isEnabled()) {
            return null;
        }
        if (node == null || tenantId == null || tenantId.isBlank()) {
            return null;
        }
        String featureKey = featureKeyFor(node);
        if (featureKey == null) {
            return null;
        }
        String required;
        try {
            required = planFeatureGate.upgradeRequiredFor(tenantId, List.of(featureKey));
        } catch (Exception e) {
            // Fail OPEN: a gate that cannot read its own configuration must not be
            // the reason a paid customer's workflow stops.
            logger.warn("[PlanGate] Lookup failed for {} - allowing: {}", featureKey, e.getMessage());
            return null;
        }
        if (required == null) {
            return null;
        }
        logger.info("[PlanGate] Node {} ({}) requires plan {} - failing node for tenant {}",
                node.getNodeId(), featureKey, required, tenantId);
        return NodeExecutionResult.failureWithOutput(
                node.getNodeId(),
                message(required),
                Map.of("error_code", ERROR_CODE, "required_plan", required),
                0);
    }

    /**
     * Runtime type -> the type {@code node_type_documentation} uses for it.
     *
     * <p>One entry, and it is not cosmetic: a tables trigger EXECUTES as
     * {@code datasource} ({@code Trigger.type}'s default) and is DOCUMENTED as
     * {@code table}. The admin screen offers what is documented, so without this
     * the one thing an admin could select would never be the thing this gate
     * looks up - a control that silently does nothing.
     */
    private static final Map<String, String> DOCUMENTED_TYPE_ALIASES = Map.of(
            "datasource", "table");

    /**
     * The gate key for a node, or {@code null} when its type cannot be named.
     *
     * <p>Package-visible so a test can pin the mapping: it has to agree, string for
     * string, with {@code node_type_documentation.type}, which is what the admin
     * screen lists. A key that never matches is a control that silently does
     * nothing.
     */
    static String featureKeyFor(ExecutionNode node) {
        String type = null;
        if (node instanceof TriggerNode triggerNode) {
            // A trigger's schemaNodeType is the constant "TRIGGER"; the documented
            // type is the trigger's own kind (webhook, schedule, form, ...).
            type = triggerNode.getTriggerType();
        }
        if (type == null || type.isBlank()) {
            type = node.schemaNodeType();
        }
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return "node:" + DOCUMENTED_TYPE_ALIASES.getOrDefault(normalized, normalized);
    }

    /** One sentence: what is missing, and the only thing that changes it. */
    private static String message(String requiredPlan) {
        return "This node is available from the " + requiredPlan
                + " plan. Upgrade your plan to run it, or remove this node from the workflow.";
    }
}
