package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity guard between {@code NodeParamsValidator.PARAM_ALIASES} (what {@code add_node} ACCEPTS)
 * and what each creator actually READS.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An alias the validator accepts but the creator never reads is worse than a rejected one: the
 * param sails through validation and is then silently dropped. Audit 2026-07-31 found four node
 * types out of parity, and the agent node was the dangerous shape - its prompt is OPTIONAL, so
 * {@code add_node(type='agent', params={instruction: '...'})} produced a successful agent node
 * with NO prompt, with no error anywhere. classify / guardrail / decision failed loudly instead,
 * but on a message naming a param the caller never used.
 *
 * <p>The reverse gap (creator reads a spelling the validator rejects) only costs an
 * {@code Unknown parameter} error, but it makes the help a liar - that is how the loop node's
 * {@code loopCondition} was rejected while the help advertised it.
 *
 * <h2>How to keep this green</h2>
 *
 * <p>The CREATOR is the source of truth for what is supported. When you add an alias to
 * {@code PARAM_ALIASES}, teach the matching creator to read it (see
 * {@link CreatorBase#firstNonBlank}) and add it below. Do not "fix" a failure by deleting the
 * expectation here.
 */
@DisplayName("Param alias parity - validator accepts only what creators read")
class ParamAliasCreatorParityTest {

    /**
     * Spellings each creator genuinely reads, transcribed from the creator source.
     * agent    -> AgentCreator: firstNonBlank(prompt, instruction, message, task, input)
     * classify -> ClassifyCreator: firstNonBlank(prompt, instruction, system_prompt, content, input, text, data)
     * guardrail-> GuardrailCreator: firstNonBlank(input, content, text) + firstNonBlank(prompt, system_prompt, instruction)
     * decision -> DecisionNodeCreator: conditions, decisionConditions, cases (tryRecoverConditions), branches, rules
     * loop     -> UtilityNodeCreator: condition/loopCondition/loop_condition/expression/while,
     *             max_iterations/maxIterations/limit
     * response -> UtilityNodeCreator: message, text, content, body, response
     * download_file -> UtilityNodeCreator.executeAddDownloadFile: url/source/link/file_url/href/src,
     *             filename/file_name/output
     * http_request -> UtilityNodeCreator.executeAddHttpRequest: url/endpoint/uri, authType/auth_type,
     *             bodyType/body_type, authConfig/auth_config, queryParams/query_params
     * approval -> DecisionNodeCreator.executeAddApproval: approver_roles/approverRoles/roles,
     *             required_approvals/requiredApprovals, timeout_ms/timeoutMs/timeout,
     *             contextTemplate/context_template, continuationMode/continuation_mode, delegation
     */
    private static final Map<String, Set<String>> CREATOR_READS = Map.ofEntries(
        Map.entry("agent", Set.of("prompt", "instruction", "message", "task", "input", "withMemory", "with_memory")),
        Map.entry("classify", Set.of("prompt", "instruction", "system_prompt", "content", "input", "text", "data")),
        Map.entry("guardrail", Set.of("input", "content", "text", "prompt", "system_prompt", "instruction")),
        Map.entry("decision", Set.of("conditions", "decisionConditions", "cases", "condition", "branches", "rules")),
        Map.entry("loop", Set.of("condition", "loopCondition", "loop_condition", "expression", "while",
                       "max_iterations", "maxIterations", "limit")),
        Map.entry("response", Set.of("message", "text", "content", "body", "response")),
        Map.entry("download_file", Set.of("url", "source", "link", "file_url", "href", "src",
                       "filename", "file_name", "output")),
        Map.entry("http_request", Set.of("url", "endpoint", "uri", "authType", "auth_type",
                       "bodyType", "body_type", "authConfig", "auth_config",
                       "queryParams", "query_params")),
        Map.entry("approval", Set.of("approver_roles", "approverRoles", "roles",
                       "required_approvals", "requiredApprovals",
                       "timeout_ms", "timeoutMs", "timeout",
                       "contextTemplate", "context_template",
                       "continuationMode", "continuation_mode", "delegation"))
    );

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> paramAliases() {
        try {
            Field f = NodeParamsValidator.class.getDeclaredField("PARAM_ALIASES");
            f.setAccessible(true);
            return (Map<String, Map<String, String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "PARAM_ALIASES moved or was renamed - this parity guard must be updated with it", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> nestedConfigAliases() {
        try {
            Field f = com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderModifier.class
                .getDeclaredField("NESTED_CONFIG_ALIASES");
            f.setAccessible(true);
            return (Map<String, Map<String, String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "NESTED_CONFIG_ALIASES moved or was renamed - this parity guard must be updated with it", e);
        }
    }

    @Nested
    @DisplayName("no silent drop")
    class NoSilentDrop {

        /**
         * The dangerous direction. Every alias the validator waves through must be a spelling the
         * creator reads, otherwise the param is accepted and lost.
         */
        @Test
        @DisplayName("regression: every accepted alias is read by its creator")
        void everyAcceptedAliasIsRead() {
            Map<String, Set<String>> unread = new LinkedHashMap<>();

            paramAliases().forEach((nodeType, aliases) -> {
                Set<String> reads = CREATOR_READS.get(nodeType);
                if (reads == null) {
                    return; // type not covered by this guard yet - see class javadoc
                }
                Set<String> missing = new TreeSet<>(aliases.keySet());
                missing.removeAll(reads);
                if (!missing.isEmpty()) {
                    unread.put(nodeType, missing);
                }
            });

            assertThat(unread)
                .as("these aliases pass validation but no creator reads them, so the param is "
                    + "silently dropped - teach the creator (CreatorBase.firstNonBlank) or remove "
                    + "the alias")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("no rejected-but-supported spelling")
    class NoRejectedSupportedSpelling {

        /**
         * The reverse direction, checked on the canonical param names only: a spelling the creator
         * reads must either BE the canonical schema name or be listed as an alias, or add_node
         * answers "Unknown parameter" for something that would have worked.
         */
        @Test
        @DisplayName("loop: every spelling the creator reads is accepted")
        void loopSpellingsAreAccepted() {
            Set<String> accepted = new java.util.HashSet<>(paramAliases().get("loop").keySet());
            // canonical schema names, always accepted without an alias entry
            accepted.add("condition");
            accepted.add("max_iterations");

            Set<String> rejected = new TreeSet<>(CREATOR_READS.get("loop"));
            rejected.removeAll(accepted);

            assertThat(rejected)
                .as("UtilityNodeCreator.createLoop honours these, so add_node must not reject them")
                .isEmpty();
        }

        /**
         * The bug this guard was extended for. {@code DecisionNodeCreator.executeAddApproval} honours
         * both conventions for every approval param, and the builder help advertises the camelCase
         * ones, but the node's documented schema stores a single spelling each - so the other one
         * came back as "Unknown parameter" for a value that would have worked.
         */
        @Test
        @DisplayName("regression: approval accepts every spelling executeAddApproval reads (timeoutMs was rejected)")
        void approvalSpellingsAreAccepted() {
            // Assert the entry exists first: without this the pre-change run dies in an NPE and
            // the message below, written for exactly this failure, never prints.
            assertThat(paramAliases())
                .as("the approval node has aliases to declare, so the map must carry an entry for it")
                .containsKey("approval");

            Set<String> accepted = new java.util.HashSet<>(paramAliases().get("approval").keySet());
            // canonical schema names, accepted without an alias entry
            accepted.addAll(Set.of("timeout_ms", "approver_roles", "required_approvals",
                                   "contextTemplate", "continuationMode", "delegation"));

            Set<String> rejected = new TreeSet<>(CREATOR_READS.get("approval"));
            rejected.removeAll(accepted);

            assertThat(rejected)
                .as("DecisionNodeCreator.executeAddApproval honours these, so add_node must not reject them")
                .isEmpty();
        }

        /**
         * The alias VALUE is what the required-param bypass resolves against
         * ({@code NodeParamsValidator} checks whether an alias covers a missing required param).
         * Approval has no required param today, so a typo in a value would be inert - and
         * invisible to every other test here, which reads only {@code keySet()}. Pin the
         * targets so the map cannot rot into a set of keys pointing nowhere.
         */
        @Test
        @DisplayName("approval: each alias resolves to the canonical name the schema declares")
        void approvalAliasesPointAtCanonicalNames() {
            assertThat(paramAliases().get("approval"))
                .containsEntry("timeoutMs", "timeout_ms")
                .containsEntry("timeout", "timeout_ms")
                .containsEntry("approverRoles", "approver_roles")
                .containsEntry("roles", "approver_roles")
                .containsEntry("requiredApprovals", "required_approvals")
                .containsEntry("context_template", "contextTemplate")
                .containsEntry("continuation_mode", "continuationMode");
        }

        /**
         * The third copy of the same truth. add_node normalizes through the creator, modify
         * normalizes through {@code WorkflowBuilderModifier.NESTED_CONFIG_ALIASES}. Nothing
         * else fails if the modify table loses a spelling the add path still accepts, and the
         * result is the original bug back on the edit half: a green call that changes nothing.
         *
         * <p>Storage names are the camelCase fields {@code WorkflowPlanParser.parseApprovalConfig}
         * reads. Every OTHER spelling add_node knows must be rewritten to one of them on modify.
         */
        @Test
        @DisplayName("regression: every approval spelling add_node accepts is normalized on the modify path too")
        void approvalSpellingsAreNormalizedOnModify() {
            Set<String> storageNames = Set.of("timeoutMs", "approverRoles", "requiredApprovals",
                                              "contextTemplate", "continuationMode", "delegation");
            Map<String, String> modifyAliases = nestedConfigAliases().get("approval");
            assertThat(modifyAliases)
                .as("the modify path must declare the approval node's aliases")
                .isNotNull();

            Set<String> addPathSpellings = new TreeSet<>(paramAliases().get("approval").keySet());
            addPathSpellings.addAll(paramAliases().get("approval").values());  // canonical schema names
            addPathSpellings.removeAll(storageNames);

            assertThat(modifyAliases.keySet())
                .as("a spelling add_node accepts but modify does not rewrite lands under a key "
                    + "WorkflowPlanParser never reads, so the edit silently does nothing")
                .containsExactlyInAnyOrderElementsOf(addPathSpellings);

            assertThat(modifyAliases.values())
                .as("every rewrite must target a field the parser actually reads")
                .isSubsetOf(storageNames);
        }

        @Test
        @DisplayName("response: the node's own name is an accepted spelling")
        void responseSpellingIsAccepted() {
            assertThat(paramAliases().get("response"))
                .as("UtilityNodeCreator reads `response` - rejecting the spelling that matches the "
                    + "node's own name is the most surprising possible error")
                .containsKey("response");
        }
    }

    @Nested
    @DisplayName("CreatorBase.firstNonBlank")
    class FirstNonBlankTests {

        @Test
        @DisplayName("Returns the first key present and non-blank, in the order given")
        void returnsFirstNonBlankInOrder() {
            Map<String, Object> params = new HashMap<>();
            params.put("instruction", "from alias");
            params.put("task", "later alias");

            assertThat(CreatorBase.firstNonBlank(params, "prompt", "instruction", "task"))
                .isEqualTo("from alias");
        }

        @Test
        @DisplayName("The canonical name wins over an alias when both are present")
        void canonicalWinsOverAlias() {
            Map<String, Object> params = new HashMap<>();
            params.put("prompt", "canonical");
            params.put("instruction", "alias");

            assertThat(CreatorBase.firstNonBlank(params, "prompt", "instruction")).isEqualTo("canonical");
        }

        @Test
        @DisplayName("A blank value is skipped, not returned")
        void blankIsSkipped() {
            Map<String, Object> params = new HashMap<>();
            params.put("prompt", "   ");
            params.put("instruction", "real value");

            assertThat(CreatorBase.firstNonBlank(params, "prompt", "instruction")).isEqualTo("real value");
        }

        @Test
        @DisplayName("Returns null when nothing matches, and tolerates a null map")
        void nullWhenNothingMatches() {
            assertThat(CreatorBase.firstNonBlank(Map.of("other", "x"), "prompt", "instruction")).isNull();
            assertThat(CreatorBase.firstNonBlank(null, "prompt")).isNull();
        }
    }
}
