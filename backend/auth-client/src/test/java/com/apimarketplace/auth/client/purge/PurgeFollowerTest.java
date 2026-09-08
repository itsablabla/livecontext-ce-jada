package com.apimarketplace.auth.client.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower.PassReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PurgeFollower")
class PurgeFollowerTest {

    private AuthClient authClient;
    private PurgeFollower.Handler handler;
    private final AtomicLong cursorValue = new AtomicLong(0);
    private final List<Long> cursorWrites = new ArrayList<>();
    private PurgeFollower follower;

    @BeforeEach
    void setUp() {
        authClient = mock(AuthClient.class);
        handler = mock(PurgeFollower.Handler.class);
        PurgeFollower.Cursor cursor = new PurgeFollower.Cursor() {
            @Override public long read() { return cursorValue.get(); }
            @Override public void advanceTo(long seq) { cursorWrites.add(seq); cursorValue.accumulateAndGet(seq, Math::max); }
        };
        follower = new PurgeFollower("test", authClient, cursor, handler);
        when(authClient.getPurges(anyLong(), anyInt())).thenReturn(List.of());
    }

    private static PurgeRecord org(long seq, String id) { return new PurgeRecord(seq, PurgeRecord.ORG, id); }
    private static PurgeRecord user(long seq, String id) { return new PurgeRecord(seq, PurgeRecord.USER, id); }

    @Test
    @DisplayName("Dispatches ORG and USER subjects to the matching handler method, in log order")
    void dispatchesBySubjectType() {
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(org(1, "org-a"), user(2, "42"), org(3, "org-b")));

        PassReport report = follower.runPass();

        InOrder order = inOrder(handler);
        order.verify(handler).purgeOrganization("org-a");
        order.verify(handler).purgeUser("42");
        order.verify(handler).purgeOrganization("org-b");
        assertEquals(3, report.applied());
        assertTrue(report.clean());
        assertEquals(3, cursorValue.get());
    }

    /**
     * The at-least-once contract. The cursor moves only after a subject's handler returned,
     * one record at a time, so a crash between two records replays exactly the unfinished one
     * and nothing is skipped.
     */
    @Test
    @DisplayName("The cursor advances after each handled record, never before")
    void cursorAdvancesPerRecord() {
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(org(5, "a"), org(9, "b")));

        follower.runPass();

        assertEquals(List.of(5L, 9L), cursorWrites);
    }

    /**
     * A poisoned record must STALL, not be dropped: a deletion the user was promised is
     * worth a visible stuck follower more than a silent hole. So a throwing handler ends the
     * pass, the cursor stays before the failed record, and the records behind it are not
     * touched this pass.
     */
    @Test
    @DisplayName("A handler that throws stops the pass without advancing past the failed record")
    void handlerFailureStopsWithoutSkipping() {
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(org(1, "ok"), org(2, "boom"), org(3, "after")));
        doThrow(new IllegalStateException("table locked")).when(handler).purgeOrganization("boom");

        PassReport report = follower.runPass();

        assertFalse(report.clean());
        assertEquals(1, report.applied());
        assertEquals(1, cursorValue.get(), "cursor must stay on the last GOOD record");
        verify(handler, never()).purgeOrganization("after");
    }

    @Test
    @DisplayName("The next pass retries the failed record first")
    void nextPassRetriesTheFailedRecord() {
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(org(1, "ok"), org(2, "boom")));
        when(authClient.getPurges(1, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(org(2, "boom")));
        doThrow(new IllegalStateException("once")).doNothing().when(handler).purgeOrganization("boom");

        follower.runPass();
        PassReport second = follower.runPass();

        assertTrue(second.clean());
        assertEquals(2, cursorValue.get());
        verify(authClient).getPurges(eq(1L), anyInt());
    }

    @Test
    @DisplayName("Pages through the log until a short page, asking from the last seq each time")
    void pagesUntilShortPage() {
        List<PurgeRecord> full = IntStream.rangeClosed(1, PurgeFollower.PAGE_SIZE).mapToObj(i -> org(i, "o" + i)).toList();
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(full);
        when(authClient.getPurges(PurgeFollower.PAGE_SIZE, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(org(PurgeFollower.PAGE_SIZE + 1, "last")));

        PassReport report = follower.runPass();

        assertEquals(PurgeFollower.PAGE_SIZE + 1, report.applied());
        verify(authClient).getPurges(eq((long) PurgeFollower.PAGE_SIZE), anyInt());
        verify(authClient, never()).getPurges(eq((long) PurgeFollower.PAGE_SIZE + 1), anyInt());
    }

    @Test
    @DisplayName("An empty log (or auth unreachable, which reads the same) is a clean no-op")
    void emptyLogIsNoop() {
        PassReport report = follower.runPass();

        assertEquals(0, report.applied());
        assertTrue(report.clean());
        verify(handler, never()).purgeOrganization(anyString());
        verify(handler, never()).purgeUser(anyString());
    }

    /**
     * A subject type shipped by a newer auth-service is not this service's failure: it is
     * skipped and the cursor moves on, otherwise one unknown row would stall every follower
     * of the previous release forever.
     */
    @Test
    @DisplayName("An unknown subject type is skipped, not treated as a failure")
    void unknownSubjectIsSkipped() {
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(new PurgeRecord(1, "PROJECT", "p1"), org(2, "o")));

        PassReport report = follower.runPass();

        assertTrue(report.clean());
        assertEquals(2, cursorValue.get());
        verify(handler).purgeOrganization("o");
    }

    /**
     * The other side of the "unknown type is skipped" rule. A record with NO type or NO id is
     * what a wire drift looks like (a renamed JSON field deserialises to null); skipping those
     * would silently retain every purge behind them. So they stall the pass, visibly.
     */
    @Test
    @DisplayName("A record with a null subject type or id stalls the pass instead of being skipped")
    void nullSubjectStallsInsteadOfSkipping() {
        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(
                org(1, "ok"), new PurgeRecord(2, null, "x"), org(3, "after")));

        PassReport report = follower.runPass();

        assertFalse(report.clean());
        assertEquals(1, cursorValue.get(), "cursor must not move past the drifted record");
        verify(handler, never()).purgeOrganization("after");

        when(authClient.getPurges(0, PurgeFollower.PAGE_SIZE)).thenReturn(List.of(new PurgeRecord(1, PurgeRecord.ORG, "")));
        cursorValue.set(0);
        assertFalse(follower.runPass().clean(), "a blank id is drift too");
    }

    @Test
    @DisplayName("A pass starts from the stored cursor, not from zero")
    void startsFromStoredCursor() {
        cursorValue.set(40);

        follower.runPass();

        verify(authClient).getPurges(eq(40L), anyInt());
        verify(authClient, never()).getPurges(eq(0L), anyInt());
    }
}
