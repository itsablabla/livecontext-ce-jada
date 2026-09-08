package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard over {@code WorkflowBuilderModifier.NESTED_CONFIG_ALIASES}.
 *
 * <h2>Why</h2>
 *
 * <p>That table rewrites a parameter's name before it is stored in a node's nested config. Two
 * ways to get an entry wrong, both silent:
 *
 * <ul>
 *   <li><b>Target does not exist.</b> Rewriting to a name the config record does not carry moves
 *       the value from one unread key to another. The call still reports success and the run
 *       still ignores it, which is the exact defect the table exists to fix.</li>
 *   <li><b>Source is a real field.</b> Rewriting a name the record DOES carry destroys a
 *       parameter that already worked. {@code timeout} is the trap: it is the approval node's
 *       expiry, so it belongs in that node's aliases, and it is http_request's OWN request
 *       timeout, so rewriting it there would silently revert every edited request to the
 *       default.</li>
 * </ul>
 *
 * <p>Both are checked here against {@code Core}'s record components by reflection, so the table
 * cannot drift away from the configs it targets, and a future entry is checked the same way the
 * existing ones were.
 */
@DisplayName("NESTED_CONFIG_ALIASES - every rewrite targets a real config field, and never overwrites one")
class NestedConfigAliasInvariantTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> aliases() {
        try {
            Field f = WorkflowBuilderModifier.class.getDeclaredField("NESTED_CONFIG_ALIASES");
            f.setAccessible(true);
            return (Map<String, Map<String, String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "NESTED_CONFIG_ALIASES moved or was renamed - this guard must be updated with it", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> nestedConfigKeys() {
        try {
            Field f = WorkflowBuilderModifier.class.getDeclaredField("NESTED_CONFIG_KEYS");
            f.setAccessible(true);
            return (Map<String, String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NESTED_CONFIG_KEYS moved or was renamed", e);
        }
    }

    /**
     * Node types whose config record is not simply the CamelCased type plus "Config".
     * Taken from the {@code WorkflowPlanParser.parse*Config} method that actually fills it.
     */
    private static final Map<String, String> IRREGULAR_CONFIG_CLASS = Map.of(
        "download_file", "DownloadConfig"
    );

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> paramAliases() {
        try {
            Field f = com.apimarketplace.orchestrator.service.NodeParamsValidator.class
                .getDeclaredField("PARAM_ALIASES");
            f.setAccessible(true);
            return (Map<String, Map<String, String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("PARAM_ALIASES moved or was renamed", e);
        }
    }

    /** "email_inbox" -> Core.EmailInboxConfig's component names. */
    private static Set<String> configFieldsOf(String nodeType) {
        String simple = IRREGULAR_CONFIG_CLASS.getOrDefault(nodeType,
            Arrays.stream(nodeType.split("_"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining()) + "Config");
        for (Class<?> nested : Core.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simple) && nested.isRecord()) {
                return Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            }
        }
        throw new AssertionError("no Core." + simple + " record for node type '" + nodeType
            + "' - an alias table entry must name a node type whose config is a record, so the "
            + "invariants below can be checked");
    }

    @Test
    @DisplayName("every alias resolves to a field the node's config record actually declares")
    void everyTargetIsARealField() {
        Map<String, Set<String>> bad = new LinkedHashMap<>();

        aliases().forEach((nodeType, map) -> {
            Set<String> fields = configFieldsOf(nodeType);
            Set<String> unknown = map.values().stream()
                .filter(target -> !fields.contains(target))
                .collect(Collectors.toCollection(TreeSet::new));
            if (!unknown.isEmpty()) bad.put(nodeType, unknown);
        });

        assertThat(bad)
            .as("these rewrites move a value to a key the config record does not carry, so the "
                + "edit still silently does nothing - the very defect the table exists to fix")
            .isEmpty();
    }

    @Test
    @DisplayName("no alias overwrites a field the node already has, e.g. timeout on http_request")
    void noAliasShadowsARealField() {
        Map<String, Set<String>> bad = new LinkedHashMap<>();

        aliases().forEach((nodeType, map) -> {
            Set<String> fields = configFieldsOf(nodeType);
            Set<String> shadowing = map.keySet().stream()
                .filter(fields::contains)
                .collect(Collectors.toCollection(TreeSet::new));
            if (!shadowing.isEmpty()) bad.put(nodeType, shadowing);
        });

        assertThat(bad)
            .as("these spellings are the node's OWN parameters; rewriting them destroys a value "
                + "that already worked")
            .isEmpty();
    }

    @Test
    @DisplayName("no alias collides with the node's own nested key, which carries the whole config object")
    void noAliasEqualsTheNestedKey() {
        Map<String, String> nestedKeys = nestedConfigKeys();
        Map<String, String> bad = new LinkedHashMap<>();

        aliases().forEach((nodeType, map) -> {
            String nestedKey = nestedKeys.get(nodeType);
            if (nestedKey != null && map.containsKey(nestedKey)) bad.put(nodeType, nestedKey);
        });

        assertThat(bad)
            .as("modify accepts the whole config under its nested key (params={approval: {...}}); "
                + "an alias with that same name would rewrite it and lose the object")
            .isEmpty();
    }

    @Test
    @DisplayName("every aliased node type is one whose params are actually routed into a nested config")
    void everyAliasedTypeIsNestedConfig() {
        Set<String> notNested = new TreeSet<>(aliases().keySet());
        notNested.removeAll(nestedConfigKeys().keySet());

        assertThat(notNested)
            .as("a type absent from NESTED_CONFIG_KEYS never reaches a nested config, so an alias "
                + "entry for it would be dead weight")
            .isEmpty();
    }

    /**
     * Completeness within the table's declared scope.
     *
     * <p>{@code NodeParamsValidator.PARAM_ALIASES} is what {@code add_node} ACCEPTS. For a node
     * type this table claims to cover, every one of those spellings that is not already a field
     * of the config record has to be rewritten on the edit path too, or the same word is honoured
     * on creation and silently dropped on modification.
     *
     * <p>Scoped to the types the table declares. The same defect on the types it does NOT declare
     * is real and is named in {@code NESTED_CONFIG_ALIASES}'s javadoc, with the reason a
     * mechanical sweep is unsafe; {@link #outOfScopeTypesAreDeclaredNotForgotten()} keeps that
     * list from growing silently.
     */
    @Test
    @DisplayName("regression: within its declared types, every add_node alias is normalized on modify too")
    void everyAcceptedAliasIsNormalizedOnModify() {
        Map<String, Set<String>> missing = new LinkedHashMap<>();

        aliases().forEach((nodeType, modifyAliases) -> {
            Map<String, String> addAliases = paramAliases().get(nodeType);
            if (addAliases == null) return;
            Set<String> fields = configFieldsOf(nodeType);

            Set<String> gap = new TreeSet<>(addAliases.keySet());
            gap.addAll(addAliases.values());
            gap.removeAll(fields);                     // already the storage spelling
            gap.removeAll(modifyAliases.keySet());     // rewritten on the edit path
            if (!gap.isEmpty()) missing.put(nodeType, gap);
        });

        assertThat(missing)
            .as("add_node honours these spellings but modify drops them into a key nothing reads, "
                + "so the edit reports success and changes nothing")
            .isEmpty();
    }

    /**
     * The known debt that this guard can SEE, which is a floor and not a census.
     *
     * <p>These node types declare aliases in {@code PARAM_ALIASES} that {@code modify} still
     * drops. They are left out because renaming their keys is not a pure rename
     * ({@code outputs} on transform/aggregate is an object converted to a list; {@code seconds}
     * on wait is multiplied by 1000; {@code response} on a response node is that node's own
     * nested key), so each needs its creator read and its own test.
     *
     * <p>The list is derived from {@code PARAM_ALIASES}, so a type whose aliases are advertised
     * only through the per-type help (filter and sort offer {@code items} / {@code list} for
     * {@code input}, for instance) is dropped identically and does NOT appear here. Read this
     * assertion as "no NEW type drifted in", never as "these four are all that is left".
     */
    @Test
    @DisplayName("the node types left out of the table are the known ones, and no more")
    void outOfScopeTypesAreDeclaredNotForgotten() {
        Set<String> outOfScope = new TreeSet<>();
        paramAliases().forEach((nodeType, addAliases) -> {
            if (!nestedConfigKeys().containsKey(nodeType)) return;
            if (aliases().containsKey(nodeType)) return;
            outOfScope.add(nodeType);
        });

        assertThat(outOfScope)
            .as("a nested-config type that add_node gives aliases to, but modify does not "
                + "normalize, still silently drops them - add it to NESTED_CONFIG_ALIASES after "
                + "reading its creator, or list it here with the reason it cannot be a rename")
            .containsExactlyInAnyOrder("aggregate", "response", "transform", "wait");
    }

    /**
     * A node-level key captured by an alias is a capability silently removed: after the rewrite
     * the caller can no longer set it on that node ({@code description} on a task node is the
     * live example, which is why task is not in the table).
     */
    @Test
    @DisplayName("no alias captures a node-level key such as label, type or description")
    void noAliasCapturesANodeLevelKey() {
        Set<String> topLevel;
        try {
            Field f = WorkflowBuilderModifier.class.getDeclaredField("TOP_LEVEL_NODE_KEYS");
            f.setAccessible(true);
            topLevel = (Set<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("TOP_LEVEL_NODE_KEYS moved or was renamed", e);
        }

        Map<String, Set<String>> captured = new LinkedHashMap<>();
        aliases().forEach((nodeType, map) -> {
            Set<String> hit = map.keySet().stream()
                .filter(topLevel::contains)
                .collect(Collectors.toCollection(TreeSet::new));
            if (!hit.isEmpty()) captured.put(nodeType, hit);
        });

        assertThat(captured)
            .as("rewriting a node-level key removes the ability to set it through modify")
            .isEmpty();
    }


    /**
     * The two per-type tables must not claim the same key for one node type. The numeric branch
     * runs FIRST, so a key in both would be coerced in place and never renamed, silently skipping
     * the rewrite. It works today only because {@code timeout} is approval's alias but
     * http_request's own field, and vice versa.
     */
    @Test
    @DisplayName("the alias table and the numeric-coercion table never claim the same key for a type")
    void aliasAndNumericTablesAreDisjointPerType() {
        Map<String, Set<String>> numeric;
        try {
            Field f = WorkflowBuilderModifier.class.getDeclaredField("NUMERIC_NESTED_CONFIG_FIELDS");
            f.setAccessible(true);
            numeric = (Map<String, Set<String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NUMERIC_NESTED_CONFIG_FIELDS moved or was renamed", e);
        }

        Map<String, Set<String>> overlap = new LinkedHashMap<>();
        numeric.forEach((nodeType, fields) -> {
            Set<String> both = fields.stream()
                .filter(aliases().getOrDefault(nodeType, Map.of())::containsKey)
                .collect(Collectors.toCollection(TreeSet::new));
            if (!both.isEmpty()) overlap.put(nodeType, both);
        });

        assertThat(overlap)
            .as("a key in both tables is coerced and never renamed, so the rewrite is skipped "
                + "with no signal")
            .isEmpty();
    }

    @Test
    @DisplayName("every numeric field is a real component of its node's config record")
    void numericFieldsAreRealComponents() {
        Map<String, Set<String>> numeric;
        try {
            Field f = WorkflowBuilderModifier.class.getDeclaredField("NUMERIC_NESTED_CONFIG_FIELDS");
            f.setAccessible(true);
            numeric = (Map<String, Set<String>>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NUMERIC_NESTED_CONFIG_FIELDS moved or was renamed", e);
        }

        Map<String, Set<String>> unknown = new LinkedHashMap<>();
        numeric.forEach((nodeType, fields) -> {
            Set<String> missing = fields.stream()
                .filter(f -> !configFieldsOf(nodeType).contains(f))
                .collect(Collectors.toCollection(TreeSet::new));
            if (!missing.isEmpty()) unknown.put(nodeType, missing);
        });

        assertThat(unknown).as("coercing a field the config does not carry is dead weight").isEmpty();
    }

    /**
     * The scrub removes a superseded spelling from the OUTGOING patch, which only sticks because
     * NodeFieldMerger REPLACES these nested configs. For a nested key it merges instead
     * ({@code params}, shared by media/generate/public_link), the removed key would be
     * re-introduced from the node and the scrub would be silently inert.
     */
    @Test
    @DisplayName("no aliased type stores its config under a key NodeFieldMerger merges rather than replaces")
    void aliasedTypesUseAReplacedNestedKey() {
        Set<String> merged;
        try {
            Field f = com.apimarketplace.orchestrator.tools.workflow.builder.NodeFieldMerger.class
                .getDeclaredField("MERGE_MAP_FIELDS");
            f.setAccessible(true);
            merged = (Set<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("MERGE_MAP_FIELDS moved or was renamed", e);
        }

        Set<String> offenders = aliases().keySet().stream()
            .filter(t -> merged.contains(nestedConfigKeys().get(t)))
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(offenders)
            .as("the scrub would be re-merged away for these types, so it would do nothing")
            .isEmpty();
    }
}
