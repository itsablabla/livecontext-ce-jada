package com.apimarketplace.auth.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InternalPurgeLogController")
class InternalPurgeLogControllerTest {

    private EntityManager em;
    private Query query;
    private InternalPurgeLogController controller;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(query);
        controller = new InternalPurgeLogController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "em", em);
    }

    @Test
    @DisplayName("Returns the rows after the caller's seq, in log order, mapped to InternalPurgeLogController.PurgeLogEntry")
    void returnsRowsAfterSeq() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{7L, "ORG", "org-a"}, new Object[]{8L, "USER", "42"}));

        List<InternalPurgeLogController.PurgeLogEntry> page = controller.purgesAfter(6, 200).getBody();

        assertEquals(List.of(new InternalPurgeLogController.PurgeLogEntry(7, "ORG", "org-a"), new InternalPurgeLogController.PurgeLogEntry(8, "USER", "42")), page);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("seq > ?1"), "keyset on seq");
        assertTrue(sql.getValue().contains("ORDER BY seq"), "log order");
        // Commit-visibility gap: a lower seq still inside an open purge transaction must not be
        // skipped by a follower that already saw a higher one. Rows are served only once settled.
        assertTrue(sql.getValue().contains("purged_at < now() - interval '" + InternalPurgeLogController.SETTLE_SECONDS + " seconds'"),
                "rows must settle before they are served");
        verify(query).setParameter(1, 6L);
    }

    /**
     * The client side (auth-client PurgeRecord) reads these three names off the JSON; auth
     * does not depend on that jar, so the names are pinned on both sides independently.
     */
    @Test
    @DisplayName("The wire record exposes exactly seq, subjectType, subjectId")
    void wireFieldNamesArePinned() {
        java.util.List<String> names = java.util.Arrays.stream(InternalPurgeLogController.PurgeLogEntry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(java.util.List.of("seq", "subjectType", "subjectId"), names);
    }

    /**
     * Clamped rather than refused: this endpoint can only under-deliver (the follower pages
     * again from the last seq), so a generous caller gets a full page instead of an error.
     */
    @Test
    @DisplayName("The page size is clamped to the cap, and to at least one")
    void limitIsClamped() {
        when(query.getResultList()).thenReturn(List.of());

        controller.purgesAfter(0, 5000);
        verify(query).setParameter(eq(2), eq(InternalPurgeLogController.MAX_LIMIT));

        controller.purgesAfter(0, 0);
        verify(query).setParameter(eq(2), eq(1));
    }
}
