package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code agent:generate} node must not give up on a call catalog is still
 * running and will still charge for.
 *
 * <p>Catalog's own budget for one generation lives in
 * {@code ToolExecutionManager.GENERATION_CALLER_BUDGET_MS}, in a module this
 * one cannot import. The number is therefore READ from that source rather than
 * copied here: a copy agrees with itself while the system drifts, which is the
 * exact failure this test exists to catch. Raising the upstream read ceiling in
 * catalog-service and forgetting this default is how the node ends up
 * abandoning a call it has been billed for, and a hardcoded copy would stay
 * green through it.
 *
 * <p>The same cross-module source read is used by the gateway's
 * {@code BillingContextHeadersTest}; when the sibling module is not reachable
 * (a module built in isolation) the test skips rather than pretending.
 */
class GenerationReadTimeoutDefaultTest {

    private static final Path CATALOG_BUDGET_SOURCE = Path.of(
            "..", "catalog-service", "src", "main", "java", "com", "apimarketplace",
            "catalog", "service", "ToolExecutionManager.java");

    @Test
    @DisplayName("the default read timeout covers catalog's whole caller budget, read from where that budget is defined")
    void defaultCoversTheServerBudget() throws Exception {
        long catalogBudget = readCatalogCallerBudgetMs();

        assertThat(configuredDefaultMs())
                .as("http.client.timeout.generation-read default vs catalog's caller budget")
                .isGreaterThanOrEqualTo(catalogBudget);
    }

    @Test
    @DisplayName("the timeout stays configurable, so an operator can raise it without a rebuild")
    void theTimeoutStaysConfigurable() throws Exception {
        Field field = RestTemplateConfig.class.getDeclaredField("generationReadTimeout");
        assertThat(field.getAnnotation(Value.class)).isNotNull();
    }

    /** The default inside {@code @Value("${...:NNN}")}. */
    private static long configuredDefaultMs() throws NoSuchFieldException {
        Field field = RestTemplateConfig.class.getDeclaredField("generationReadTimeout");
        Value annotation = field.getAnnotation(Value.class);
        assertThat(annotation).as("the timeout must stay configurable").isNotNull();
        String expression = annotation.value();
        return Long.parseLong(
                expression.substring(expression.indexOf(':') + 1, expression.length() - 1));
    }

    /**
     * {@code GENERATION_CALLER_BUDGET_MS} as catalog-service defines it, by
     * evaluating the three literals it is composed of. Parsed rather than
     * imported because the two modules do not depend on each other; parsing
     * fails loudly if the shape changes, which is the point.
     */
    private static long readCatalogCallerBudgetMs() throws IOException {
        assumeTrue(Files.isRegularFile(CATALOG_BUDGET_SOURCE),
                "catalog-service sources not reachable from this module");
        String source = Files.readString(CATALOG_BUDGET_SOURCE);

        long upstreamCeiling = literalAfter(source, "UPSTREAM_READ_CEILING_MS");
        long pollCeiling = readAbsoluteMaxWaitMs();
        long slack = literalAfter(source, "GENERATION_CALLER_BUDGET_MS = GENERATION_WORST_CASE_MS \\+");

        return upstreamCeiling + pollCeiling + slack;
    }

    /** {@code AsyncPollExecutor.ABSOLUTE_MAX_WAIT_MS}, the other half of the worst case. */
    private static long readAbsoluteMaxWaitMs() throws IOException {
        Path pollSource = Path.of("..", "catalog-service", "src", "main", "java", "com",
                "apimarketplace", "catalog", "service", "execution", "AsyncPollExecutor.java");
        assumeTrue(Files.isRegularFile(pollSource), "AsyncPollExecutor not reachable");
        Matcher m = Pattern.compile("ABSOLUTE_MAX_WAIT_MS\\s*=\\s*(\\d+)\\s*\\*\\s*(\\d+)\\s*\\*\\s*(\\d+)")
                .matcher(Files.readString(pollSource));
        assertThat(m.find()).as("ABSOLUTE_MAX_WAIT_MS shape in AsyncPollExecutor").isTrue();
        return Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)) * Long.parseLong(m.group(3));
    }

    private static long literalAfter(String source, String namePattern) {
        Matcher m = Pattern.compile(namePattern + "[^;]*?(\\d[\\d_]*)L").matcher(source);
        assertThat(m.find()).as("literal for %s in ToolExecutionManager", namePattern).isTrue();
        return Long.parseLong(m.group(1).replace("_", ""));
    }
}
