package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ApiRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolRow;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The cloud side of carrying prices: ask auth-service for the published rates of
 * the GENERATION endpoints in this snapshot, and nothing else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiCatalogGenerationPriceReader - only generation endpoints, only this snapshot")
class ApiCatalogGenerationPriceReaderTest {

    private static final UUID GEN_TOOL = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PLAIN_TOOL = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private CredentialClient credentialClient;

    private ApiCatalogGenerationPriceReader reader;

    @BeforeEach
    void setUp() {
        reader = new ApiCatalogGenerationPriceReader(credentialClient);
        when(credentialClient.fetchPublishedGenerationPrices(any(), any())).thenReturn(List.of());
    }

    private static ToolRow tool(UUID id, String slug, String generationSpec) {
        return new ToolRow(id, slug, "d", null, "POST", "/v1/" + slug, "HTTP", null, null, null,
                null, "async_poll", null, null, generationSpec, null, "ACTIVE", null, true,
                "1.0.0", List.of(), List.of(), List.of());
    }

    private static ApiRow api(String slug, String platformCredentialName, List<ToolRow> tools) {
        return new ApiRow(UUID.randomUUID(), slug, slug, "d", "https://x", null, "Cat", "cat",
                "Sub", "sub", "apikey", null, null, "public", true, true, false, "free",
                "APPROVED", "1.0.0", slug, platformCredentialName, null, null, null, null, null, tools);
    }

    @SuppressWarnings("unchecked")
    private Collection<String>[] captureAsk() {
        ArgumentCaptor<Collection<String>> integrations = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<String>> toolIds = ArgumentCaptor.forClass(Collection.class);
        verify(credentialClient).fetchPublishedGenerationPrices(
                integrations.capture(), toolIds.capture());
        return new Collection[]{integrations.getValue(), toolIds.getValue()};
    }

    @Test
    @DisplayName("Only endpoints carrying a descriptor are asked about - the owner's rates for the "
            + "ordinary catalog are never distributed")
    void asksOnlyAboutGenerationEndpoints() {
        // The 700 ordinary endpoints are the platform owner's own commercial
        // business. Scoping the ASK is what keeps them out of a bundle every
        // install can download.
        var snapshot = new ApiCatalogSnapshotReader.Snapshot(List.of(
                api("seedance", "seedance", List.of(
                        tool(GEN_TOOL, "create-video-task", "{\"kind\":\"video\"}"),
                        tool(PLAIN_TOOL, "get-task", null))),
                api("openweather", "openweather", List.of(
                        tool(UUID.randomUUID(), "current", null)))),
                List.of());

        reader.read(snapshot);

        Collection<String>[] asked = captureAsk();
        assertThat(asked[0]).containsExactly("seedance");
        assertThat(asked[1]).containsExactly(GEN_TOOL.toString());
    }

    @Test
    @DisplayName("A catalog with no generation endpoint asks nothing at all")
    void noGenerationEndpointsMeansNoCall() {
        var snapshot = new ApiCatalogSnapshotReader.Snapshot(List.of(
                api("openweather", "openweather", List.of(tool(PLAIN_TOOL, "current", null)))),
                List.of());

        assertThat(reader.read(snapshot)).isEmpty();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("A generation API with no platform credential name contributes nothing - there is "
            + "no credential for a price to hang off, here or on the install")
    void generationWithoutAPlatformCredentialIsSkipped() {
        var snapshot = new ApiCatalogSnapshotReader.Snapshot(List.of(
                api("seedance", null, List.of(tool(GEN_TOOL, "create-video-task", "{\"kind\":\"video\"}")))),
                List.of());

        assertThat(reader.read(snapshot)).isEmpty();
        verify(credentialClient, never()).fetchPublishedGenerationPrices(any(), any());
    }

    @Test
    @DisplayName("Amounts become plain strings, so the signed bytes never depend on how a decimal "
            + "was scaled or rendered")
    void amountsBecomePlainStrings() {
        when(credentialClient.fetchPublishedGenerationPrices(any(), any())).thenReturn(List.of(
                new BundleGenerationPriceDto("seedance", GEN_TOOL.toString(), "seedance-2.0",
                        "second", new BigDecimal("0.000000"), new BigDecimal("60.000000"),
                        null, null)));
        var snapshot = new ApiCatalogSnapshotReader.Snapshot(List.of(
                api("seedance", "seedance", List.of(
                        tool(GEN_TOOL, "create-video-task", "{\"kind\":\"video\"}")))),
                List.of());

        var rows = reader.read(snapshot);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).unitCredits()).isEqualTo("60.000000");
        // Absent clamps stay absent rather than becoming a zero ceiling, which
        // would cap every generation at nothing.
        assertThat(rows.get(0).minCredits()).isNull();
        assertThat(rows.get(0).maxCredits()).isNull();
    }

    @Test
    @DisplayName("auth-service being unreachable builds a bundle with no price update, never one "
            + "with a wrong price")
    void unreachableAuthYieldsNoPrices() {
        // The client already answers with an empty list on transport failure.
        // The asymmetry is the point: a bundle without prices behaves exactly
        // like every bundle built before this feature, whereas a bundle with a
        // WRONG price would be signed and distributed.
        var snapshot = new ApiCatalogSnapshotReader.Snapshot(List.of(
                api("seedance", "seedance", List.of(
                        tool(GEN_TOOL, "create-video-task", "{\"kind\":\"video\"}")))),
                List.of());

        assertThat(reader.read(snapshot)).isEmpty();
    }
}
