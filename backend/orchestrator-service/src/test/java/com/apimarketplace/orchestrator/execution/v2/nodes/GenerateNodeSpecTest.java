package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 3-way alignment guard for {@code agent:generate}.
 *
 * <p>The persisted output is whatever {@link GenerateNodeSpec} declares (the
 * generic mapper writes exactly the declared keys), the agent reads
 * {@code node_type_documentation.outputs} from V429, and the inspector reads the
 * spec back over {@code /api/node-definitions}. Let the first two drift and the
 * agent writes {@code {{core:x.output.<name>}}} for a key nothing ever stores:
 * that resolves to NOTHING silently and the run still reports COMPLETED, which
 * is the expensive kind of wrong for a node that charges per run.
 */
@DisplayName("GenerateNodeSpec - output contract and V429 alignment")
class GenerateNodeSpecTest {

    private static final Path MIGRATION_DIR = Paths.get(
            "..", "migration-service", "src", "main", "resources", "db", "migration");

    private static final String V429 = "V429__add_generate_node_documentation.sql";

    @Test
    @DisplayName("declares the generation contract: a canonical file plus what ran and what it billed")
    void declaresTheGenerationContract() {
        NodeDefinition def = new GenerateNodeSpec().definition();

        assertEquals("GENERATE", def.nodeType());
        // The AI family, not core: it runs a model and hands back what the model
        // produced, and it is keyed `agent:<label>` like its siblings. The prefix
        // is what every reference resolves through, so a `core:` here would have
        // `{{agent:make_clip.output.file}}` resolve to nothing, silently.
        assertEquals("agent", def.category());
        assertEquals("agent", def.variablePrefix());

        Set<String> keys = outputKeys();
        assertEquals(
            Set.of("file", "model", "kind", "provider", "billed_quantity", "billed_unit", "provider_response"),
            keys);
    }

    @Test
    @DisplayName("`file` is expanded into the canonical FileRef sub-fields so a path can be dragged")
    void fileIsExpandable() {
        OutputFieldDef file = new GenerateNodeSpec().definition().outputs().stream()
                .filter(f -> "file".equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("`file` output is missing"));

        List<String> children = file.children().stream().map(OutputFieldDef::key).toList();
        assertTrue(children.contains("path"), "children: " + children);
        assertTrue(children.contains("mimeType"), "children: " + children);
        assertTrue(children.contains("name"), "children: " + children);
    }

    @Test
    @DisplayName("the provider payload is a separate key, never merged, so it cannot shadow `file`")
    void providerResponseIsItsOwnKey() {
        OutputFieldDef provider = new GenerateNodeSpec().definition().outputs().stream()
                .filter(f -> "provider_response".equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("`provider_response` output is missing"));

        assertEquals("object", provider.type());
    }

    @Test
    @DisplayName("V429 `outputs` key-set == GenerateNodeSpec.outputs() key-set (drift guard)")
    void v424MatchesSpecOutputs() throws IOException {
        Set<String> docKeys = extractTopLevelJsonKeys(outputsBlockFromMigration());
        Set<String> specKeys = outputKeys();

        Set<String> inDocNotSpec = new LinkedHashSet<>(docKeys);
        inDocNotSpec.removeAll(specKeys);
        Set<String> inSpecNotDoc = new LinkedHashSet<>(specKeys);
        inSpecNotDoc.removeAll(docKeys);

        StringBuilder failures = new StringBuilder();
        if (!inDocNotSpec.isEmpty()) {
            failures.append("  - V429 documents ").append(inDocNotSpec)
                    .append(" but the spec does NOT declare them, so nothing persists them: the agent "
                            + "would write templates that resolve to nothing on a COMPLETED run.\n");
        }
        if (!inSpecNotDoc.isEmpty()) {
            failures.append("  - the spec declares ").append(inSpecNotDoc)
                    .append(" but V429 does NOT document them: the inspector shows them and the agent "
                            + "never learns they exist.\n");
        }
        if (failures.length() > 0) {
            fail("V429 <-> GenerateNodeSpec.outputs() drift:\n" + failures);
        }
    }

    @Test
    @DisplayName("V429 documents `model` as the one required parameter")
    void v424DocumentsModelAsRequired() throws IOException {
        String parameters = parametersBlockFromMigration();
        assertTrue(parameters.contains("\"model\""), "V429 must document the model parameter");
        Matcher m = Pattern.compile("\"model\"\\s*:\\s*\\{[^}]*\"required\"\\s*:\\s*true").matcher(parameters);
        assertTrue(m.find(),
            "V429 must mark `model` required: it is what decides the format, the accepted params and "
                + "the price, and the node refuses to run without it");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Set<String> outputKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (OutputFieldDef f : new GenerateNodeSpec().definition().outputs()) {
            keys.add(f.key());
        }
        return keys;
    }

    /** V429 is an INSERT: `parameters` is the first jsonb literal, `outputs` the second. */
    private static String parametersBlockFromMigration() throws IOException {
        return jsonbLiteral(readMigration(), 0);
    }

    private static String outputsBlockFromMigration() throws IOException {
        return jsonbLiteral(readMigration(), 1);
    }

    private static String jsonbLiteral(String sql, int index) {
        int cursor = 0;
        for (int i = 0; i <= index; i++) {
            int start = sql.indexOf("'{", cursor);
            if (start < 0) throw new AssertionError("V429 has fewer than " + (index + 1) + " jsonb literals");
            int end = sql.indexOf("'::jsonb", start);
            if (end < 0) throw new AssertionError("V429 jsonb literal " + i + " is not terminated with '::jsonb");
            if (i == index) {
                return sql.substring(start + 1, end);
            }
            cursor = end + 1;
        }
        throw new AssertionError("unreachable");
    }

    /**
     * Top-level keys of a JSON object string. Depth-tracked so the nested
     * {@code {"type": ..., "description": ...}} values are skipped.
     */
    private static Set<String> extractTopLevelJsonKeys(String json) {
        String normalised = json.replace("''", "'");
        Pattern keyPattern = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:");
        Matcher m = keyPattern.matcher(normalised);
        Set<String> keys = new LinkedHashSet<>();
        int depth = 0;
        int lastEnd = 0;
        while (m.find()) {
            for (int i = lastEnd; i < m.start(); i++) {
                char c = normalised.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            if (depth == 1) {
                keys.add(m.group(1));
            }
            lastEnd = m.end();
        }
        return keys;
    }

    private static String readMigration() throws IOException {
        Path path = MIGRATION_DIR.resolve(V429);
        if (!Files.exists(path)) {
            path = Paths.get("backend", "migration-service", "src", "main",
                    "resources", "db", "migration", V429);
        }
        return Files.readString(path);
    }
}
