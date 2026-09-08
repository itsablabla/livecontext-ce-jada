package com.apimarketplace.orchestrator.execution.v2.adhoc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to run, once, standalone.
 *
 * @param nodeType        canonical core type (alias already resolved, prefix already stripped)
 * @param configKey       nested key the engine reads this type's config from, or null when the
 *                        type stores its config at the core's top level
 * @param config          the node configuration, exactly as {@code add_node} would store it
 * @param runInput        upstream data made visible to templates; may be empty, never null
 * @param tenantId        owner of the credentials the node will resolve
 * @param organizationId  active workspace, null when unknown (NOT "personal")
 * @param organizationRole caller's role in that workspace
 * @param label           display label, also the node's key
 */
public record AdHocNodeRequest(
        String nodeType,
        String configKey,
        Map<String, Object> config,
        Map<String, Object> runInput,
        String tenantId,
        String organizationId,
        String organizationRole,
        String label
) {

    /**
     * Keys that identify the node rather than configure it. A config carrying one of these
     * would rewrite what the node IS after every gate has already been asked about the declared
     * type, so they are refused at the surface and stripped here as defence in depth.
     */
    public static final java.util.Set<String> RESERVED_CORE_KEYS = java.util.Set.of("type", "id", "label");

    public AdHocNodeRequest {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (config != null) {
            config.forEach((k, v) -> { if (!RESERVED_CORE_KEYS.contains(k)) sanitized.put(k, v); });
        }
        config = sanitized;
        runInput = runInput != null ? new LinkedHashMap<>(runInput) : new LinkedHashMap<>();
        label = (label == null || label.isBlank()) ? defaultLabel(nodeType) : label;
    }

    private static String defaultLabel(String nodeType) {
        return nodeType == null ? "node" : nodeType;
    }

    /**
     * The node's id inside the synthetic plan.
     *
     * <p>Built with {@code LabelNormalizer} rather than by hand, because that is the same
     * normalisation the plan parser and every {@code {{core:...}}} template use: a key
     * spelled differently here would build a node no template could address.
     *
     * <p>The prefix follows the list the plan files the node under. {@code generate} is an AI
     * node keyed {@code agent:<label>}, and the builder derives that key from the label
     * itself - so a {@code core:} id here would leave the plan entry and the built node
     * disagreeing about what the node is called.
     */
    public String nodeKey() {
        return AdHocNodeTypeResolver.AGENT_TYPES.contains(nodeType())
                ? com.apimarketplace.orchestrator.utils.LabelNormalizer.agentKey(label())
                : com.apimarketplace.orchestrator.utils.LabelNormalizer.coreKey(label());
    }
}
