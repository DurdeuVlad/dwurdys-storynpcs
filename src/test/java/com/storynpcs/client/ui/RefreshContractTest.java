package com.storynpcs.client.ui;

import com.storynpcs.domain.panel.PlayerPanels.MailRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for the post-mutation refresh contract (issue #201):
 * pending acknowledgements and id-based selection that survives refresh.
 */
class RefreshContractTest {

    // ---- PendingAck ----

    @Test
    @DisplayName("pending: begin → pending, ack → cleared")
    void pendingAckCycle() {
        PendingAck p = new PendingAck();
        assertFalse(p.pending(1000));
        assertNull(p.label(1000));

        p.begin("Sending…", 1000);
        assertTrue(p.pending(1000));
        assertTrue(p.pending(4999));
        assertEquals("Sending…", p.label(2000));

        p.ack();
        assertFalse(p.pending(2000));
        assertNull(p.label(2000));
    }

    @Test
    @DisplayName("pending: expires after timeout so a chat-only reject can't wedge the UI")
    void pendingExpires() {
        PendingAck p = new PendingAck();
        p.begin("Deleting…", 1000, 4000);
        assertTrue(p.pending(4999));
        assertFalse(p.pending(5000));
        assertNull(p.label(5000), "expired pending must not render");
    }

    @Test
    @DisplayName("pending: re-begin replaces the prior label and resets the clock")
    void pendingRebegin() {
        PendingAck p = new PendingAck();
        p.begin("Sending…", 1000, 4000);
        p.begin("Marking read…", 3000, 4000);
        assertEquals("Marking read…", p.label(3000));
        assertTrue(p.pending(6999));
        assertFalse(p.pending(7000));
    }

    // ---- id-based selection (RowKeys.indexOf — the shared re-resolution
    //      helper; SelectableList applies the same contract via keys) ----

    private static MailRow row(String id) {
        return new MailRow(id, "sender", "subject", "body", 0L, false);
    }

    @Test
    @DisplayName("selection survives a refresh that inserts a row above it")
    void selectionSurvivesInsert() {
        List<MailRow> before = List.of(row("a"), row("b"), row("c"));
        int sel = RowKeys.indexOf(before, MailRow::id, "b");
        assertEquals(1, sel);

        List<MailRow> after = new ArrayList<>(before);
        after.add(0, row("new")); // server refresh inserts newest-first
        assertEquals(2, RowKeys.indexOf(after, MailRow::id, "b"),
                "same id must re-resolve to its new index");
    }

    @Test
    @DisplayName("selection clears when the refreshed view drops the row")
    void selectionClearsOnRemoval() {
        List<MailRow> after = List.of(row("a"), row("c"));
        assertEquals(-1, RowKeys.indexOf(after, MailRow::id, "b"));
    }

    @Test
    @DisplayName("null/empty views resolve to no selection, never throw")
    void selectionEdge() {
        assertEquals(-1, RowKeys.indexOf(null, MailRow::id, "a"));
        assertEquals(-1, RowKeys.indexOf(List.of(), MailRow::id, "a"));
        assertEquals(-1, RowKeys.indexOf(List.of(row("a")), MailRow::id, null));
        List<MailRow> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(row("x"));
        assertEquals(1, RowKeys.indexOf(withNull, MailRow::id, "x"));
    }

    // ---- AttemptIds (#204: request id lifetime vs server replay journal) ----

    @Test
    @DisplayName("attempt id: same key reuses id until ack — a lost-response retry replays the journal record")
    void attemptIdReusedBeforeAck() {
        AttemptIds ids = new AttemptIds();
        UUID first = ids.idFor("0");
        assertEquals(first, ids.idFor("0"), "retry before ack must reuse the id");
        assertNotEquals(first, ids.idFor("1"), "a different attempt needs its own id");
    }

    @Test
    @DisplayName("attempt id: ack clears — a post-refresh action on the same target mints a fresh id")
    void attemptIdFreshAfterAck() {
        AttemptIds ids = new AttemptIds();
        UUID first = ids.idFor("0");
        ids.ack();
        assertNotEquals(first, ids.idFor("0"),
                "reusing a resolved request id would be misclassified as a journal replay");
    }
}
