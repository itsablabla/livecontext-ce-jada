package com.apimarketplace.publication.utils;

import com.apimarketplace.agent.domain.BridgeProviders;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a publication can only run on a self-hosted install, purely
 * from its frozen snapshot.
 *
 * <p>Two platform features exist ONLY on self-hosted deployments, and a
 * publication that uses either is useless on managed cloud:
 * <ul>
 *   <li>{@code CLI_AGENT} - an agent bound to a local-CLI bridge provider
 *       ({@link BridgeProviders}). The CLI runs on the operator's own bridge
 *       host; managed cloud has no such binary to route to.</li>
 *   <li>{@code VECTOR_SEARCH} - a table carrying a {@code VECTOR} (embedding)
 *       column, or a CRUD node doing similarity search over one. Blocked on
 *       managed cloud by the datasource-service edition gate, so the cloud
 *       clone would silently lose the column the app is built around.</li>
 * </ul>
 *
 * <p><b>Detection is the label.</b> There is no publisher-set flag: the result
 * is recomputed from the snapshot on every publish AND every update, so the
 * badge can never drift from what the publication actually contains.
 *
 * <p>Pure function of the snapshot - no live lookups, no beans - so it produces
 * the same answer at publish time, at acquire time, and in a test.
 */
public final class CeExclusiveFeatureDetector {

    private CeExclusiveFeatureDetector() {}

    /** An agent in the snapshot runs on a local CLI bridge provider. */
    public static final String FEATURE_CLI_AGENT = "CLI_AGENT";

    /** The snapshot carries a vector/embedding column or a similarity search. */
    public static final String FEATURE_VECTOR_SEARCH = "VECTOR_SEARCH";

    /** Every feature this detector can report - the early-exit bound for the recursion. */
    public static final Set<String> ALL_FEATURES = Set.of(FEATURE_CLI_AGENT, FEATURE_VECTOR_SEARCH);

    /**
     * The subset that makes a publication genuinely UN-INSTALLABLE on managed cloud, as opposed to
     * merely priced.
     *
     * <p>Until 2026-09-03 both codes blocked, because both named something managed cloud could not
     * run at all. Vector search stopped being one of those: it runs on cloud from the plan an admin
     * set on {@code feature:vector_search}, so an app that uses embeddings is no longer refused,
     * it is sold. A local CLI agent still has no host on cloud at any price, so it stays here.
     *
     * <p>Detection is unchanged for both: {@code ce_exclusive_features} still lists everything
     * found, because the list is what explains the badge and the refusal to a human and to an
     * agent. Only the BLOCKING decision narrowed.
     */
    public static final Set<String> BLOCKING_FEATURES = Set.of(FEATURE_CLI_AGENT);

    /** Whether this feature set makes the publication un-installable on managed cloud. */
    public static boolean blocksInstall(Collection<String> features) {
        return features != null && features.stream().anyMatch(BLOCKING_FEATURES::contains);
    }

    /**
     * Column type that only self-hosted supports. Snapshots carry the enum's
     * JSON form, which is LOWERCASE ({@code ColumnType.VECTOR} is
     * {@code @JsonValue "vector"}); the comparison is case-insensitive so a
     * hand-written or legacy uppercase payload is caught too.
     */
    private static final String VECTOR_COLUMN_TYPE = "vector";

    /**
     * Depth cap for the sub-workflow / sub-agent recursion. This is a
     * belt-and-braces bound so a malformed snapshot can never turn a publish
     * into a stack overflow; the real bound is the {@code visitedWorkflowIds}
     * cycle guard, which admits each sub-workflow exactly once.
     *
     * <p>It MUST stay at or above the depth the PRODUCERS can capture, because a
     * shallower cap is the one way this detector produces a FALSE NEGATIVE: the
     * snapshot genuinely contains a CLI agent below the cap, the scan stops
     * before reaching it, and the publication ships unflagged and installable on
     * managed cloud, which is exactly what this class exists to prevent. Every
     * other bound in here fails safe (over-flag); this one does not.
     *
     * <p>Sub-agents are capped at {@code AgentPublicationService.MAX_AGENT_DEPTH}
     * (15), but WORKFLOW sub-plans are NOT depth-capped at all: they are bounded
     * by a COUNT budget ({@code WorkflowPublicationService.maxSnapshottedSubWorkflows},
     * 1000), so a linear chain of sub-workflows can nest as deep as that budget
     * allows. The cap therefore matches that budget rather than the agent depth,
     * which makes it unreachable in practice. The backfill SQL uses unbounded
     * {@code $.**} and must agree with this class.
     */
    private static final int MAX_DEPTH = 1000;

    /**
     * Separate, deliberately small bound for the {@code children} nesting inside
     * a captured {@code mappingSpec}. Column structures are shallow by
     * construction, and unlike the plan recursion this one has no cycle guard.
     */
    private static final int MAX_COLUMN_NESTING = 20;

    /**
     * CE-exclusive features used by a WORKFLOW plan snapshot, recursing into
     * {@code _snapshot_subworkflows}. Empty set = installable anywhere.
     */
    public static Set<String> detectInPlan(Map<String, Object> planSnapshot) {
        Set<String> features = new LinkedHashSet<>();
        scanPlan(planSnapshot, features, new HashSet<>(), 0);
        return features;
    }

    /**
     * CE-exclusive features used by an AGENT publication snapshot: the agent's
     * own provider, its sub-agents, and every workflow plan it embeds.
     */
    public static Set<String> detectInAgentSnapshot(Map<String, Object> agentSnapshot) {
        Set<String> features = new LinkedHashSet<>();
        scanAgentSnapshot(agentSnapshot, features, new HashSet<>(), 0);
        return features;
    }

    /**
     * Convenience for callers holding both payloads (a publication carries a
     * plan snapshot, an agent snapshot, or neither). Nulls are ignored.
     */
    public static Set<String> detect(Map<String, Object> planSnapshot, Map<String, Object> agentSnapshot) {
        Set<String> features = new LinkedHashSet<>();
        features.addAll(detectInPlan(planSnapshot));
        features.addAll(detectInAgentSnapshot(agentSnapshot));
        return features;
    }

    /**
     * THE chokepoint every publish/update path calls: re-derive the label from
     * whatever snapshot the entity currently carries and stamp it on the row.
     *
     * <p>Always assigns both fields, including the empty case - a publication
     * that DROPS its last CLI agent on an update must lose the badge in the
     * same pass, otherwise it stays un-installable on cloud forever.
     *
     * <p><b>The two fields answer two different questions since 2026-09-03.</b>
     * {@code ce_exclusive_features} lists everything detected, and
     * {@code ce_exclusive} says only whether one of those makes the app impossible here
     * ({@link #BLOCKING_FEATURES}). They used to be the same question, and collapsing them was
     * what made a table with an embedding column un-installable on cloud at any price.
     */
    public static void applyTo(WorkflowPublicationEntity publication) {
        if (publication == null) {
            return;
        }
        Set<String> features = detect(publication.getPlanSnapshot(), publication.getAgentSnapshot());
        publication.setCeExclusive(blocksInstall(features));
        publication.setCeExclusiveFeatures(toSortedList(features));
    }

    /**
     * Stable, persistable form of a detection result: sorted so the stored
     * JSONB (and any test asserting on it) does not depend on traversal order.
     */
    public static List<String> toSortedList(Collection<String> features) {
        List<String> sorted = new ArrayList<>(features == null ? List.of() : features);
        sorted.sort(String::compareTo);
        return sorted;
    }

    // ------------------------------------------------------------------
    // Plan walk
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void scanPlan(Map<String, Object> plan, Set<String> features,
                                 Set<String> visitedWorkflowIds, int depth) {
        if (plan == null || plan.isEmpty() || depth > MAX_DEPTH) {
            return;
        }

        // agents[] - THREE key spellings, all of which occur in real plans:
        //  * `_snapshot_agent_modelProvider` - written at publish time, but ONLY
        //    for nodes backed by an agent entity (enrichPlanWithAgentData skips
        //    a node with no agentConfigId);
        //  * `provider` - what an INLINE agent / classify / guardrail node
        //    carries (the builder writes it, the plan parser reads it at
        //    execution). These nodes never get the _snapshot_ key, so relying on
        //    that alone would miss the most common CLI-agent shape entirely;
        //  * `modelProvider` - the entity field name, for a plan inspected
        //    before enrichment.
        for (Map<String, Object> agentNode : mapsIn(plan.get("agents"))) {
            if (usesBridgeProvider(agentNode)) {
                features.add(FEATURE_CLI_AGENT);
            }
        }

        // tables[] - vector columns in the captured schema, and similarity
        // search configured on the CRUD node itself.
        for (Map<String, Object> tableNode : mapsIn(plan.get("tables"))) {
            if (hasVectorColumn(tableNode.get("_snapshot_ds_mappingSpec"), 0)
                    || hasSimilarityConfig(tableNode)) {
                features.add(FEATURE_VECTOR_SEARCH);
            }
        }

        // Standalone RESOURCE snapshots are not workflow plans:
        //  * TABLE      - the flat datasource payload (TableResourceStrategy),
        //                 whose schema sits at the ROOT under "mappingSpec";
        //  * INTERFACE  - the interface payload with its backing table nested
        //                 under "embeddedTable" (InterfaceResourceStrategy).
        // Missing the nested one let an interface bound to a vector table
        // publish unflagged and be cloned on cloud with the embedding column
        // stripped - the exact silent degradation this feature exists to stop.
        if (hasVectorColumn(plan.get("mappingSpec"), 0)) {
            features.add(FEATURE_VECTOR_SEARCH);
        }
        if (plan.get("embeddedTable") instanceof Map<?, ?> embeddedTable
                && hasVectorColumn(((Map<String, Object>) embeddedTable).get("mappingSpec"), 0)) {
            features.add(FEATURE_VECTOR_SEARCH);
        }

        // Every feature already found - nothing deeper can change the answer.
        if (features.size() >= ALL_FEATURES.size()) {
            return;
        }

        if (plan.get("_snapshot_subworkflows") instanceof Map<?, ?> subWorkflows) {
            for (Map.Entry<?, ?> entry : subWorkflows.entrySet()) {
                String workflowId = String.valueOf(entry.getKey());
                if (!visitedWorkflowIds.add(workflowId)) {
                    continue; // cycle / duplicate reference
                }
                if (entry.getValue() instanceof Map<?, ?> subSnapshot
                        && ((Map<String, Object>) subSnapshot).get("plan") instanceof Map<?, ?> subPlan) {
                    scanPlan((Map<String, Object>) subPlan, features, visitedWorkflowIds, depth + 1);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Agent snapshot walk
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void scanAgentSnapshot(Map<String, Object> snapshot, Set<String> features,
                                          Set<String> visitedWorkflowIds, int depth) {
        if (snapshot == null || snapshot.isEmpty() || depth > MAX_DEPTH) {
            return;
        }

        if (snapshot.get("agent") instanceof Map<?, ?> agent
                && usesBridgeProvider((Map<String, Object>) agent)) {
            features.add(FEATURE_CLI_AGENT);
        }

        // Standalone tables granted to the agent live in their OWN container,
        // NOT inside a workflow plan: {dsId: {name, ..., mappingSpec, items}}.
        // Missing this is how a RAG agent whose only vector table is attached
        // directly (no workflow) would slip through and be cloned on cloud with
        // the embedding column silently stripped.
        for (Map<String, Object> datasource : mapsIn(snapshot.get("datasources"))) {
            if (hasVectorColumn(datasource.get("mappingSpec"), 0)) {
                features.add(FEATURE_VECTOR_SEARCH);
            }
        }

        // workflows/subAgents are keyed maps on the publish side but a few
        // readers treat them as lists - accept both rather than depend on it.
        for (Map<String, Object> workflow : mapsIn(snapshot.get("workflows"))) {
            if (workflow.get("plan") instanceof Map<?, ?> plan) {
                scanPlan((Map<String, Object>) plan, features, visitedWorkflowIds, depth + 1);
            }
        }
        for (Map<String, Object> subAgent : mapsIn(snapshot.get("subAgents"))) {
            scanAgentSnapshot(subAgent, features, visitedWorkflowIds, depth + 1);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Every provider key an agent-ish node can carry, in ANY of the shapes the
     * platform writes:
     * <ul>
     *   <li>{@code _snapshot_agent_modelProvider} - written at publish time, but
     *       ONLY for nodes backed by an agent entity;</li>
     *   <li>{@code provider} - an INLINE agent / classify / guardrail node,
     *       which never gets the {@code _snapshot_} key;</li>
     *   <li>{@code modelProvider} - the agent entity's own field;</li>
     *   <li>{@code _snapshot_agent_compactionModelProvider} - the COLD-summariser
     *       override. It runs on the same bridge host, so an agent whose MAIN
     *       provider is an API but whose compaction provider is a CLI is just as
     *       unrunnable on managed cloud.</li>
     * </ul>
     */
    private static boolean usesBridgeProvider(Map<String, Object> node) {
        for (String key : List.of("_snapshot_agent_modelProvider", "provider", "modelProvider",
                "_snapshot_agent_compactionModelProvider", "compactionModelProvider")) {
            if (BridgeProviders.isBridgeProvider(asString(node.get(key)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vector column lookup over a captured {@code mappingSpec}
     * ({@code {columnName: {path, type, structure, children, display}}}),
     * recursing into {@code children} so a nested object column carrying an
     * embedding is not missed.
     */
    @SuppressWarnings("unchecked")
    private static boolean hasVectorColumn(Object mappingSpec, int depth) {
        if (!(mappingSpec instanceof Map<?, ?> spec) || depth > MAX_COLUMN_NESTING) {
            return false;
        }
        for (Object value : spec.values()) {
            if (!(value instanceof Map<?, ?> column)) {
                continue;
            }
            Map<String, Object> col = (Map<String, Object>) column;
            if (VECTOR_COLUMN_TYPE.equalsIgnoreCase(asString(col.get("type")))) {
                return true;
            }
            if (hasVectorColumn(col.get("children"), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Similarity search on a CRUD table node. The plan parser accepts the
     * {@code similarity} block at the node root, under {@code crud}, or under
     * {@code params}, so all three are checked here for the same reason.
     */
    @SuppressWarnings("unchecked")
    private static boolean hasSimilarityConfig(Map<String, Object> tableNode) {
        if (isPresent(tableNode.get("similarity"))) {
            return true;
        }
        for (String container : List.of("crud", "params")) {
            if (tableNode.get(container) instanceof Map<?, ?> block
                    && isPresent(((Map<String, Object>) block).get("similarity"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A similarity block counts only when it names the vector COLUMN it
     * searches. Accepting any non-empty map would mark a publication
     * cloud-uninstallable over a leftover {@code {"topK": 5}} that performs no
     * similarity search at all.
     *
     * <p>Deliberately BROADER than {@code WorkflowPlanParser.parseCrudConfig},
     * which additionally requires {@code queryVector}: a block naming a column
     * but templating its vector at run time still targets a vector column, and
     * the safe direction here is to flag it.
     *
     * <p>The stringified-JSON form the frontend sometimes sends is matched with
     * a regex rather than parsed (a pure function with no parser dependency),
     * and it requires a NON-BLANK column exactly like the map form, so the two
     * shapes cannot disagree about the same configuration.
     */
    private static boolean isPresent(Object similarity) {
        if (similarity instanceof Map<?, ?> map) {
            Object column = map.get("column");
            return column instanceof String text && !text.isBlank();
        }
        if (!(similarity instanceof String text)) {
            return false;
        }
        var matcher = COLUMN_VALUE.matcher(text);
        return matcher.find() && !matcher.group(1).isBlank();
    }

    /** Captures the value of {@code "column"} inside a stringified similarity block. */
    private static final Pattern COLUMN_VALUE =
            Pattern.compile("\"column\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Normalizes the two container shapes found in snapshots (a JSON array of
     * nodes, or a map keyed by id) into a flat list of maps.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapsIn(Object container) {
        Collection<?> values;
        if (container instanceof List<?> list) {
            values = list;
        } else if (container instanceof Map<?, ?> map) {
            values = map.values();
        } else {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }
}
