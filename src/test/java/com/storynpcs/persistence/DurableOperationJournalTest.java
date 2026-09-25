package com.storynpcs.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DurableOperationJournalTest {

    @Test
    @DisplayName("Operation lifecycle is durable and replay classification is stable")
    void testLifecycleAndReplay(@TempDir Path tempDir) throws IOException {
        DurableOperationJournal journal = new DurableOperationJournal(tempDir.resolve("journal"));
        UUID operationId = UUID.randomUUID();

        var started = journal.begin(operationId, "bank.deposit", "player:test",
                "tab=0;slot=3;item=minecraft:diamond;count=2");
        assertEquals(DurableOperationJournal.BeginStatus.STARTED, started.status());
        assertEquals(DurableOperationJournal.State.PREPARED, started.record().state());
        assertEquals("tab=0;slot=3;item=minecraft:diamond;count=2", started.record().preparedIntent());
        assertEquals(DurableOperationJournal.BeginStatus.PENDING,
                journal.begin(operationId, "bank.deposit", "player:test").status());
        assertEquals("tab=0;slot=3;item=minecraft:diamond;count=2", started.record().detail());
        assertEquals(1, journal.pending().size());
        assertEquals(1, journal.pendingForSubject("player:test").size());
        assertTrue(journal.pendingForSubject("player:other").isEmpty());

        var committed = journal.commit(operationId, "APPLIED", "slot=3");
        assertEquals(DurableOperationJournal.State.COMMITTED, committed.state());
        assertEquals("tab=0;slot=3;item=minecraft:diamond;count=2", committed.preparedIntent());
        assertEquals(DurableOperationJournal.BeginStatus.COMMITTED,
                journal.begin(operationId, "bank.deposit", "player:test").status());
        assertTrue(journal.pending().isEmpty());
        assertEquals(committed, journal.commit(operationId, "different", "ignored"),
                "A replay cannot rewrite a committed outcome");

        DurableOperationJournal reloaded = new DurableOperationJournal(tempDir.resolve("journal"));
        assertEquals(DurableOperationJournal.State.COMMITTED, reloaded.read(operationId).state());
    }

    @Test
    @DisplayName("Aborted operations are terminal and identity reuse is rejected")
    void testAbortAndIdentity(@TempDir Path tempDir) throws IOException {
        DurableOperationJournal journal = new DurableOperationJournal(tempDir.resolve("journal"));
        UUID operationId = UUID.randomUUID();
        journal.begin(operationId, "trade.execute", "player:test");

        var aborted = journal.abort(operationId, "VALIDATION_REJECTED", "listing unavailable");
        assertEquals(DurableOperationJournal.State.ABORTED, aborted.state());
        assertEquals(DurableOperationJournal.BeginStatus.ABORTED,
                journal.begin(operationId, "trade.execute", "player:test").status());
        assertThrows(IOException.class,
                () -> journal.begin(operationId, "bank.deposit", "player:test"));
        assertThrows(IOException.class,
                () -> journal.commit(operationId, "APPLIED", null));
    }

    @Test
    @DisplayName("Malformed or future records are not silently replaced")
    void testInvalidRecordIsRefused(@TempDir Path tempDir) throws IOException {
        Path journalDir = tempDir.resolve("journal");
        Files.createDirectories(journalDir);
        UUID operationId = UUID.randomUUID();
        Files.writeString(journalDir.resolve(operationId + ".json"),
                "{\"schemaVersion\":99,\"data\":{}}\n");

        DurableOperationJournal journal = new DurableOperationJournal(journalDir);
        assertThrows(IOException.class,
                () -> journal.begin(operationId, "bank.deposit", "player:test"));
        assertThrows(IOException.class,
                () -> journal.begin(operationId, "bank.deposit", "player:test"),
                "A quarantined operation ID must not be silently recreated");
        try (var paths = Files.list(journalDir)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().contains(".corrupted.")));
        }
    }

    @Test
    @DisplayName("Journal write failure leaves no false committed outcome")
    void testFailureInjection(@TempDir Path tempDir) throws IOException {
        for (DurableJsonStore.FailurePoint failurePoint : DurableJsonStore.FailurePoint.values()) {
            Path journalDir = tempDir.resolve(failurePoint.name().toLowerCase());
            DurableOperationJournal journal = new DurableOperationJournal(journalDir, point -> {
                if (point == failurePoint) throw new IOException("injected journal failure");
            });
            UUID operationId = UUID.randomUUID();
            assertThrows(IOException.class,
                    () -> journal.begin(operationId, "bank.deposit", "player:test"), failurePoint.toString());
            assertNull(journal.read(operationId), failurePoint.toString());
        }
    }
}
