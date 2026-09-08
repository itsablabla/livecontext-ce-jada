package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ApiRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.CredentialTemplateRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ParameterRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ResponseRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolCredentialRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canonical-bytes contract for the API-catalog bundle payload: the signer
 * signs these bytes and CE verifies the exact same bytes, so two builds from
 * the same logical snapshot MUST be byte-identical - regardless of the order
 * the DB returned rows in.
 */
@DisplayName("ApiCatalogBundlePayload - canonical determinism + gzip")
class ApiCatalogBundlePayloadTest {

    private static final Instant SNAPSHOT_AT = Instant.parse("2026-06-10T12:00:00Z");

    private static ApiRow api(UUID id, String name, List<ToolRow> tools) {
        return new ApiRow(id, name, name.toLowerCase(), "desc " + name, "https://api.example",
                null, "Communication", "communication", "Email", "email",
                "oauth2", null, null, "public", true, true, false,
                "free", "APPROVED", "1.0.0", name.toLowerCase(), name.toLowerCase() + "_cred",
                null, null, null, "{\"per_minute\":60}", null, tools);
    }

    private static ToolRow tool(UUID id, String slug,
                                List<ParameterRow> params,
                                List<ResponseRow> responses,
                                List<ToolCredentialRow> creds) {
        return tool(id, slug, params, responses, creds, null);
    }

    /** @param generationSpec the descriptor, or null for an ordinary endpoint. */
    private static ToolRow tool(UUID id, String slug,
                                List<ParameterRow> params,
                                List<ResponseRow> responses,
                                List<ToolCredentialRow> creds,
                                String generationSpec) {
        return new ToolRow(id, slug, "does " + slug, null, "GET", "/v1/" + slug, "HTTP",
                null, null, "{\"mode\":\"http\"}", "[{\"name\":\"x\"}]", "http", null,
                null, generationSpec, null, "ACTIVE", null, true, "1.0.0", params, responses, creds);
    }

    @Test
    @DisplayName("the generation descriptor is part of the payload, or an install fed by the bundle "
            + "cannot see a single generation")
    void generationSpecIsCarriedInThePayload() {
        // This payload is the ONLY way an install that does not run the importer
        // receives the catalog. A descriptor missing here lands as NULL, so the
        // registry finds nothing, no model resolves, and every fail-closed guard
        // that asks "is this a generation" answers no.
        String descriptor = "{\"kind\":\"video\",\"models\":[{\"id\":\"seedance-2.0\"}]}";
        List<ApiRow> apis = List.of(api(UUID.randomUUID(), "Seedance", List.of(
                tool(UUID.randomUUID(), "create-video-task", List.of(), List.of(), List.of(),
                        descriptor))));

        String payload = new String(ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates()), StandardCharsets.UTF_8);

        assertThat(payload).contains("generationSpec");
        assertThat(payload).contains("seedance-2.0");
    }

    @Test
    @DisplayName("the error policy is part of the payload, or a bundle-fed install gets none of "
            + "the rules its APIs declare")
    void errorPolicyIsCarriedInThePayload() {
        // Same reasoning as the descriptor above, and the same silent failure: the emit line is
        // the ONLY thing that puts this column into the bundle. Dropped, the merge writes NULL on
        // every self-hosted install and the round-trip byte comparison still matches, because both
        // sides are then equally empty.
        String policy = "[{\"match\":{\"bodyContains\":\"spam_risk\"},"
                + "\"action\":\"user_error\",\"message\":\"Slow down.\"}]";
        ApiRow withPolicy = new ApiRow(UUID.randomUUID(), "TikTok", "tiktok", "desc", "https://api.example",
                null, "Social", "social", "Video", "video",
                "oauth2", null, null, "public", true, true, false,
                "free", "APPROVED", "1.0.0", "tiktok", "tiktok_cred",
                null, null, null, "{\"per_minute\":60}", policy, List.of());

        String payload = new String(ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, List.of(withPolicy), sampleTemplates()),
                StandardCharsets.UTF_8);

        assertThat(payload).contains("errorPolicy");
        assertThat(payload).contains("spam_risk");
    }

    @Test
    @DisplayName("an API without one adds no key, so the canonical bytes of every other API "
            + "are unchanged")
    void absentErrorPolicyAddsNothingToThePayload() {
        // putIfNotNull is what keeps the bundle bytes identical for the ~970 APIs that declare
        // no policy: an empty key on each of them would change every hash for nothing.
        String payload = new String(ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT,
                List.of(api(UUID.randomUUID(), "OpenWeather", List.of())), sampleTemplates()),
                StandardCharsets.UTF_8);

        assertThat(payload).doesNotContain("errorPolicy");
    }

    @Test
    @DisplayName("and an ordinary endpoint carries none, so nothing else becomes a generation")
    void ordinaryToolCarriesNoDescriptor() {
        List<ApiRow> apis = List.of(api(UUID.randomUUID(), "OpenWeather", List.of(
                tool(UUID.randomUUID(), "current-weather", List.of(), List.of(), List.of()))));

        String payload = new String(ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates()), StandardCharsets.UTF_8);

        assertThat(payload).doesNotContain("generationSpec");
    }

    private static ParameterRow param(UUID id, String name) {
        return new ParameterRow(id, "query", name, "string", true, "d", "ex", null, null,
                null, null, false);
    }

    @Test
    @DisplayName("Same logical input produces byte-identical output across calls")
    void deterministicAcrossCalls() {
        List<ApiRow> apis = sampleApis();
        byte[] first = ApiCatalogBundlePayload.canonicalBytes(7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates());
        byte[] second = ApiCatalogBundlePayload.canonicalBytes(7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates());
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Input list order does not change the bytes (apis, tools, params, templates all sorted)")
    void orderIndependence() {
        List<ApiRow> apis = sampleApis();
        byte[] reference = ApiCatalogBundlePayload.canonicalBytes(7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates());

        // Shuffle every level of the input.
        List<ApiRow> shuffledApis = new ArrayList<>();
        for (ApiRow a : apis) {
            List<ToolRow> shuffledTools = new ArrayList<>();
            for (ToolRow t : a.tools()) {
                List<ParameterRow> p = new ArrayList<>(t.parameters());
                Collections.shuffle(p, new Random(42));
                List<ToolCredentialRow> c = new ArrayList<>(t.toolCredentials());
                Collections.shuffle(c, new Random(42));
                shuffledTools.add(new ToolRow(t.id(), t.toolSlug(), t.description(), t.toolNameId(),
                        t.method(), t.endpoint(), t.protocol(), t.defaultHeaders(), t.runtimeMetadata(),
                        t.executionSpec(), t.outputSchema(), t.executionMode(), t.pagination(),
                        t.requiredScopes(), t.generationSpec(), t.nextHint(), t.status(),
                        t.testStatus(), t.isActive(), t.version(), p, t.responses(), c));
            }
            Collections.shuffle(shuffledTools, new Random(42));
            shuffledApis.add(new ApiRow(a.id(), a.apiName(), a.apiSlug(), a.description(), a.baseUrl(),
                    a.healthcheckEndpoint(), a.categoryName(), a.categorySlug(), a.subcategoryName(),
                    a.subcategorySlug(), a.authType(), a.authHeaderName(), a.authHeaderValue(),
                    a.visibility(), a.isPublic(), a.isActive(), a.isLocal(), a.pricingModel(),
                    a.status(), a.version(), a.iconSlug(), a.platformCredentialName(), a.iconUrl(),
                    a.apiVersion(), a.documentation(), a.rateLimits(), a.errorPolicy(), shuffledTools));
        }
        Collections.shuffle(shuffledApis, new Random(42));
        List<CredentialTemplateRow> shuffledTemplates = new ArrayList<>(sampleTemplates());
        Collections.shuffle(shuffledTemplates, new Random(42));

        byte[] shuffled = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, shuffledApis, shuffledTemplates);

        assertThat(shuffled).isEqualTo(reference);
    }

    @Test
    @DisplayName("Null fields are omitted entirely from the JSON")
    void nullFieldsOmitted() throws Exception {
        UUID apiId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ApiRow bare = new ApiRow(apiId, "Bare", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, List.of());

        byte[] bytes = ApiCatalogBundlePayload.canonicalBytes(1L, 1, "cloud", SNAPSHOT_AT,
                List.of(bare), List.of());

        JsonNode apiNode = new ObjectMapper().readTree(bytes).get("apis").get(0);
        assertThat(apiNode.has("apiSlug")).isFalse();
        assertThat(apiNode.has("baseUrl")).isFalse();
        assertThat(apiNode.has("category")).isFalse();
        assertThat(apiNode.has("iconSlug")).isFalse();
        assertThat(apiNode.get("id").asText()).isEqualTo(apiId.toString());
        // tools is always present so the applier can iterate unconditionally.
        assertThat(apiNode.get("tools").isArray()).isTrue();
    }

    @Test
    @DisplayName("Top-level keys are sorted and carry version/schemaVersion/issuer/snapshotAt")
    void topLevelShape() throws Exception {
        byte[] bytes = ApiCatalogBundlePayload.canonicalBytes(99L, 2, "test-cloud", SNAPSHOT_AT,
                sampleApis(), sampleTemplates());
        JsonNode root = new ObjectMapper().readTree(bytes);

        assertThat(root.get("version").asLong()).isEqualTo(99L);
        assertThat(root.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(root.get("issuer").asText()).isEqualTo("test-cloud");
        assertThat(root.get("snapshotAt").asText()).isEqualTo("2026-06-10T12:00:00Z");
        assertThat(root.get("credentialTemplates").isArray()).isTrue();

        // Alphabetical key order at the top level - the determinism contract.
        List<String> keys = new ArrayList<>();
        Iterator<String> it = root.fieldNames();
        it.forEachRemaining(keys::add);
        assertThat(keys).isSorted();
    }

    @Test
    @DisplayName("Rejects null issuer/snapshotAt/apis")
    void rejectsNullInputs() {
        assertThatThrownBy(() -> ApiCatalogBundlePayload.canonicalBytes(
                1L, 1, null, SNAPSHOT_AT, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiCatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiCatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", SNAPSHOT_AT, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("gzip → gunzip round-trips arbitrary payload bytes")
    void gzipRoundTrip() {
        byte[] raw = ApiCatalogBundlePayload.canonicalBytes(5L, 1, "cloud", SNAPSHOT_AT,
                sampleApis(), sampleTemplates());

        byte[] gz = ApiCatalogBundlePayload.gzip(raw);
        assertThat(gz).isNotEqualTo(raw);
        assertThat(ApiCatalogBundlePayload.gunzip(gz)).isEqualTo(raw);
    }

    @Test
    @DisplayName("gzip actually compresses a repetitive catalog payload")
    void gzipCompresses() {
        // Real catalogs repeat key names hundreds of times - gzip must shrink them.
        String repetitive = "{\"description\":\"the same words over and over\"}".repeat(200);
        byte[] raw = repetitive.getBytes(StandardCharsets.UTF_8);
        assertThat(ApiCatalogBundlePayload.gzip(raw).length).isLessThan(raw.length / 4);
    }

    @Test
    @DisplayName("gunzip on non-gzip bytes throws (caught upstream as APPLY_FAILED)")
    void gunzipRejectsGarbage() {
        assertThatThrownBy(() -> ApiCatalogBundlePayload.gunzip(new byte[]{1, 2, 3}))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    // ── V430: the published generation price rides with the descriptor ───────

    private static ApiCatalogBundlePayload.GenerationPriceRow price(
            String integration, String toolId, String modelId, String unit, String perUnit) {
        return new ApiCatalogBundlePayload.GenerationPriceRow(
                integration, toolId, modelId, unit, "0", perUnit, null, null);
    }

    @Test
    @DisplayName("the published price travels in the SIGNED payload, so an install cannot keep the "
            + "descriptor and substitute a rate of its own")
    void generationPriceIsCarriedInTheSignedPayload() {
        // The descriptor alone makes a model visible and unsellable: an unpriced
        // generation is refused by design. These bytes are what the Ed25519
        // signature covers, so carrying the price here is what makes it both
        // deliverable and unforgeable.
        String toolId = "33333333-3333-3333-3333-333333333333";

        String payload = new String(ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(),
                List.of(price("seedance", toolId, "seedance-2.0", "second", "60"))),
                StandardCharsets.UTF_8);

        assertThat(payload).contains("generationPrices");
        assertThat(payload).contains("seedance-2.0");
        // Amounts are plain STRINGS: these bytes are signed and compared for
        // equality on the far side, so a rate must not round-trip through a
        // double and "60" must not become 60.0.
        assertThat(payload).contains("\"unitCredits\":\"60\"");
    }

    @Test
    @DisplayName("a catalog with no published price is byte-identical to the pre-V430 payload, "
            + "so the addition is invisible to a cloud that has nothing to carry")
    void noPricesMeansTheKeyIsAbsentEntirely() {
        byte[] withoutArgument = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates());
        byte[] withEmptyList = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(), List.of());
        byte[] withNull = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(), null);

        assertThat(withEmptyList).isEqualTo(withoutArgument);
        assertThat(withNull).isEqualTo(withoutArgument);
        assertThat(new String(withoutArgument, StandardCharsets.UTF_8))
                .doesNotContain("generationPrices");
    }

    @Test
    @DisplayName("price row order does not change the bytes - the signer depends on it")
    void generationPriceOrderIndependence() {
        String toolA = "33333333-3333-3333-3333-333333333333";
        String toolB = "44444444-4444-4444-4444-444444444444";
        List<ApiCatalogBundlePayload.GenerationPriceRow> ordered = List.of(
                price("seedance", toolA, "seedance-2.0", "second", "60"),
                price("seedance", toolA, "seedance-2.0-fast", "second", "30"),
                price("elevenlabs", toolB, null, "character", "2"));
        List<ApiCatalogBundlePayload.GenerationPriceRow> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new Random(42));

        assertThat(ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(), shuffled))
                .isEqualTo(ApiCatalogBundlePayload.canonicalBytes(
                        7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(), ordered));
    }

    @Test
    @DisplayName("an endpoint-wide price omits modelId rather than writing null")
    void endpointWidePriceOmitsTheModel() throws Exception {
        byte[] bytes = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(),
                List.of(price("elevenlabs", "44444444-4444-4444-4444-444444444444",
                        null, "character", "2")));

        JsonNode row = new ObjectMapper().readTree(bytes).get("generationPrices").get(0);
        assertThat(row.has("modelId")).isFalse();
        assertThat(row.get("integrationName").asText()).isEqualTo("elevenlabs");
        assertThat(row.get("priceUnit").asText()).isEqualTo("character");
    }

    /**
     * Digest of the canonical bytes of {@link #goldenSnapshot()}, recorded from
     * the implementation that built the whole payload as one {@code Map} tree and
     * called {@code writeValueAsBytes}. Streaming replaced that because the tree
     * form exhausted the catalog pod's heap, and this hash is the proof the
     * replacement did not move a single byte.
     */
    private static final String GOLDEN_SHA256 =
            "20a3e52b6edcf256f9c55a22186ab6e602958dd70df4819da590e69235f61e00";

    private static List<ApiCatalogBundlePayload.GenerationPriceRow> goldenPrices() {
        return List.of(
                price("seedance", "33333333-3333-3333-3333-333333333333",
                        "seedance-2.0", "second", "60"),
                price("elevenlabs", "44444444-4444-4444-4444-444444444444",
                        null, "character", "2"));
    }

    private static byte[] goldenSnapshot() {
        return ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(), goldenPrices());
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    @DisplayName("the canonical bytes still hash to the value the TREE implementation produced - "
            + "streaming must not move one byte, or every published bundle stops verifying")
    void goldenCanonicalHashIsUnchangedByStreaming() throws Exception {
        // Recorded BEFORE the streaming rewrite. CE verifies an Ed25519 signature
        // over these bytes, so a canonicalisation change is not a refactor: it
        // silently invalidates bundles already in the fleet's hands. Any edit that
        // reaches this hash has to be a deliberate, versioned format change.
        assertThat(sha256(goldenSnapshot())).isEqualTo(GOLDEN_SHA256);
    }

    @Test
    @DisplayName("writeCanonical streams exactly the bytes canonicalBytes returns, and reports "
            + "their count")
    void writeCanonicalMatchesTheByteArrayForm() {
        // The production build never calls canonicalBytes - it streams - so the
        // two forms agreeing is what lets every other test here speak for it.
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        long reported = ApiCatalogBundlePayload.writeCanonical(streamed, 7L, 1, "cloud",
                SNAPSHOT_AT, sampleApis(), sampleTemplates(), goldenPrices());

        assertThat(streamed.toByteArray()).isEqualTo(goldenSnapshot());
        assertThat(reported).isEqualTo(goldenSnapshot().length);
    }

    @Test
    @DisplayName("writeCanonical leaves the caller's stream OPEN, so a gzip wrapper can still be "
            + "given its trailer")
    void writeCanonicalDoesNotCloseTheCallersStream() throws Exception {
        // Jackson closes its target by default. Left enabled, the generator would close the
        // GZIPOutputStream the build wraps around it, and nothing after this call could write to
        // it. Today's composition survives that by luck - DeflaterOutputStream.close() is guarded
        // by a `closed` flag, so the caller's close is a harmless no-op - which is exactly why the
        // contract needs its own test: it holds for the ONE caller that exists, and the next
        // caller to write anything after the payload (a wrapper's trailer, a second document)
        // would find the stream shut, with the byte-level symptom appearing only in production.
        class CloseSpy extends java.io.ByteArrayOutputStream {
            boolean closed;

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }
        CloseSpy spy = new CloseSpy();

        ApiCatalogBundlePayload.writeCanonical(spy, 7L, 1, "cloud", SNAPSHOT_AT,
                sampleApis(), sampleTemplates(), List.of());

        assertThat(spy.closed).isFalse();
        // And still writable: the gzip trailer is written after this returns.
        spy.write('!');
    }

    @Test
    @DisplayName("streaming straight into gzip gunzips back to the canonical bytes - the shape the "
            + "build actually ships")
    void streamedGzipRoundTripsToTheCanonicalBytes() throws Exception {
        // This is the exact composition attemptBuild uses. It is asserted here
        // because the signature covers the GZIP bytes: if the composition lost the
        // tail of the payload, the bundle would still be signed, still verify, and
        // deliver a truncated catalog.
        ByteArrayOutputStream gzBuffer = new ByteArrayOutputStream();
        long reported;
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(gzBuffer)) {
            reported = ApiCatalogBundlePayload.writeCanonical(gz, 7L, 1, "cloud", SNAPSHOT_AT,
                    sampleApis(), sampleTemplates(), goldenPrices());
        }

        byte[] recovered = ApiCatalogBundlePayload.gunzip(gzBuffer.toByteArray());
        assertThat(recovered).isEqualTo(goldenSnapshot());
        assertThat(reported).isEqualTo(recovered.length);
    }

    @Test
    @DisplayName("writeCanonical hands the payload over INCREMENTALLY - no single write carries the "
            + "whole thing, which is the entire point of the method")
    void writeCanonicalDoesNotMaterialiseThePayload() {
        // The property this method exists for, and the one nothing else here can see: an
        // implementation that builds the payload into a byte[] and hands it over in one write is
        // byte-identical, satisfies the golden hash, satisfies the round trip, and satisfies the
        // callsite rule - while restoring the OutOfMemoryError the change was made to end. The
        // callsite rule guards WHICH method the build calls; only this guards what that method does.
        //
        // The fixture has to exceed one generator buffer (8000 bytes) or a streaming implementation
        // would legitimately write once too.
        List<ApiRow> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(api(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i)),
                    "Api" + i, List.of(tool(
                            UUID.fromString(String.format("11111111-0000-0000-0000-%012d", i)),
                            "endpoint-" + i, List.of(), List.of(), List.of()))));
        }

        final List<Integer> writes = new ArrayList<>();
        OutputStream recorder = new OutputStream() {
            @Override public void write(int b) { writes.add(1); }
            @Override public void write(byte[] b, int off, int len) { writes.add(len); }
        };

        long total = ApiCatalogBundlePayload.writeCanonical(
                recorder, 7L, 1, "cloud", SNAPSHOT_AT, many, sampleTemplates(), List.of());

        assertThat(total).isGreaterThan(8000L);
        assertThat(writes).as("a single write means the payload was materialised first").hasSizeGreaterThan(1);
        assertThat(writes.stream().mapToInt(Integer::intValue).max().orElse(0))
                .as("no single write may carry the whole payload")
                .isLessThan((int) total);
        // And the pieces still add up to the reported size, so incremental does not mean lossy.
        assertThat(writes.stream().mapToLong(Integer::longValue).sum()).isEqualTo(total);
    }

    @Test
    @DisplayName("a stream that fails mid-payload raises UncheckedIOException instead of returning "
            + "a short count that would be signed as a complete catalog")
    void writeCanonicalPropagatesAStreamFailure() {
        // The disaster this prevents: swallow the IOException and the caller gets a valid gzip of a
        // TRUNCATED JSON, signs it, CE verifies it happily, and the install applies an amputated
        // catalog. Nothing on either side reports anything.
        OutputStream failing = new OutputStream() {
            private int written;

            @Override public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > 4000) throw new IOException("disk went away");
            }
        };

        List<ApiRow> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(api(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i)),
                    "Api" + i, List.of()));
        }

        assertThatThrownBy(() -> ApiCatalogBundlePayload.writeCanonical(
                failing, 7L, 1, "cloud", SNAPSHOT_AT, many, sampleTemplates(), List.of()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("canonical API catalog payload")
                .hasRootCauseMessage("disk went away");
    }

    @Test
    @DisplayName("the byte counter counts single-byte writes too, or a reported size could be short")
    void theCounterCountsBothWriteForms() throws Exception {
        // Jackson's UTF-8 generator only ever calls the array form, so this branch cannot be
        // reached through writeCanonical - and deleting it changed no test at all. It is kept
        // because FilterOutputStream's inherited single-byte write forwards WITHOUT counting, so
        // dropping the override would silently under-report for any future caller. Tested directly,
        // since that is the only way this can fail visibly.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Class<?> counterClass = Class.forName(
                "com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload$CountingOutputStream");
        var ctor = counterClass.getDeclaredConstructor(OutputStream.class);
        ctor.setAccessible(true);
        OutputStream counter = (OutputStream) ctor.newInstance(sink);
        var written = counterClass.getDeclaredMethod("written");
        written.setAccessible(true);

        counter.write('a');
        counter.write(new byte[]{'b', 'c'}, 0, 2);
        counter.write('d');

        assertThat(written.invoke(counter)).isEqualTo(4L);
        assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("abcd");
    }

    @Test
    @DisplayName("writeCanonical rejects a null stream rather than counting bytes into nothing")
    void writeCanonicalRejectsANullStream() {
        assertThatThrownBy(() -> ApiCatalogBundlePayload.writeCanonical(
                null, 7L, 1, "cloud", SNAPSHOT_AT, sampleApis(), sampleTemplates(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output stream");
    }

    private static List<ApiRow> sampleApis() {
        UUID api1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID api2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID tool1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID tool2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID p1 = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID p2 = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID r1 = UUID.fromString("77777777-7777-7777-7777-777777777777");

        ToolRow t1 = tool(tool1, "send-message",
                List.of(param(p1, "to"), param(p2, "body")),
                List.of(new ResponseRow(r1, "ok", null, null, "{}", "{}", null, 200, true, "json", true)),
                List.of(new ToolCredentialRow("slack", "oauth2", true, "authentication", null,
                        "{\"field\":\"access_token\"}")));
        ToolRow t2 = tool(tool2, "list-channels", List.of(), List.of(), List.of());

        return List.of(api(api1, "Slack", List.of(t1, t2)), api(api2, "Gmail", List.of()));
    }

    private static List<CredentialTemplateRow> sampleTemplates() {
        return List.of(
                new CredentialTemplateRow("slack", "oauth2", "Slack", null, "oauth", "oauth2",
                        null, null, null, "slack", "{\"client_id\":{}}", "{}", "{}"),
                new CredentialTemplateRow("gmail", "api_key", "Gmail", null, "key", "apikey",
                        null, null, null, "gmail", "{}", "{}", "{}"));
    }
}
