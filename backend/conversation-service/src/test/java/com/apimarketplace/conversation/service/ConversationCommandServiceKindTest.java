package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a conversation IS, at the two write boundaries: it is decided once, and it cannot move.
 *
 * <p>Each test here fails on the pre-change code for a different reason, which is the point: before
 * the kind existed a studio conversation was indistinguishable from a chat, and once it exists the
 * hazard moves to the update path, where a change would be accepted and reported as a success.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationCommandService - conversation kind")
class ConversationCommandServiceKindTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;
    @Mock private UserChatDefaultsService userChatDefaultsService;

    private final ConversationMapper conversationMapper = new ConversationMapper();
    private ConversationCommandService service;

    @BeforeEach
    void setUp() {
        service = new ConversationCommandService(
                conversationRepository, messageRepository, conversationMapper, workflowContextProvider,
                userChatDefaultsService, null);
    }

    @Nested
    @DisplayName("createConversation")
    class Create {

        @Test
        @DisplayName("persists the requested kind, and hands it back")
        void persistsRequestedKind() {
            ConversationDto dto = buildDto();
            dto.setKind("studio");
            echoSave();

            ConversationDto result = service.createConversation(dto);

            assertThat(savedConversation().getKind()).isEqualTo("studio");
            // Handed back too: the caller routes on this without a second read.
            assertThat(result.getKind()).isEqualTo("studio");
        }

        @Test
        @DisplayName("an omitted kind is a chat, which is what every pre-existing row is")
        void omittedKindIsChat() {
            ConversationDto dto = buildDto();
            echoSave();

            ConversationDto result = service.createConversation(dto);

            assertThat(savedConversation().getKind()).isEqualTo("chat");
            assertThat(result.getKind()).isEqualTo("chat");
        }

        @Test
        @DisplayName("normalises the stored value, so the filter matches what was created")
        void normalisesStoredValue() {
            ConversationDto dto = buildDto();
            dto.setKind("  STUDIO ");
            echoSave();

            service.createConversation(dto);

            // Stored lowercase and trimmed. Persisting the caller's spelling instead would create a
            // conversation that the kind = 'studio' listing never returns.
            assertThat(savedConversation().getKind()).isEqualTo("studio");
        }

        @Test
        @DisplayName("refuses a studio conversation that also names an agent, rather than making a chat")
        void refusesStudioWithAgent() {
            // A conversation carrying an agent id is funnelled through the hardened agent path,
            // which builds its own DTO from six scalars and cannot carry a kind. Left alone, that
            // funnel answered 201 with a CHAT to a caller who asked for a studio one - the silent
            // default this enum exists to prevent, at the one call site that bypassed it.
            ConversationDto dto = buildDto();
            dto.setKind("studio");
            dto.setAgentId("agent-uuid-123");

            assertThatThrownBy(() -> service.createConversation(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("studio")
                    .hasMessageContaining("agentId");

            verify(conversationRepository, never()).save(any(Conversation.class));
        }

        @Test
        @DisplayName("leaves an ordinary agent conversation alone")
        void allowsAgentConversationWithNoKind() {
            // The guard must be about the KIND, not about agent conversations: every existing caller
            // sends an agentId with no kind at all, and refusing those would break agent chat.
            ConversationDto dto = buildDto();
            dto.setAgentId("agent-uuid-123");

            // It reaches the agent funnel, which this unit does not wire (the self-proxy is null),
            // so it fails there rather than at the kind guard. Reaching it at all is the assertion:
            // an agent conversation with no kind must not be refused.
            assertThatThrownBy(() -> service.createConversation(dto))
                    .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses an unknown kind rather than silently creating a chat")
        void refusesUnknownKind() {
            ConversationDto dto = buildDto();
            dto.setKind("generate");

            assertThatThrownBy(() -> service.createConversation(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generate");

            // Nothing was written: a refused create must not leave a row behind.
            verify(conversationRepository, never()).save(any(Conversation.class));
        }
    }

    @Nested
    @DisplayName("updateConversation")
    class Update {

        @Test
        @DisplayName("refuses to change the kind, naming both the current and the requested one")
        void refusesKindChange() {
            Conversation existing = existingStudioConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(existing));

            ConversationDto patch = new ConversationDto();
            patch.setKind("chat");

            assertThatThrownBy(() -> service.updateConversation("conv-1", patch))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("studio")
                    .hasMessageContaining("chat");

            // The refusal must be a refusal, not a no-op reported as a success.
            verify(conversationRepository, never()).save(any(Conversation.class));
            assertThat(existing.getKind()).isEqualTo("studio");
        }

        @Test
        @DisplayName("accepts a full-object update that restates the kind it already has")
        void acceptsRestatedKind() {
            Conversation existing = existingStudioConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(existing));
            when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));

            ConversationDto patch = new ConversationDto();
            patch.setKind("STUDIO");
            patch.setTitle("Renamed");

            ConversationDto result = service.updateConversation("conv-1", patch);

            // A PUT that echoes the whole object back is ordinary; only a DIFFERENT kind is refused.
            assertThat(result.getKind()).isEqualTo("studio");
            assertThat(result.getTitle()).isEqualTo("Renamed");
        }

        @Test
        @DisplayName("a patch that says nothing about the kind leaves it alone")
        void silentPatchLeavesKindAlone() {
            Conversation existing = existingStudioConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(existing));
            when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));

            ConversationDto patch = new ConversationDto();
            patch.setTitle("Renamed");

            ConversationDto result = service.updateConversation("conv-1", patch);

            assertThat(existing.getKind()).isEqualTo("studio");
            assertThat(result.getKind()).isEqualTo("studio");
        }
    }

    @Nested
    @DisplayName("a studio conversation is never an agent one")
    class StudioAndAgentAreExclusive {

        @Test
        @DisplayName("refuses a SUB-agent studio conversation, not only a primary one")
        void refusesSubAgentStudio() {
            // The guard used to reuse isPrimaryAgentShape, which is the narrower question "does this
            // go through the V115 funnel?". A sub-agent answers that FALSE while still being an
            // agent conversation, so adding one field walked straight past the refusal and persisted
            // a studio row bound to an agent - the exact state this rule forbids.
            ConversationDto dto = studioWithAgent();
            dto.setParentConversationId("parent-1");

            assertThatThrownBy(() -> service.createConversation(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agent");
        }

        @Test
        @DisplayName("refuses a memory-off studio conversation for the same reason")
        void refusesMemoryOffStudio() {
            ConversationDto dto = studioWithAgent();
            dto.setMemoryEnabled(false);

            assertThatThrownBy(() -> service.createConversation(dto))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("still refuses the primary shape, which is where this started")
        void refusesPrimaryStudio() {
            assertThatThrownBy(() -> service.createConversation(studioWithAgent()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a blank agentId is no agent at all, and a studio conversation is created")
        void blankAgentIdIsNotAnAgent() {
            // The other direction: a guard on "agentId is present" that forgot to treat blank as
            // absent would refuse an ordinary studio conversation whose caller sent an empty string.
            ConversationDto dto = studioWithAgent();
            dto.setAgentId("   ");
            echoSave();

            assertThat(service.createConversation(dto).getKind()).isEqualTo("studio");
        }

        private ConversationDto studioWithAgent() {
            ConversationDto dto = new ConversationDto();
            dto.setUserId("user-1");
            dto.setTitle("A studio");
            dto.setModel("seedance-2");
            dto.setProvider("seedance");
            dto.setOrganizationId("org-1");
            dto.setKind("studio");
            dto.setAgentId("agent-1");
            return dto;
        }
    }

    private void echoSave() {
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            c.setId("generated-id");
            return c;
        });
    }

    private Conversation savedConversation() {
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        return captor.getValue();
    }

    private Conversation existingStudioConversation() {
        Conversation existing = new Conversation("user-1", "A studio thread", "flux-1", "flux");
        existing.setId("conv-1");
        existing.setOrganizationId("org-1");
        existing.setKind("studio");
        return existing;
    }

    @Nested
    @DisplayName("the mapper never writes the kind")
    class MapperInvariant {

        @Test
        @DisplayName("updateEntity leaves the kind alone even when the DTO carries a different one")
        void updateEntityNeverWritesKind() {
            // ConversationMapper.updateEntity carries a comment saying "adding it here would be the
            // bug". Nothing pinned it: the service refuses a change before the mapper runs, and the
            // restate case writes the same value, so adding `kind` to updateEntity left every test
            // green. Called DIRECTLY here, past the guard, which is the only way to see it.
            Conversation existing = existingStudioConversation();
            ConversationDto dto = new ConversationDto();
            dto.setKind("chat");
            dto.setTitle("Renamed");

            conversationMapper.updateEntity(dto, existing);

            assertThat(existing.getKind()).isEqualTo("studio");
            // And it still does its job: the fields it IS responsible for are written.
            assertThat(existing.getTitle()).isEqualTo("Renamed");
        }
    }

    private ConversationDto buildDto() {
        ConversationDto dto = new ConversationDto();
        dto.setUserId("user-1");
        dto.setOrganizationId("org-1");
        dto.setTitle("Test Conversation");
        dto.setModel("gpt-4");
        dto.setProvider("openai");
        dto.setActive(true);
        return dto;
    }
}
