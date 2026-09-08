package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.analytics.ApiCallAnalytics;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The analytics envelope around {@code executeTool}: exactly ONE
 * {@code api_call_completed} emission per execution, on every exit path
 * (success envelope, caught-exception envelope, CE relay, rethrown refusal),
 * and none when the tool does not exist. Regression: before the envelope the
 * catalog API calls made by workflow nodes were invisible to product analytics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolExecutionManagerAnalyticsEnvelopeTest {

    @Mock private ToolContextService toolContextService;
    @Mock private ApiService apiService;
    @Mock private ResponseShaper responseShaper;
    @Mock private NextActionBuilder nextActionBuilder;
    @Mock private ResponseCache responseCache;
    @Mock private com.apimarketplace.catalog.repository.ToolNextHintRepository toolNextHintRepository;
    @Mock private ToolResponseService toolResponseService;
    @Mock private CredentialClient credentialClient;
    @Mock private CeCatalogCloudRelay ceCatalogCloudRelay;
    @Mock private ApiCallAnalytics apiCallAnalytics;
    @Mock private CatalogPlanAccess catalogPlanAccess;

    private ToolExecutionManager manager;
    private ToolContextService.ToolContext context;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        var orchestrator = new com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator(
                new com.apimarketplace.catalog.service.execution.OutputProjector(objectMapper));
        var binary = new com.apimarketplace.catalog.service.execution.BinaryResponseHandler(objectMapper);
        manager = new ToolExecutionManager(toolContextService, apiService, objectMapper, responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService, orchestrator, binary,
                null, credentialClient, null, ceCatalogCloudRelay);
        manager.setApiCallAnalytics(apiCallAnalytics);

        lenient().when(credentialClient.getCredentialStateVersion(anyString()))
                .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        lenient().when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(responseShaper.shape(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(inv.getArgument(0), java.util.List.of(),
                        ResponseShaper.Action.UNTOUCHED, 0, 0));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());

        context = new ToolContextService.ToolContext();
        context.setToolId(UUID.randomUUID().toString());
        context.setApiId(UUID.randomUUID().toString());
        context.setToolName("send_message");
        context.setApiSlug("slack");
        context.setToolSlug("slack-send-message");
        context.setEndpoint("/chat.postMessage");
        context.setHttpMethod("POST");
        context.setAllowedParameterNames(java.util.Set.of("text"));
        when(toolContextService.loadToolContext("slack/send_message")).thenReturn(Optional.of(context));
    }

    private ToolExecutionRequest request() {
        return ToolExecutionRequest.builder().parameters(Map.of("text", "hi")).build();
    }

    @Test
    @DisplayName("success: emitted once with the response, no failure")
    void success() {
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenReturn(Map.of("success", true, "data", Map.of("ok", true), "status", 200));

        ToolExecutionResponse response = manager.executeTool("slack/send_message", request(), "42", "org-1", "req");

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<ToolExecutionResponse> captor = ArgumentCaptor.forClass(ToolExecutionResponse.class);
        verify(apiCallAnalytics).emit(same(context), any(ToolExecutionRequest.class), eq("42"), eq("org-1"),
                captor.capture(), isNull(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(captor.getValue()).isSameAs(response);
    }

    @Test
    @DisplayName("provider exception: the caught-exception envelope is what gets emitted (still one emission)")
    void providerException() {
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenThrow(new RuntimeException("upstream exploded"));

        ToolExecutionResponse response = manager.executeTool("slack/send_message", request(), "42", null, "req");

        assertThat(response.isSuccess()).isFalse();
        verify(apiCallAnalytics).emit(same(context), any(ToolExecutionRequest.class), eq("42"), isNull(),
                same(response), isNull(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("rethrown refusal (plan gate): emitted once with the failure and no response, then rethrown")
    void rethrownRefusal() {
        manager.setCatalogPlanAccess(catalogPlanAccess);
        RuntimeException refusal = new IllegalStateException("PLAN_UPGRADE_REQUIRED");
        org.mockito.Mockito.doThrow(refusal).when(catalogPlanAccess).assertAllowed(any(), any(), any(), any());

        assertThatThrownBy(() -> manager.executeTool("slack/send_message", request(), "42", "org-1", "req"))
                .isSameAs(refusal);

        verify(apiCallAnalytics).emit(same(context), any(ToolExecutionRequest.class), eq("42"), eq("org-1"),
                isNull(), same(refusal), org.mockito.ArgumentMatchers.anyLong());
        verify(apiService, never()).executeApiTool(anyString(), anyString(), any(), anySet(), anyString());
    }

    @Test
    @DisplayName("CE relay: the relayed response is emitted (the relay short-circuits everything else)")
    void relayed() {
        ToolExecutionResponse relayed = ToolExecutionResponse.builder().success(true).build();
        when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any())).thenReturn(Optional.of(relayed));

        ToolExecutionResponse response = manager.executeTool("slack/send_message", request(), "42", null, "req");

        assertThat(response).isSameAs(relayed);
        verify(apiCallAnalytics).emit(same(context), any(ToolExecutionRequest.class), eq("42"), isNull(),
                same(relayed), isNull(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("unknown tool: nothing to attribute, nothing emitted")
    void unknownTool() {
        when(toolContextService.loadToolContext("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> manager.executeTool("nope", request(), "42", null, "req"))
                .isInstanceOf(ToolNotFoundException.class);
        verify(apiCallAnalytics, never()).emit(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("no emitter wired (hand-built manager): execution is unaffected")
    void noEmitter() {
        manager.setApiCallAnalytics(null);
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenReturn(Map.of("success", true, "data", "ok"));
        assertThat(manager.executeTool("slack/send_message", request(), "42", null, "req").isSuccess()).isTrue();
    }
}
