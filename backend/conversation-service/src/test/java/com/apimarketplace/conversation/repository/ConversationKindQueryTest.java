package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.Conversation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two kind-filtered listing queries, PARSED AND EXECUTED.
 *
 * <p><b>Why this exists at all.</b> A JPQL string in a {@code @Query} annotation is not compiled: a
 * typo in it surfaces when Hibernate builds the repository, which in this service happens only when
 * the application boots. No test in the default build boots it, so without this class the two
 * queries have no automated reader, and the first person to learn of a mistake in them is a user.
 *
 * <p><b>Why H2 rather than a PostgreSQL testcontainer.</b> The queries are plain JPQL with no
 * PostgreSQL-only function in them, and a testcontainer test needs a Docker socket the default build
 * does not have - so it would be exactly as ungated as the end-to-end suite it is meant to back up.
 * The repository proxy is built over a bare EntityManager (the same pattern as
 * {@code ConversationSummaryColdRepositoryIT}) because the {@code @DataJpaTest} slice trips over
 * this service's Redis configuration.
 *
 * <p><b>What it actually pins.</b> Not "the filter returns rows": that a page comes back proves
 * nothing about WHERE the narrowing happened. It pins that a conversation of the wanted kind is
 * returned even when newer conversations of another kind would fill the page - which is only
 * possible if the kind is in the query. That is the whole reason the finder exists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConversationRepository - the kind-filtered listing")
class ConversationKindQueryTest {

    private EntityManagerFactory emf;
    private EntityManager em;
    private ConversationRepository repository;

    private static final String ORG = "org-1";

    @BeforeAll
    void setUp() {
        emf = Persistence.createEntityManagerFactory("conversation-kind-h2-pu");
        em = emf.createEntityManager();
        repository = new JpaRepositoryFactory(em).getRepository(ConversationRepository.class);

        em.getTransaction().begin();
        // Oldest first, so the studio one is buried under newer chats. The order matters: it is what
        // makes a page-narrowing implementation fail this test.
        persist("studio-old", "studio", true, LocalDateTime.now().minusDays(3));
        persist("chat-newer-1", "chat", true, LocalDateTime.now().minusDays(2));
        persist("chat-newer-2", "chat", true, LocalDateTime.now().minusDays(1));
        persist("studio-inactive", "studio", false, LocalDateTime.now().minusHours(1));
        // A workflow-bound studio conversation that never got a message. Both finders carry
        // `(workflowId IS NULL OR messages IS NOT EMPTY)` to keep exactly this row out of a sidebar:
        // it is the empty shell a workflow creates, not a thread anybody started.
        Conversation emptyWorkflowBound = new Conversation("user-1", "studio-workflow-empty", "model", "provider");
        emptyWorkflowBound.setOrganizationId(ORG);
        emptyWorkflowBound.setKind("studio");
        emptyWorkflowBound.setActive(true);
        emptyWorkflowBound.setWorkflowId("wf-1");
        emptyWorkflowBound.setUpdatedAt(LocalDateTime.now());
        em.persist(emptyWorkflowBound);
        em.getTransaction().commit();
    }

    @AfterAll
    void tearDown() {
        if (em != null && em.isOpen()) em.close();
        if (emf != null && emf.isOpen()) emf.close();
    }

    /**
     * The id is left to Hibernate. Setting one by hand makes the entity DETACHED (the id is
     * {@code GenerationType.UUID}), so `persist` refuses it - which is why the rows below are
     * identified by their title.
     */
    private void persist(String name, String kind, boolean active, LocalDateTime updatedAt) {
        Conversation conversation = new Conversation("user-1", name, "model", "provider");
        conversation.setOrganizationId(ORG);
        conversation.setKind(kind);
        conversation.setActive(active);
        conversation.setUpdatedAt(updatedAt);
        em.persist(conversation);
    }

    @Test
    @DisplayName("finds a studio conversation buried under newer chats, which post-filtering cannot")
    void findsOneOfTheKindBeneathNewerRowsOfAnother() {
        // A page of size 2 ordered by recency holds only the two chats. Getting the studio row back
        // is possible only because the kind is part of the QUERY.
        Page<Conversation> page = repository
                .findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                        ORG, "studio", PageRequest.of(0, 2));

        assertThat(page.getContent()).extracting(Conversation::getTitle).contains("studio-old");
        assertThat(page.getContent()).allMatch(c -> "studio".equals(c.getKind()));
    }

    @Test
    @DisplayName("leaves the other kind out entirely")
    void excludesTheOtherKind() {
        Page<Conversation> page = repository
                .findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                        ORG, "studio", PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Conversation::getTitle)
                .doesNotContain("chat-newer-1", "chat-newer-2");
    }

    @Test
    @DisplayName("the active-only finder drops an inactive conversation of the wanted kind")
    void activeOnlyDropsInactive() {
        Page<Conversation> page = repository
                .findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                        ORG, "studio", PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Conversation::getTitle)
                .doesNotContain("studio-inactive");
    }

    @Test
    @DisplayName("the include-inactive finder keeps it, which is the only difference between them")
    void includeInactiveKeepsIt() {
        Page<Conversation> page = repository.findByOrganizationIdStrictAndKindOrderByUpdatedAtDesc(
                ORG, "studio", PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Conversation::getTitle)
                .contains("studio-old", "studio-inactive");
    }

    @Test
    @DisplayName("both finders are scoped to the workspace, not to the whole table")
    void scopedToTheWorkspace() {
        // Strict isolation is the rule every conversation read follows. A finder that dropped the
        // organization predicate would still pass every assertion above.
        List<Page<Conversation>> pages = List.of(
                repository.findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                        "another-org", "studio", PageRequest.of(0, 50)),
                repository.findByOrganizationIdStrictAndKindOrderByUpdatedAtDesc(
                        "another-org", "studio", PageRequest.of(0, 50)));

        assertThat(pages).allSatisfy(page -> assertThat(page.getContent()).isEmpty());
    }

    @Test
    @DisplayName("orders newest first, which is the order the sidebar is read in")
    void newestFirst() {
        // Not decoration: the finder is paged, so the ORDER BY decides WHICH conversations exist as
        // far as the reader is concerned. Flipped to ascending, page 0 of a long-lived workspace is
        // the oldest threads and a conversation used this morning is unreachable.
        Page<Conversation> page = repository.findByOrganizationIdStrictAndKindOrderByUpdatedAtDesc(
                ORG, "studio", PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Conversation::getTitle)
                .containsExactly("studio-inactive", "studio-old");
    }

    @Test
    @DisplayName("hides a workflow-bound conversation that has no messages, on both finders")
    void hidesEmptyWorkflowBoundConversations() {
        // The empty shell a workflow creates for itself. Listing it puts a thread in the sidebar
        // that opens onto nothing, and it is the ONLY row here that distinguishes the predicate
        // being present from it being deleted.
        assertThat(repository.findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                ORG, "studio", PageRequest.of(0, 50)).getContent())
                .extracting(Conversation::getTitle).doesNotContain("studio-workflow-empty");
        assertThat(repository.findByOrganizationIdStrictAndKindOrderByUpdatedAtDesc(
                ORG, "studio", PageRequest.of(0, 50)).getContent())
                .extracting(Conversation::getTitle).doesNotContain("studio-workflow-empty");
    }

    @Test
    @DisplayName("the TOTAL counts the filtered set, so the sidebar does not offer a page that is not there")
    void totalReflectsTheFilter() {
        // Spring derives the count query from the same JPQL. A count that lost the kind - or the
        // active flag - reports more conversations than the finder can return, and the list keeps
        // offering a next page after the last one.
        Page<Conversation> page = repository
                .findByOrganizationIdStrictAndKindAndActiveTrueOrderByUpdatedAtDesc(
                        ORG, "studio", PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a conversation is a chat until something says otherwise")
    void defaultsToChat() {
        // The entity default mirrors the column default. Dropping it is invisible for a row read
        // back from the database and wrong for every row built in memory - which is every row this
        // service creates before it is first saved.
        assertThat(new Conversation("user-1", "fresh", "model", "provider").getKind()).isEqualTo("chat");
    }
}
