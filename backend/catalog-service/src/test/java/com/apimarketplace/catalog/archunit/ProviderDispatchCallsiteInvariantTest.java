package com.apimarketplace.catalog.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every provider call {@code HttpExecutionService} makes must go through {@code exchangeWithRetry}.
 *
 * <p><b>What this protects.</b> The retry added with {@code errorPolicy} re-sends a request when
 * the provider refuses it, and the guarantee that this can never publish twice lives in one place:
 * {@code exchangeWithRetry} hands the tool's real HTTP method to {@code ErrorPolicyEngine}, which
 * refuses to re-send a state-changing method on a status that does not prove rejection. A new
 * {@code restTemplate.exchange} added straight into this class opts out of that guarantee in
 * silence: it simply never retries, which looks harmless, until someone "fixes" the inconsistency
 * by copying a retry loop without the method gate.
 *
 * <p><b>Why a callsite rule rather than a test.</b> The defect is the ABSENCE of a call, on a
 * dispatch that does not exist yet. No behavioural test can fail for code nobody has written; only
 * a structural rule can. Sibling of {@code ApiCatalogBundleBuildCallsiteInvariantTest} and of
 * {@code orchestrator}'s {@code JsonbWritesCallsiteInvariantTest}, for the same reason: the wrong
 * shape fails silently and late.
 *
 * <p><b>The frozen set is the documented exclusion list.</b> Any entry that is not a tool path is
 * also a row in SCHEMA.md's "Which requests this covers" table. Adding one here means adding it
 * there, and saying why a provider call is exempt from the platform's rate-limit handling.
 */
@DisplayName("Provider dispatch - callsite invariant (every tool call goes through the retry)")
class ProviderDispatchCallsiteInvariantTest {

    private static final String SERVICE =
            "com.apimarketplace.catalog.service.http.HttpExecutionService";

    /**
     * The methods that host a provider dispatch, frozen.
     *
     * <p>The four tool paths do NOT call {@code RestTemplate} directly: they build a
     * {@code Supplier} and hand it to {@code exchangeWithRetry}, which invokes it. Bytecode has no
     * such distinction - a lambda body is attributed to the method that declares it - so this rule
     * cannot tell "dispatches directly" from "passes a supplier". What it CAN do, and what the
     * defect actually needs, is freeze the SET: a new method that reaches for the transport shows
     * up here and has to be justified.
     *
     * <p>{@code lookupSubResourceToken} is the one that genuinely dispatches on its own. It is not
     * a tool call, it has its own fallback, and SCHEMA.md's exclusion table lists it.
     */
    private static final List<String> DISPATCHING_METHODS = List.of(
            "executeBinaryResponse",
            "executeHttpCall",
            "executeHttpCallTyped",
            "executeHttpCallWithCredentials",
            "lookupSubResourceToken");

    /** The tool paths, which must each route their dispatch through the retry. */
    private static final List<String> TOOL_PATHS = List.of(
            "executeBinaryResponse",
            "executeHttpCall",
            "executeHttpCallTyped",
            "executeHttpCallWithCredentials");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.apimarketplace.catalog.service.http");

    private List<String> methodsReachingTheTransport() {
        return classes.stream()
                .filter(c -> c.getName().equals(SERVICE))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().getName()
                        .equals("org.springframework.web.client.RestTemplate"))
                .filter(call -> call.getTarget().getName().startsWith("exchange")
                        || call.getTarget().getName().startsWith("getFor")
                        || call.getTarget().getName().startsWith("postFor"))
                .map(call -> call.getOrigin().getName())
                .distinct()
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("no NEW method reaches the transport without being accounted for, or the "
            + "no-duplicate-publish guarantee silently stops covering it")
    void theSetOfDispatchingMethodsIsFrozen() {
        assertThat(methodsReachingTheTransport())
                .as("A method here sends a provider request. If it is a tool path it must hand its "
                        + "send to exchangeWithRetry(send, url, tool, api), so a 429 is retried and "
                        + "the method gate stops a publish being re-sent on an ambiguous status. If "
                        + "it is genuinely not a tool call, add it to DISPATCHING_METHODS AND to "
                        + "the exclusion table in scripts/api-migrations/SCHEMA.md, with the reason")
                .containsExactlyInAnyOrderElementsOf(DISPATCHING_METHODS);
    }

    @Test
    @DisplayName("every tool path routes its dispatch through exchangeWithRetry")
    void everyToolPathRoutesThroughTheRetry() {
        List<String> routed = classes.stream()
                .filter(c -> c.getName().equals(SERVICE))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTarget().getName().equals("exchangeWithRetry"))
                .map(call -> call.getOrigin().getName())
                .distinct()
                .toList();

        assertThat(routed)
                .as("A tool path that reaches the transport without calling exchangeWithRetry "
                        + "dispatches unprotected: no retry, and no method gate")
                .containsAll(TOOL_PATHS);
    }

    @Test
    @DisplayName("exchangeWithRetry still asks the engine, or nothing decides whether a refusal "
            + "is worth re-sending")
    void theRetryStillConsultsTheEngine() {
        // The companion behavioural test (HttpExecutionServiceRetryTest.theMethodReachesTheEngine)
        // proves the argument is the tool's real method; this proves the call exists at all, so
        // deleting it cannot pass as "no test covers this line".
        List<String> callsToEngine = classes.stream()
                .filter(c -> c.getName().equals(SERVICE))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().getName()
                        .equals("com.apimarketplace.catalog.service.http.ErrorPolicyEngine"))
                .filter(call -> call.getTarget().getName().equals("classify"))
                .map(call -> call.getOrigin().getName())
                .distinct()
                .toList();

        assertThat(callsToEngine).contains("exchangeWithRetry");
    }
}
