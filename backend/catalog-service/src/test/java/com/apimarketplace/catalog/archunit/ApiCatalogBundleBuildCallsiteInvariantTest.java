package com.apimarketplace.catalog.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API-catalog bundle build must never MATERIALISE its payload.
 *
 * <p><b>The incident this guards.</b> {@code ApiCatalogBundleService} used to
 * call {@code ApiCatalogBundlePayload.canonicalBytes(...)} and then
 * {@code gzip(byte[])}. Both hold the whole payload: the full-catalog JSON is
 * hundreds of megabytes, so the build needed the row tree, Jackson's output
 * segments, the final array copy AND a second copy to compress, all resident at
 * once. Once the catalog passed ~19k endpoints that exceeded the cloud catalog
 * pod's 896 MB heap and every {@code POST /api/catalog/bundles} answered HTTP
 * 500 from an {@code OutOfMemoryError}. Nothing surfaced that: the controller
 * does not catch it, so an admin saw only "An unexpected error occurred", and
 * the whole CE fleet silently stopped receiving API-catalog updates for as long
 * as it lasted.
 *
 * <p><b>Why a callsite rule and not a test on the size.</b> The heap failure
 * scales with the catalog, so no fixture reproduces it at a size CI would
 * tolerate, and a heap-bounded test pins a number that stops meaning anything
 * the moment the pod is resized. Measured once, off-CI, on a synthetic catalog
 * of 700 APIs / 32,900 endpoints (66 MiB payload): the materialising form threw
 * {@code OutOfMemoryError} at -Xmx192m, -Xmx128m and -Xmx64m, while the
 * streaming form completed at all three - including a heap SMALLER than the
 * payload.
 *
 * <p>What this rule pins is the half of that which is structural: which method
 * the build calls. The other half - that the method it calls is actually
 * incremental - is pinned by {@code
 * ApiCatalogBundlePayloadTest#writeCanonicalDoesNotMaterialiseThePayload},
 * because a materialising {@code writeCanonical} would satisfy this rule, the
 * golden hash and the round trip all at once while bringing the OOM straight
 * back.
 *
 * <p>The {@code byte[]} forms are deliberately still public: tests use them, and
 * so may a caller that already knows its payload is small. This rule only says
 * the whole-catalog build is not such a caller.
 *
 * <p>Sibling of {@code orchestrator}'s {@code JsonbWritesCallsiteInvariantTest}:
 * same shape, a rule about which call a specific class may make, because the
 * wrong one fails silently and late.
 */
@DisplayName("API catalog bundle build - callsite invariant (must stream, never materialise)")
class ApiCatalogBundleBuildCallsiteInvariantTest {

    private static final String PAYLOAD = "com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload";

    /** The payload-holding entry points the whole-catalog build must not reach for. */
    private static final List<String> MATERIALISING_METHODS = List.of("canonicalBytes", "gzip");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.apimarketplace.catalog.bundle");

    @Test
    @DisplayName("ApiCatalogBundleService never calls canonicalBytes/gzip - it streams via "
            + "writeCanonical, or the build OOMs on the real catalog again")
    void theBuildNeverMaterialisesThePayload() {
        List<String> offenders = classes.stream()
                .filter(c -> c.getName().equals(
                        "com.apimarketplace.catalog.bundle.ApiCatalogBundleService"))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().getName().equals(PAYLOAD))
                .filter(call -> MATERIALISING_METHODS.contains(call.getTarget().getName()))
                .map(call -> call.getOriginOwner().getSimpleName() + "."
                        + call.getOrigin().getName() + " -> "
                        + call.getTarget().getName())
                .sorted()
                .toList();

        assertThat(offenders)
                .as("These callsites hold the whole catalog payload in memory. Use "
                        + "ApiCatalogBundlePayload.writeCanonical(OutputStream, ...) and serialise "
                        + "straight into the GZIP stream instead")
                .isEmpty();
    }

    @Test
    @DisplayName("the streaming entry point still exists and is the one the service uses - a rule "
            + "that passes because nothing calls anything would guard nothing")
    void theServiceDoesCallTheStreamingForm() {
        // Without this, deleting the build entirely would satisfy the rule above.
        boolean streams = classes.stream()
                .filter(c -> c.getName().equals(
                        "com.apimarketplace.catalog.bundle.ApiCatalogBundleService"))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .anyMatch(call -> call.getTargetOwner().getName().equals(PAYLOAD)
                        && call.getTarget().getName().equals("writeCanonical"));

        assertThat(streams)
                .as("ApiCatalogBundleService must build its payload through "
                        + "ApiCatalogBundlePayload.writeCanonical")
                .isTrue();
    }
}
