package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Every {@code access_pattern} a node creator hands back names the node's own key
 * prefix.
 *
 * <p>{@code access_pattern} is the exact string an LLM agent copies to wire a
 * produced file into the next step. A wrong prefix there does not fail: an
 * unresolved template is an empty string, so the downstream file parameter
 * silently receives nothing and the run reports success.
 *
 * <p><b>Why this exists.</b> Moving generate from {@code core:} to {@code agent:}
 * was done partly by search-and-replace, and it rewrote the access_pattern of
 * three nodes that never moved: download_file, compression and convert_to_file,
 * the first of which is the most-used file producer in the product. Nothing
 * caught it, because no test read any of these strings.
 *
 * <p><b>Two kinds of assertion, deliberately.</b> The four file producers are
 * driven for real, so the claim is about what an agent is actually handed and it
 * survives any reformatting of the source. The source scan that follows is the
 * only way to cover the creators nobody thought to drive, but it asserts a
 * PROPERTY (the prefix is one the platform addresses, and the agent one belongs
 * to generate) rather than a count, so adding a fifth file producer does not
 * break it and cannot be "fixed" by weakening it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("access_pattern always names the node's own key prefix")
class UtilityNodeCreatorAccessPatternPrefixTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
                .sessionId("s").tenantId("t").workflowName("w")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);
        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    private Map<String, Object> params(String label, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", label);
        p.put("connect_after", "Start");
        p.putAll(extra);
        return p;
    }

    /** The access_pattern an agent is handed back, or null when none was advertised. */
    private String accessPatternOf(ToolExecutionResult result) {
        assertThat(result.success()).as("the node must be created: %s", result.error()).isTrue();
        Object data = result.data();
        assertThat(data).isInstanceOf(Map.class);
        Object pattern = ((Map<?, ?>) data).get("access_pattern");
        return pattern == null ? null : pattern.toString();
    }

    @Test
    @DisplayName("a generate node is told to reference itself as agent:, the family it now belongs to")
    void generateAdvertisesTheAgentPrefix() {
        String pattern = accessPatternOf(creator.executeAddGenerate(session,
                params("Make Clip", Map.of("model", "seedance-2.0-fast"))));

        assertThat(pattern)
                .as("the file this node produces is read as {{agent:make_clip.output.file}}")
                .startsWith("{{agent:make_clip.output.file}}");
    }

    @Test
    @DisplayName("download_file still says core:, and it is where most workflows get their bytes")
    void downloadFileKeepsTheCorePrefix() {
        String pattern = accessPatternOf(creator.executeAddDownloadFile(session,
                params("Fetch Clip", Map.of("url", "https://example.test/a.mp4"))));

        assertThat(pattern).isEqualTo("{{core:fetch_clip.output.file}}");
    }

    @Test
    @DisplayName("compression still says core:")
    void compressionKeepsTheCorePrefix() {
        String pattern = accessPatternOf(creator.executeAddCompression(session,
                params("Zip It", Map.of("operation", "compress",
                        "input", "{{core:fetch_clip.output.file}}"))));

        assertThat(pattern).isEqualTo("{{core:zip_it.output.file}}");
    }

    @Test
    @DisplayName("convert_to_file still says core:")
    void convertToFileKeepsTheCorePrefix() {
        String pattern = accessPatternOf(creator.executeAddConvertToFile(session,
                params("Export Rows", Map.of("format", "csv",
                        "input", "{{trigger:start.output.items}}"))));

        assertThat(pattern).isEqualTo("{{core:export_rows.output.file}}");
    }

    @Test
    @DisplayName("the node-type table agrees with what each creator advertises")
    void theNodeTypeTableAgrees() {
        // The prefix a creator ADVERTISES and the prefix the node is BUILT under
        // come from two places. They have to agree, or the agent is told to
        // reference a key the node does not have.
        assertThat(CreatorBase.NodeType.GENERATE.getPrefix()).isEqualTo("agent");
        assertThat(CreatorBase.NodeType.DOWNLOAD_FILE.getPrefix()).isEqualTo("core");
        assertThat(CreatorBase.NodeType.COMPRESSION.getPrefix()).isEqualTo("core");
        assertThat(CreatorBase.NodeType.CONVERT_TO_FILE.getPrefix()).isEqualTo("core");
    }

    // ------------------------------------------------------------------
    // The repo-wide half: the creators nobody drove above.
    // ------------------------------------------------------------------

    private static final Path SOURCE = Path.of(
            "src/main/java/com/apimarketplace/orchestrator/tools/workflow/builder/creators/"
                    + "UtilityNodeCreator.java");

    /** Captures the prefix of `"{{<prefix>:" + normalizedLabel` in an access_pattern line. */
    private static final Pattern ACCESS_PATTERN_PREFIX =
            Pattern.compile("\"access_pattern\",\\s*\"\\{\\{([a-z_]+):\"");

    private String source() throws Exception {
        assertThat(SOURCE).as("the creator source must be readable from the module root").exists();
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every creator that advertises one uses a prefix the platform actually addresses")
    void everyAccessPatternUsesARealPrefix() throws Exception {
        Matcher matcher = ACCESS_PATTERN_PREFIX.matcher(source());
        List<String> prefixes = matcher.results().map(r -> r.group(1)).toList();

        assertThat(prefixes)
                .as("no creator advertises an access_pattern any more, which would make this guard vacuous")
                .isNotEmpty();
        assertThat(prefixes)
                .as("a prefix outside this set addresses no node at all, and resolves to an empty string")
                .allSatisfy(prefix -> assertThat(prefix)
                        .isIn("trigger", "mcp", "agent", "core", "table", "interface"));
    }

    @Test
    @DisplayName("the only agent-prefixed access_pattern in the file is the generated asset's")
    void theAgentPrefixBelongsToGenerateAlone() throws Exception {
        String source = source();
        long agentCount = ACCESS_PATTERN_PREFIX.matcher(source).results()
                .filter(r -> "agent".equals(r.group(1))).count();

        // A property, not a headcount: exactly one node in this file is keyed
        // agent:, so a second agent-prefixed pattern means a rewrite reached a
        // node that never moved. Adding a fifth CORE file producer changes
        // nothing here, which is what keeps the guard worth having.
        assertThat(agentCount)
                .as("a second agent: pattern means a prefix rewrite caught a node that never "
                        + "moved, and that node's file reference now resolves to nothing")
                .isEqualTo(1);
        assertThat(source)
                .as("and the one that is there must be the generated asset's")
                .contains("\"{{agent:\" + normalizedLabel + \".output.file}} (the generated asset, ");
    }
}
