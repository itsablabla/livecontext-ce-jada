package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentMemoryEntity;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemorySource;
import com.apimarketplace.agent.memory.MemoryLimitsConfig;
import com.apimarketplace.agent.memory.MemoryService;
import com.apimarketplace.agent.repository.AgentMemoryRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REST surface is what a person uses to correct what the agents wrote, so it
 * is also the surface where a mistake is visible to a person rather than to a
 * model. These tests pin the two properties that are security-relevant rather
 * than cosmetic: an out-of-scope row is a 404 and never a 403, and the caller
 * cannot name an agent it has no business naming.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("MemoryController")
class MemoryControllerTest {

    private static final String TENANT = "user-7";
    private static final String ORG = "org-42";

    @Mock private MemoryService memoryService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;
    @Mock private HttpServletRequest request;

    private MemoryLimitsConfig limits;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        limits = new MemoryLimitsConfig();
        controller = new MemoryController(memoryService, tenantResolver,
            new RequestParameterExtractor(), agentRepository, limits);
        lenient().when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT);
        lenient().when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
        lenient().when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
    }

    private static AgentMemoryEntity entity() {
        AgentMemoryEntity e = new AgentMemoryEntity();
        e.setId(UUID.randomUUID());
        e.setSlug("release-cadence");
        e.setTitle("Release cadence");
        e.setSummary("Ships Thursdays.");
        e.setContent("");
        e.setType(AgentMemoryEntity.MemoryType.PROJECT);
        e.setSource(MemorySource.USER);
        e.setTags(List.of());
        e.setPinned(false);
        e.setIsActive(true);
        e.setRecallCount(0);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static Map<String, Object> body() {
        return new java.util.HashMap<>(Map.of("title", "Release cadence", "summary", "Ships Thursdays."));
    }

    @Nested
    @DisplayName("workspace boundary")
    class WorkspaceBoundary {

        @Test
        @DisplayName("reports a row from another workspace as 404, never 403, so its existence is not confirmed")
        void outOfScopeReadIs404() {
            UUID id = UUID.randomUUID();
            when(memoryService.findInScope(id, ORG)).thenReturn(Optional.empty());

            assertThat(controller.get(request, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("reports an update of an out-of-scope row as 404")
        void outOfScopeUpdateIs404() {
            UUID id = UUID.randomUUID();
            when(memoryService.update(any(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MemoryService.MemoryNotFoundException("Memory not found: " + id));

            assertThat(controller.update(request, id, body()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("reports a delete of an out-of-scope row as 404")
        void outOfScopeDeleteIs404() {
            UUID id = UUID.randomUUID();
            org.mockito.Mockito.doThrow(new MemoryService.MemoryNotFoundException("Memory not found: " + id))
                .when(memoryService).delete(any(), anyString(), anyString());

            assertThat(controller.delete(request, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("distinguishes a bad request from a missing row, instead of folding both into 404")
        void validationFailureIs400NotFound() {
            UUID id = UUID.randomUUID();
            when(memoryService.update(any(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MemoryService.MemoryValidationException("summary is 900 characters; the maximum is 240."));

            ResponseEntity<?> response = controller.update(request, id, body());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("the maximum is 240");
        }
    }

    @Nested
    @DisplayName("role gate")
    class RoleGate {

        // One test per surface. Bundled into one, the first assertion to fail hid the
        // other two, so a regression on delete looked identical to a regression on
        // create - and the three are refused by three different code paths.

        @Test
        @DisplayName("returns 403 to a VIEWER creating a memory")
        void viewerCannotCreate() {
            when(memoryService.create(any(), any()))
                .thenThrow(new MemoryService.MemoryWriteForbiddenException("read-only"));

            assertThat(controller.create(request, body()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("returns 403 to a VIEWER editing a memory")
        void viewerCannotUpdate() {
            when(memoryService.update(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MemoryService.MemoryWriteForbiddenException("read-only"));

            assertThat(controller.update(request, UUID.randomUUID(), body()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("returns 403 to a VIEWER deleting a memory")
        void viewerCannotDelete() {
            org.mockito.Mockito.doThrow(new MemoryService.MemoryWriteForbiddenException("read-only"))
                .when(memoryService).delete(any(), anyString(), any());

            assertThat(controller.delete(request, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("agent scope on create")
    class AgentScope {

        @Test
        @DisplayName("stores a workspace-scoped memory when no agent is named, which is the normal case")
        void noAgentIdMeansWorkspaceScope() {
            when(memoryService.create(any(), any())).thenReturn(entity());

            assertThat(controller.create(request, body()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).create(captor.capture(), any());
            assertThat(captor.getValue().agentId()).isNull();
            assertThat(captor.getValue().source())
                .as("written through the UI, so it is attributed to a person")
                .isEqualTo(MemorySource.USER);
        }

        @Test
        @DisplayName("refuses an agent id from ANOTHER workspace instead of storing an orphan row")
        void foreignAgentIdIsRefused() {
            UUID foreign = UUID.randomUUID();
            when(agentRepository.existsByIdAndOrganizationIdStrict(foreign, ORG)).thenReturn(false);

            Map<String, Object> withForeignAgent = body();
            withForeignAgent.put("agentId", foreign.toString());

            ResponseEntity<?> response = controller.create(request, withForeignAgent);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("No such agent in this workspace");
            // Nothing was written, so the foreign key never gets to tell the caller
            // whether that UUID is an agent somewhere else on the platform.
            // create(), not save(): the controller never calls save, so a never().save(...)
            // assertion holds even when the row IS written - it could not fail for the
            // reason it exists.
            verify(memoryService, never()).create(any(), any());
        }

        @Test
        @DisplayName("refuses a malformed agent id rather than silently widening the audience to the workspace")
        void malformedAgentIdIsRefused() {
            Map<String, Object> withGarbage = body();
            withGarbage.put("agentId", "not-a-uuid");

            ResponseEntity<?> response = controller.create(request, withGarbage);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("not a valid agent id");
            // create(), not save(): the controller never calls save, so a never().save(...)
            // assertion holds even when the row IS written - it could not fail for the
            // reason it exists.
            verify(memoryService, never()).create(any(), any());
        }

        @Test
        @DisplayName("accepts an agent that belongs to the caller's workspace")
        void ownAgentIdIsAccepted() {
            UUID own = UUID.randomUUID();
            when(agentRepository.existsByIdAndOrganizationIdStrict(own, ORG)).thenReturn(true);
            when(memoryService.create(any(), any())).thenReturn(entity());

            Map<String, Object> withOwnAgent = body();
            withOwnAgent.put("agentId", own.toString());

            assertThat(controller.create(request, withOwnAgent).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).create(captor.capture(), any());
            assertThat(captor.getValue().agentId()).isEqualTo(own);
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("lists the WHOLE workspace by default, so a person can audit agent-private entries too")
        void listShowsBothScopes() {
            when(memoryService.listWorkspace(ORG)).thenReturn(List.of(entity()));

            assertThat(controller.list(request).getBody()).hasSize(1);
            verify(memoryService).listWorkspace(ORG);
            verify(memoryService, never()).listVisible(anyString(), any());
        }

        @Test
        @DisplayName("does NOT count a recall when a person opens a row, only when an agent reaches for it")
        void uiReadDoesNotSkewTheRecallCounter() {
            AgentMemoryEntity e = entity();
            when(memoryService.findInScope(e.getId(), ORG)).thenReturn(Optional.of(e));

            controller.get(request, e.getId());

            // The recall-recording read is the agent's path. Counting a human's click
            // there would corrupt the only evidence a person has for what to prune.
            verify(memoryService, never()).getByIdVisibleToAgent(any(), anyString(), any());
        }

        @Test
        @DisplayName("searches the WHOLE workspace, so a person can find an agent-private entry they can already see")
        void searchCoversBothScopes() {
            when(memoryService.searchWorkspace(ORG, "postmortem", 25)).thenReturn(List.of(entity()));

            assertThat(controller.search(request, "postmortem", null).getBody()).hasSize(1);

            // The agent-scoped search is the tool's; using it here would make the rows
            // the tab lists impossible to search for, which for a long list is the
            // same as not having them.
            verify(memoryService).searchWorkspace(ORG, "postmortem", 25);
            verify(memoryService, never()).search(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("leaves the body out of a listed row, and out of a search hit")
        void listRowsCarryNoBody() {
            AgentMemoryEntity e = entity();
            when(memoryService.listWorkspace(ORG)).thenReturn(List.of(e));
            when(memoryService.searchWorkspace(ORG, "postmortem", 25)).thenReturn(List.of(e));

            // The body is the only unbounded field (8000 characters by default) and no
            // list renders it, so sending it per row made opening the tab cost the size
            // of the workspace's whole memory rather than its number of entries. The
            // editor fetches the one entry it opens.
            assertThat(controller.list(request).getBody().get(0))
                .doesNotContainKey("content")
                .containsKeys("id", "slug", "title", "summary");
            assertThat(controller.search(request, "postmortem", null).getBody().get(0))
                .doesNotContainKey("content");
        }

        @Test
        @DisplayName("returns the body on a single-entry read, which is what the editor opens")
        void theSingleReadStillCarriesTheBody() {
            AgentMemoryEntity e = entity();
            e.setContent("Freeze starts Wednesday 14:00 UTC.");
            when(memoryService.findInScope(e.getId(), ORG)).thenReturn(Optional.of(e));

            // The other half of the contract above. If this read dropped the body too,
            // the editor would open empty and save that emptiness over the real one.
            assertThat(controller.get(request, e.getId()).getBody())
                .containsEntry("content", e.getContent());
        }

        @Test
        @DisplayName("exposes the fields the tab renders, including scope, source and the recall count")
        void dtoCarriesWhatTheTabNeeds() {
            AgentMemoryEntity e = entity();
            when(memoryService.findInScope(e.getId(), ORG)).thenReturn(Optional.of(e));

            Map<String, Object> dto = controller.get(request, e.getId()).getBody();

            assertThat(dto).containsKeys("id", "slug", "title", "summary", "content", "type",
                "tags", "pinned", "source", "agentId", "scope", "isActive", "recallCount", "updatedAt");
            assertThat(dto.get("scope")).isEqualTo("workspace");
            assertThat(dto.get("source")).isEqualTo("user");
        }
    }
    @Nested
    @DisplayName("create and update semantics")
    class WriteSemantics {

        private final Map<String, Object> body = Map.of(
            "title", "Release cadence", "summary", "The team ships on Thursdays.");

        @Test
        @DisplayName("answers 201 when the post created a row")
        void createdIs201() {
            when(memoryService.create(any(), anyString())).thenReturn(entity());

            assertThat(controller.create(request, body).getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("answers 409 naming the entry in the way when the title is already taken")
        void aCollisionIs409() {
            AgentMemoryEntity existing = entity();
            when(memoryService.create(any(), anyString()))
                .thenThrow(new MemoryService.MemoryConflictException(existing));

            var response = controller.create(request, body);

            // This used to be an upsert, so a person pressing "new" with a title that
            // collided replaced the existing entry - body erased, unpinned, retyped -
            // and was told "saved". The refusal has to NAME the entry in the way, or the
            // person is left guessing which of their memories collided - and the message
            // is what a reader sees, so the title and the handle belong in it rather than
            // in separate fields no caller ever read.
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().toString())
                .contains(existing.getTitle())
                .contains(existing.getSlug());
        }

        @Test
        @DisplayName("refuses an unknown type with 400 and names the four, instead of filing it as the default")
        void unknownTypeIs400() {
            Map<String, Object> withBadType = new java.util.HashMap<>(body);
            withBadType.put("type", "habit");

            var response = controller.create(request, withBadType);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().toString()).contains("project");
            // The value used to be swallowed into the default type, so a typo stored
            // the entry under the wrong bucket and answered 201 - while the agent tool
            // answered 400 for the very same input.
            verify(memoryService, never()).create(any(), anyString());
        }
    }

    @Nested
    @DisplayName("when long-term memory is switched off for the installation")
    class Disabled {

        @BeforeEach
        void turnItOff() {
            limits.setEnabled(false);
        }

        @Test
        @DisplayName("refuses to store anything new, which is what the flag says it does")
        void createIsRefused() {
            var response = controller.create(request, Map.of("title", "T", "summary", "S"));

            // 503, not 409: nothing conflicts, the capability is simply not on in
            // this installation, and a caller reading 409 would look for the clash.
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            // The tool already answers that nothing is stored and nothing is recalled.
            // This surface used to keep creating rows no agent would ever see, so the
            // same flag meant two different things depending on who was writing.
            verify(memoryService, never()).create(any(), anyString());
        }

        @Test
        @DisplayName("refuses an edit too, or the flag would only be half on")
        void updateIsRefused() {
            var response = controller.update(request, UUID.randomUUID(), Map.of("summary", "S"));

            // 503, not 409: nothing conflicts, the capability is simply not on in
            // this installation, and a caller reading 409 would look for the clash.
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            verify(memoryService, never()).update(any(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("still lists what is already stored, so switching the feature off does not hide the rows")
        void readsStillWork() {
            when(memoryService.listWorkspace(ORG)).thenReturn(List.of(entity()));

            assertThat(controller.list(request).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.list(request).getBody()).hasSize(1);
        }

        @Test
        @DisplayName("still deletes, because whoever just switched it off is who wants to clear it out")
        void deleteStillWorks() {
            UUID id = UUID.randomUUID();

            assertThat(controller.delete(request, id).getStatusCode().value()).isEqualTo(204);
            // 204 on its own proves nothing here: the service is a mock, so a method
            // that returned 204 without deleting anything would pass too.
            verify(memoryService).delete(id, ORG, "MEMBER");
        }
    }

    @Nested
    @DisplayName("a request that carries no workspace")
    class NoWorkspace {

        @Test
        @DisplayName("is answered 400, not 500, on a READ as well as on a write")
        void readsAnswer400() throws Exception {
            // Through MockMvc, because the answer comes from an @ExceptionHandler and a
            // direct method call never reaches one - the failure would surface only in
            // production, as the generic 500 this exists to remove.
            when(tenantResolver.resolveOrNull(any())).thenReturn(TENANT);
            when(tenantResolver.resolveOrgId(any())).thenReturn(null);
            when(memoryService.listWorkspace(any()))
                .thenThrow(new MemoryService.MemoryValidationException("A workspace is required."));

            org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(controller).build();

            // The writes already caught this; the reads let it reach the global handler
            // and become a generic 500. Same condition, same cause, two different
            // answers depending on which half of the resource the caller touched.
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/memories"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("update validation")
    class UpdateValidation {

        @Test
        @DisplayName("refuses an unknown type on an EDIT too, not only on a create")
        void unknownTypeOnUpdateIs400() {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("type", "habit");

            var response = controller.update(request, UUID.randomUUID(), body);

            // The create path was fixed and tested; the edit path shares the parser but
            // not the test, which is how half a fix ships.
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verify(memoryService, never()).update(any(), anyString(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        }
    }
}
