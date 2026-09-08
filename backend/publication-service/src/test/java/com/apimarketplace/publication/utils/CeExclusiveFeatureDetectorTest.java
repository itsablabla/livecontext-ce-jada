package com.apimarketplace.publication.utils;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CE-exclusive label is computed ONLY from the frozen snapshot, so these
 * tests pin the exact snapshot shapes the publish pipeline produces. A miss
 * here means a self-hosted-only app is offered for install on managed cloud and
 * fails (or silently degrades) after the user installs it.
 */
@DisplayName("CeExclusiveFeatureDetector")
class CeExclusiveFeatureDetectorTest {

    private static Map<String, Object> agentNode(String provider) {
        return Map.of("agentConfigId", "a-1", "_snapshot_agent_modelProvider", provider);
    }

    /**
     * The REAL serialized form: {@code ColumnType.VECTOR} carries
     * {@code @JsonValue "vector"}, so every snapshot in the database holds the
     * lowercase spelling. Using the enum NAME here would let a case-sensitive
     * regression ship green.
     */
    private static Map<String, Object> vectorColumn() {
        return Map.of("path", "embedding", "type", "vector");
    }

    private static Map<String, Object> textColumn() {
        return Map.of("path", "title", "type", "text");
    }

    @Nested
    @DisplayName("local-CLI agents")
    class CliAgents {

        @Test
        @DisplayName("an agent bound to a bridge provider marks the plan CE-exclusive")
        void bridgeProviderDetected() {
            Map<String, Object> plan = Map.of("agents", List.of(agentNode("claude-code")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("every bridge provider is recognised, not just claude-code")
        void allBridgeProvidersDetected() {
            for (String provider : List.of("claude-code", "codex", "gemini-cli", "mistral-vibe")) {
                Map<String, Object> plan = Map.of("agents", List.of(agentNode(provider)));
                assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                        .as("provider %s", provider)
                        .contains(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
            }
        }

        @Test
        @DisplayName("an API provider (anthropic) leaves the plan installable anywhere")
        void apiProviderNotDetected() {
            Map<String, Object> plan = Map.of("agents", List.of(agentNode("anthropic")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("an INLINE agent node (provider key, no agentConfigId) is detected")
        void inlineAgentProviderDetected() {
            // The publish-time enrichment SKIPS nodes without agentConfigId, so an
            // inline agent / classify / guardrail node never gets the
            // _snapshot_agent_modelProvider key - its provider stays under
            // `provider`. Missing this shape means the most common CLI-agent
            // workflow publishes unflagged and installs on cloud.
            Map<String, Object> plan = Map.of("agents",
                    List.of(Map.of("type", "agent", "label", "Reviewer", "provider", "claude-code")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("a COLD-summariser (compaction) provider on a bridge is detected")
        void compactionProviderDetected() {
            // The compaction model runs on the same bridge host as the main one, so
            // an agent whose MAIN provider is an API but whose summariser is a CLI
            // is just as unrunnable on managed cloud.
            Map<String, Object> plan = Map.of("agents", List.of(Map.of(
                    "agentConfigId", "a-1",
                    "_snapshot_agent_modelProvider", "openai",
                    "_snapshot_agent_compactionModelProvider", "gemini-cli")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("an inline node on an API provider stays installable anywhere")
        void inlineApiProviderNotDetected() {
            Map<String, Object> plan = Map.of("agents",
                    List.of(Map.of("type", "classify", "label", "Router", "provider", "openai")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("the raw (not-yet-enriched) modelProvider key is detected too")
        void rawModelProviderKeyDetected() {
            Map<String, Object> plan = Map.of("agents",
                    List.of(Map.of("agentConfigId", "a-1", "modelProvider", "codex")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .contains(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }
    }

    @Nested
    @DisplayName("vector / embeddings")
    class VectorSearch {

        @Test
        @DisplayName("a VECTOR column in a captured table schema marks the plan CE-exclusive")
        void vectorColumnDetected() {
            Map<String, Object> plan = Map.of("tables", List.of(Map.of(
                    "dataSourceId", "42",
                    "_snapshot_ds_mappingSpec", Map.of("embedding", vectorColumn()))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a VECTOR column nested under children is detected (not only top level)")
        void nestedVectorColumnDetected() {
            Map<String, Object> parent = Map.of(
                    "path", "meta", "type", "OBJECT",
                    "children", Map.of("embedding", vectorColumn()));
            Map<String, Object> plan = Map.of("tables", List.of(Map.of(
                    "dataSourceId", "42",
                    "_snapshot_ds_mappingSpec", Map.of("meta", parent))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a table with only TEXT columns stays installable anywhere")
        void nonVectorTableNotDetected() {
            Map<String, Object> plan = Map.of("tables", List.of(Map.of(
                    "dataSourceId", "42",
                    "_snapshot_ds_mappingSpec", Map.of("title", textColumn()))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("an uppercase type is detected too (defensive, for a hand-written payload)")
        void uppercaseVectorTypeAlsoDetected() {
            Map<String, Object> plan = Map.of("tables", List.of(Map.of(
                    "_snapshot_ds_mappingSpec", Map.of("embedding", Map.of("type", "VECTOR")))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a similarity block is detected at the node root, under crud, and under params")
        void similarityDetectedInAllThreeContainers() {
            Map<String, Object> similarity = Map.of("column", "embedding", "topK", 5);

            assertThat(CeExclusiveFeatureDetector.detectInPlan(
                    Map.of("tables", List.of(Map.of("similarity", similarity)))))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
            assertThat(CeExclusiveFeatureDetector.detectInPlan(
                    Map.of("tables", List.of(Map.of("crud", Map.of("similarity", similarity))))))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
            assertThat(CeExclusiveFeatureDetector.detectInPlan(
                    Map.of("tables", List.of(Map.of("params", Map.of("similarity", similarity))))))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("an empty similarity block is not a similarity search")
        void emptySimilarityIgnored() {
            Map<String, Object> plan = Map.of("tables", List.of(Map.of("similarity", Map.of())));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("a similarity block WITHOUT a column is not a similarity search either")
        void similarityWithoutColumnIgnored() {
            // WorkflowPlanParser needs `column` to build a similarity config, so a
            // leftover {"topK": 5} runs no search and must not block cloud installs.
            Map<String, Object> plan = Map.of("tables",
                    List.of(Map.of("params", Map.of("similarity", Map.of("topK", 5)))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("a stringified similarity block naming a column is detected")
        void stringifiedSimilarityDetected() {
            // The builder sometimes stores the block as a JSON STRING.
            Map<String, Object> plan = Map.of("tables",
                    List.of(Map.of("params", Map.of("similarity", "{\"column\":\"embedding\",\"topK\":5}"))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a stringified block with a BLANK column is ignored, exactly like the map form")
        void stringifiedSimilarityWithBlankColumnIgnored() {
            // The two shapes describe the same configuration; disagreeing about it
            // would make the label depend on how the builder happened to serialise.
            Map<String, Object> plan = Map.of("tables",
                    List.of(Map.of("params", Map.of("similarity", "{\"column\":\"\",\"topK\":5}"))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("a 'vector' value outside a captured schema is NOT a vector column")
        void vectorValueOutsideMappingSpecIgnored() {
            // Free-form payload (interface snapshot data, agent config blobs) is not
            // a schema. Flagging on a bare "type":"vector" anywhere would block an
            // app whose next publish would silently un-block it.
            Map<String, Object> plan = Map.of("interfaces",
                    List.of(Map.of("_snapshot_data", Map.of("rows", List.of(Map.of("type", "vector"))))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan)).isEmpty();
        }

        @Test
        @DisplayName("a standalone INTERFACE carries its backing table under embeddedTable")
        void interfaceEmbeddedTableDetected() {
            // InterfaceResourceStrategy nests the interface's backing table one
            // level down. Scanning only the root mappingSpec let an interface
            // bound to a vector table publish unflagged and get cloned on cloud
            // with the embedding column stripped.
            Map<String, Object> interfaceSnapshot = Map.of(
                    "name", "Semantic search UI",
                    "htmlTemplate", "<div></div>",
                    "embeddedTable", Map.of(
                            "name", "Docs",
                            "mappingSpec", Map.of("embedding", vectorColumn())));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(interfaceSnapshot))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("an interface whose backing table has no vector column stays installable")
        void interfaceEmbeddedTableWithoutVectorIgnored() {
            Map<String, Object> interfaceSnapshot = Map.of(
                    "name", "Contacts UI",
                    "embeddedTable", Map.of("mappingSpec", Map.of("email", textColumn())));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(interfaceSnapshot)).isEmpty();
        }

        @Test
        @DisplayName("a standalone TABLE snapshot carries its schema at the root, not under tables[]")
        void standaloneTableSnapshotDetected() {
            Map<String, Object> tableSnapshot = Map.of(
                    "name", "Docs",
                    "sourceType", "INLINE",
                    "mappingSpec", Map.of("embedding", vectorColumn()));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(tableSnapshot))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }
    }

    @Nested
    @DisplayName("recursion")
    class Recursion {

        @Test
        @DisplayName("a CLI agent buried in a sub-workflow snapshot is still detected")
        void subWorkflowScanned() {
            Map<String, Object> subPlan = Map.of("agents", List.of(agentNode("gemini-cli")));
            Map<String, Object> plan = Map.of(
                    "agents", List.of(agentNode("openai")),
                    "_snapshot_subworkflows", Map.of("wf-1", Map.of("plan", subPlan)));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .contains(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("a self-referencing sub-workflow terminates AND still reports what it found")
        void cyclicSubWorkflowTerminates() {
            // The CLI agent sits in the cycling plan on purpose: asserting only
            // "empty" would pass even with the visited-set deleted, because the
            // depth cap alone terminates the recursion. Returning CLI_AGENT proves
            // the walk completed rather than blew up or bailed out early.
            Map<String, Object> plan = new java.util.HashMap<>();
            Map<String, Object> subSnapshot = new java.util.HashMap<>();
            subSnapshot.put("plan", plan); // A -> A
            plan.put("agents", List.of(agentNode("claude-code")));
            plan.put("_snapshot_subworkflows", Map.of("wf-self", subSnapshot));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("scans as DEEP as the publish side captures - a shallower cap would leak")
        void scansToTheProducersMaxDepth() {
            // AgentPublicationService.MAX_AGENT_DEPTH = 15, so a snapshot really can
            // carry a CLI agent 15 levels down. A detector cap below that is the one
            // way this can FALSE-NEGATIVE: the app ships unflagged and installs on
            // managed cloud. Nest one level deeper than the producer ever goes.
            Map<String, Object> deepest = new java.util.HashMap<>();
            deepest.put("agents", List.of(agentNode("claude-code")));

            Map<String, Object> plan = deepest;
            for (int level = 0; level < 16; level++) {
                Map<String, Object> parent = new java.util.HashMap<>();
                parent.put("_snapshot_subworkflows", Map.of("wf-" + level, Map.of("plan", plan)));
                plan = parent;
            }

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("a sub-workflow chain longer than the old depth cap is still scanned")
        void scansBeyondTheOldDepthCap() {
            // The old cap was 20, chosen against AgentPublicationService.MAX_AGENT_DEPTH (15).
            // That was the wrong yardstick: WORKFLOW sub-plans are not depth-capped at all on
            // the publish side, only budgeted by COUNT
            // (WorkflowPublicationService.maxSnapshottedSubWorkflows = 1000). So a linear chain
            // of 21+ sub-workflows is genuinely producible, and a cap of 20 silently dropped the
            // CLI agent below it - the app then installs on managed cloud and cannot run.
            Map<String, Object> deepest = new java.util.HashMap<>();
            deepest.put("agents", List.of(agentNode("codex")));

            Map<String, Object> plan = deepest;
            for (int level = 0; level < 60; level++) {
                Map<String, Object> parent = new java.util.HashMap<>();
                parent.put("_snapshot_subworkflows", Map.of("wf-deep-" + level, Map.of("plan", plan)));
                plan = parent;
            }

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .as("a CLI agent 60 sub-workflows down must still be found")
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("a bare compactionModelProvider on a PLAN agent node is detected")
        void barePlanCompactionProviderDetected() {
            // The agent's COLD summariser runs on the same bridge host, so an agent whose main
            // provider is an API but whose compaction provider is a CLI is just as unrunnable on
            // managed cloud. The plan-node shape carries the key BARE (no _snapshot_ prefix); the
            // backfill SQL listed only the prefixed form, so the two disagreed on this row.
            Map<String, Object> plan = Map.of("agents", List.of(Map.of(
                    "type", "agent",
                    "provider", "anthropic",
                    "compactionModelProvider", "claude-code")));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("both features are reported when a plan uses each")
        void bothFeaturesReported() {
            Map<String, Object> plan = Map.of(
                    "agents", List.of(agentNode("claude-code")),
                    "tables", List.of(Map.of("_snapshot_ds_mappingSpec", Map.of("e", vectorColumn()))));

            assertThat(CeExclusiveFeatureDetector.detectInPlan(plan))
                    .containsExactlyInAnyOrder(
                            CeExclusiveFeatureDetector.FEATURE_CLI_AGENT,
                            CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }
    }

    @Nested
    @DisplayName("agent publications")
    class AgentSnapshots {

        @Test
        @DisplayName("the published agent's own CLI provider marks it CE-exclusive")
        void agentProviderDetected() {
            Map<String, Object> snapshot = Map.of("agent", Map.of("modelProvider", "claude-code"));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("a sub-agent on a CLI provider marks the whole publication CE-exclusive")
        void subAgentProviderDetected() {
            Map<String, Object> snapshot = Map.of(
                    "agent", Map.of("modelProvider", "openai"),
                    "subAgents", Map.of("sub-1", Map.of("agent", Map.of("modelProvider", "mistral-vibe"))));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot))
                    .contains(CeExclusiveFeatureDetector.FEATURE_CLI_AGENT);
        }

        @Test
        @DisplayName("an embedded workflow's vector table is detected through the agent snapshot")
        void embeddedWorkflowScanned() {
            Map<String, Object> plan = Map.of("tables",
                    List.of(Map.of("_snapshot_ds_mappingSpec", Map.of("e", vectorColumn()))));
            Map<String, Object> snapshot = Map.of(
                    "agent", Map.of("modelProvider", "openai"),
                    "workflows", Map.of("wf-1", Map.of("plan", plan)));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a vector table attached DIRECTLY to the agent (no workflow) is detected")
        void standaloneAgentDatasourceDetected() {
            // AgentPublicationService stores agent-granted tables in their own
            // `datasources` container, NOT inside a workflow plan. Missing it
            // means a RAG agent installs on cloud with its embedding column
            // silently stripped at clone time - green HTTP, broken app.
            Map<String, Object> snapshot = Map.of(
                    "agent", Map.of("modelProvider", "openai"),
                    "datasources", Map.of("42", Map.of(
                            "name", "Docs",
                            "sourceType", "INLINE",
                            "mappingSpec", Map.of("embedding", vectorColumn()))));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot))
                    .containsExactly(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a sub-agent's directly-attached vector table is detected through the recursion")
        void subAgentDatasourceDetected() {
            Map<String, Object> snapshot = Map.of(
                    "agent", Map.of("modelProvider", "openai"),
                    "subAgents", Map.of("sub-1", Map.of(
                            "agent", Map.of("modelProvider", "openai"),
                            "datasources", Map.of("7", Map.of(
                                    "mappingSpec", Map.of("embedding", vectorColumn()))))));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot))
                    .contains(CeExclusiveFeatureDetector.FEATURE_VECTOR_SEARCH);
        }

        @Test
        @DisplayName("a non-vector table attached to the agent leaves it installable anywhere")
        void nonVectorAgentDatasourceNotDetected() {
            Map<String, Object> snapshot = Map.of(
                    "agent", Map.of("modelProvider", "openai"),
                    "datasources", Map.of("42", Map.of(
                            "mappingSpec", Map.of("title", textColumn()))));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot)).isEmpty();
        }

        @Test
        @DisplayName("an API-only agent is installable anywhere")
        void apiOnlyAgentNotDetected() {
            Map<String, Object> snapshot = Map.of("agent", Map.of("modelProvider", "anthropic"));

            assertThat(CeExclusiveFeatureDetector.detectInAgentSnapshot(snapshot)).isEmpty();
        }
    }

    @Nested
    @DisplayName("applyTo")
    class ApplyTo {

        @Test
        @DisplayName("stamps the flag and the sorted feature list on the entity")
        void stampsFlagAndFeatures() {
            WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
            publication.setPlanSnapshot(Map.of(
                    "tables", List.of(Map.of("_snapshot_ds_mappingSpec", Map.of("e", vectorColumn()))),
                    "agents", List.of(agentNode("claude-code"))));

            CeExclusiveFeatureDetector.applyTo(publication);

            assertThat(publication.isCeExclusive()).isTrue();
            assertThat(publication.getCeExclusiveFeatures())
                    .containsExactly("CLI_AGENT", "VECTOR_SEARCH"); // sorted, order-stable
        }

        @Test
        @DisplayName("CLEARS a previously set flag when the update dropped the feature")
        void clearsFlagWhenFeatureRemoved() {
            WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
            publication.setCeExclusive(true);
            publication.setCeExclusiveFeatures(List.of("CLI_AGENT"));
            publication.setPlanSnapshot(Map.of("agents", List.of(agentNode("anthropic"))));

            CeExclusiveFeatureDetector.applyTo(publication);

            assertThat(publication.isCeExclusive()).isFalse();
            assertThat(publication.getCeExclusiveFeatures()).isEmpty();
        }

        @Test
        @DisplayName("a publication with no snapshot at all is not CE-exclusive")
        void noSnapshotIsNotExclusive() {
            WorkflowPublicationEntity publication = new WorkflowPublicationEntity();

            CeExclusiveFeatureDetector.applyTo(publication);

            assertThat(publication.isCeExclusive()).isFalse();
            assertThat(publication.getCeExclusiveFeatures()).isEmpty();
        }
    }
    /**
     * The 2026-09-03 split, at the one line that decides it. `applyTo` writes two fields that used
     * to answer the same question and no longer do: the LIST records every capability found, the
     * BOOLEAN records only whether one of them makes the app impossible here. Collapsing them was
     * what made a table with an embedding column un-installable on cloud at any price.
     */
    @Nested
    @DisplayName("blocking versus merely labelled")
    class BlockingSubset {

        private WorkflowPublicationEntity stamped(java.util.Map<String, Object> plan) {
            WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
            publication.setPlanSnapshot(plan);
            CeExclusiveFeatureDetector.applyTo(publication);
            return publication;
        }

        @Test
        @DisplayName("a vector-only app is LABELLED but stays installable")
        void vectorOnlyIsLabelledNotBlocked() {
            WorkflowPublicationEntity publication = stamped(java.util.Map.of("tables", java.util.List.of(
                    java.util.Map.of("_snapshot_ds_mappingSpec",
                            java.util.Map.of("embedding", java.util.Map.of("type", "vector"))))));

            assertThat(publication.getCeExclusiveFeatures()).containsExactly("VECTOR_SEARCH");
            assertThat(publication.isCeExclusive())
                    .as("embeddings run on cloud from a plan; the acquire path reads the LIST to gate on it")
                    .isFalse();
        }

        @Test
        @DisplayName("a CLI-agent app is labelled AND blocked, at any plan")
        void cliAgentStillBlocks() {
            WorkflowPublicationEntity publication = stamped(java.util.Map.of("agents", java.util.List.of(
                    java.util.Map.of("modelProvider", "claude-code"))));

            assertThat(publication.getCeExclusiveFeatures()).containsExactly("CLI_AGENT");
            assertThat(publication.isCeExclusive()).isTrue();
        }

        @Test
        @DisplayName("an app using both is blocked by the CLI agent, and still names both")
        void bothFeaturesBlockOnTheCliAgent() {
            WorkflowPublicationEntity publication = stamped(java.util.Map.of(
                    "agents", java.util.List.of(java.util.Map.of("modelProvider", "codex")),
                    "tables", java.util.List.of(java.util.Map.of("_snapshot_ds_mappingSpec",
                            java.util.Map.of("embedding", java.util.Map.of("type", "vector"))))));

            assertThat(publication.getCeExclusiveFeatures()).containsExactly("CLI_AGENT", "VECTOR_SEARCH");
            assertThat(publication.isCeExclusive()).isTrue();
        }

        @Test
        @DisplayName("blocksInstall names the subset directly, so a reader does not have to infer it")
        void blocksInstallIsExplicit() {
            assertThat(CeExclusiveFeatureDetector.blocksInstall(java.util.List.of("CLI_AGENT"))).isTrue();
            assertThat(CeExclusiveFeatureDetector.blocksInstall(java.util.List.of("VECTOR_SEARCH"))).isFalse();
            assertThat(CeExclusiveFeatureDetector.blocksInstall(java.util.List.of())).isFalse();
            assertThat(CeExclusiveFeatureDetector.blocksInstall(null)).isFalse();
        }
    }
}
