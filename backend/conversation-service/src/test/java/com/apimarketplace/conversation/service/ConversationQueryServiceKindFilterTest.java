package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the kind filter is applied, which is the whole substance of it.
 *
 * <p>A page of conversations is chosen by {@code updated_at}. Narrowing that page afterwards
 * answers "the studio conversations among the 20 most recent", which is empty for anyone whose
 * recent activity is chat and is indistinguishable, on screen, from having none. So these tests
 * assert which REPOSITORY METHOD is called, not what comes back: a post-filter implementation would
 * satisfy any assertion about the returned rows while still being the bug.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationQueryService - kind filter")
class ConversationQueryServiceKindFilterTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;

    private final ConversationMapper conversationMapper = new ConversationMapper();
    private ConversationQueryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationQueryService(
                conversationRepository, messageRepository, conversationMapper, workflowContextProvider);
    }

    @Test
    @DisplayName("no kind asks for every kind, exactly as before")
    void noKindKeepsTheUnfilteredListing() {
        when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), any(Pageable.class))).thenReturn(onePage("chat"));

        service.getConversationsByUserId("user-1", "org-1", 0, 20, false, null);

        verify(conversationRepository).findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), any(Pageable.class));
        verify(conversationRepository, never())
                .findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                        any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("a kind is pushed into the query, not applied to the fetched page")
    void kindIsPushedIntoTheQuery() {
        when(conversationRepository.findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), eq("studio"), any(Pageable.class))).thenReturn(onePage("studio"));

        Page<ConversationDto> result =
                service.getConversationsByUserId("user-1", "org-1", 0, 20, false, "studio");

        verify(conversationRepository).findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), eq("studio"), any(Pageable.class));
        // The unfiltered listing must not be touched: calling it and then narrowing is the defect.
        verify(conversationRepository, never())
                .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(any(), any(Pageable.class));
        assertThat(result.getContent()).singleElement()
                .extracting(ConversationDto::getKind).isEqualTo("studio");
    }

    @Test
    @DisplayName("includeInactive keeps the kind filter - the two narrowings compose")
    void includeInactiveKeepsTheKindFilter() {
        when(conversationRepository.findByOrganizationIdStrictAndKindOrderByUpdatedAtDesc(
                eq("org-1"), eq("studio"), any(Pageable.class))).thenReturn(onePage("studio"));

        service.getConversationsByUserId("user-1", "org-1", 0, 20, true, "studio");

        verify(conversationRepository).findByOrganizationIdStrictAndKindOrderByUpdatedAtDesc(
                eq("org-1"), eq("studio"), any(Pageable.class));
    }

    @Test
    @DisplayName("a blank kind means no filter, not a filter on the empty string")
    void blankKindMeansNoFilter() {
        when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), any(Pageable.class))).thenReturn(onePage("chat"));

        service.getConversationsByUserId("user-1", "org-1", 0, 20, false, "   ");

        // Filtering on "" would return nothing at all, which reads as an empty workspace.
        verify(conversationRepository).findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), any(Pageable.class));
    }

    @Test
    @DisplayName("normalises the requested kind before querying")
    void normalisesRequestedKind() {
        when(conversationRepository.findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), eq("studio"), any(Pageable.class))).thenReturn(onePage("studio"));

        service.getConversationsByUserId("user-1", "org-1", 0, 20, false, "STUDIO");

        // The column holds the lowercase wire value; querying the caller's spelling matches nothing.
        verify(conversationRepository).findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                eq("org-1"), eq("studio"), any(Pageable.class));
    }

    @Test
    @DisplayName("refuses an unknown kind instead of listing an empty result")
    void refusesUnknownKind() {
        assertThatThrownBy(() ->
                service.getConversationsByUserId("user-1", "org-1", 0, 20, false, "generate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generate");

        // An empty list would be read as "this workspace has none", which is a different fact.
        verify(conversationRepository, never())
                .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(any(), any(Pageable.class));
    }

    private Page<Conversation> onePage(String kind) {
        Conversation conversation = new Conversation("user-1", "A thread", "model", "provider");
        conversation.setId("conv-1");
        conversation.setOrganizationId("org-1");
        conversation.setKind(kind);
        return new PageImpl<>(List.of(conversation));
    }
}
