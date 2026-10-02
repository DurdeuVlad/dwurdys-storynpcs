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
        assertEquals(1, journal.pending().records().size());
        assertEquals(1, journal.pendingForSubject("player:test").records().size());
        assertTrue(journal.pendingForSubject("player:other").records().isEmpty());

        var committed = journal.commit(operationId, "APPLIED", "slot=3");
        assertEquals(DurableOperationJournal.State.COMMITTED, committed.state());
        assertEquals("tab=0;slot=3;item=minecraft:diamond;count=2", committed.preparedIntent());
        assertEquals(DurableOperationJournal.BeginStatus.COMMITTED,
                journal.begin(operationId, "bank.deposit", "player:test").status());
        assertTrue(journal.pending().records().isEmpty());
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
        // Record-commit stages only — INDEX_UPDATE belongs to indexed stores above this layer.
        for (DurableJsonStore.FailurePoint failurePoint : DurableJsonStore.FailurePoint.values()) {
            if (failurePoint == DurableJsonStore.FailurePoint.INDEX_UPDATE) continue;
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

    // ── terminal-record retention ─────────────────────────────────────────

    private static int countFiles(Path dir, String suffix) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var paths = Files.list(dir)) {
            return (int) paths.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static int countFilesContaining(Path dir, String fragment) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var paths = Files.list(dir)) {
            return (int) paths.filter(p -> p.getFileName().toString().contains(fragment)).count();
        }
    }

    @Test
    @DisplayName("Pruning deletes terminal records, their locks and backups, but keeps prepared work")
    void pruneDeletesTerminalKeepsPrepared(@TempDir Path tempDir) throws IOException {
        Path journalDir = tempDir.resolve("journal");
        DurableOperationJournal journal = new DurableOperationJournal(journalDir);
        UUID committed = UUID.randomUUID();
        UUID aborted = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        journal.begin(committed, "bank.deposit", "player:test");
        journal.commit(committed, "APPLIED", "slot=1"); // transition write rotates a .bak.1
        journal.begin(aborted, "trade.execute", "player:test");
        journal.abort(aborted, "REJECTED", "sold out");
        journal.begin(pending, "bank.withdraw", "player:test");
        assertEquals(3, countFiles(journalDir, ".json"));
        assertEquals(3, countFiles(journalDir, ".json.lock"));

        assertEquals(2, journal.pruneTerminalRecords(0, 0));

        assertEquals(1, countFiles(journalDir, ".json"), "Only the prepared record remains");
        assertEquals(1, countFiles(journalDir, ".json.lock"), "Terminal lock files are removed");
        assertEquals(0, countFiles(journalDir, ".bak.1"), "Backup generations are removed too");
        assertEquals(1, journal.pending().records().size());
        assertNull(journal.read(committed));
        assertNull(journal.read(aborted));
    }

    @Test
    @DisplayName("Pruning keeps the newest terminal records regardless of age")
    void pruneRetainsNewestTerminal(@TempDir Path tempDir) throws IOException {
        Path journalDir = tempDir.resolve("journal");
        DurableOperationJournal journal = new DurableOperationJournal(journalDir);
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            journal.begin(id, "trade.execute", "player:test");
            journal.commit(id, "APPLIED", Integer.toString(i));
        }

        assertEquals(3, journal.pruneTerminalRecords(0, 2));
        assertEquals(2, countFiles(journalDir, ".json"), "Two newest terminal records survive");
    }

    @Test
    @DisplayName("Pruning respects the age cutoff and removes orphaned lock files")
    void pruneRespectsAgeAndOrphanLocks(@TempDir Path tempDir) throws IOException {
        Path journalDir = tempDir.resolve("journal");
        DurableOperationJournal journal = new DurableOperationJournal(journalDir);
        UUID committed = UUID.randomUUID();
        journal.begin(committed, "trade.execute", "player:test");
        journal.commit(committed, "APPLIED", "ok");

        // Younger than the cutoff — nothing is pruned.
        assertEquals(0, journal.pruneTerminalRecords(
                java.time.Duration.ofDays(30).toMillis(), 0));
        assertEquals(2, countFiles(journalDir, ".json.lock") + countFiles(journalDir, ".json"));

        // An orphaned .lock left by a crash between lock creation and the first
        // write is deleted once it ages past the cutoff.
        Path orphanLock = journalDir.resolve(UUID.randomUUID() + ".json.lock");
        Files.writeString(orphanLock, "");
        Files.setLastModifiedTime(orphanLock,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 1000));
        journal.pruneTerminalRecords(0, 0);
        assertFalse(Files.exists(orphanLock), "Aged orphan lock files are swept");
    }

    @Test
    @DisplayName("Pruning never deletes a corrupt record — it may be recovery evidence")
    void pruneKeepsCorruptRecords(@TempDir Path tempDir) throws IOException {
        Path journalDir = tempDir.resolve("journal");
        Files.createDirectories(journalDir);
        Path corrupt = journalDir.resolve(UUID.randomUUID() + ".json");
        Files.writeString(corrupt, "{\"schemaVersion\":99,\"data\":{}}\n");

        DurableOperationJournal journal = new DurableOperationJournal(journalDir);
        journal.pruneTerminalRecords(0, 0);
        assertTrue(Files.exists(corrupt) || countFilesContaining(journalDir, ".corrupted.") > 0,
                "Corrupt files stay on disk for manual recovery");
    }

    @Test
    @DisplayName("Pending scan quarantines invalid records instead of blocking all recovery")
    void pendingScanToleratesInvalidRecords(@TempDir Path tempDir) throws IOException {
        Path journalDir = tempDir.resolve("journal");
        DurableOperationJournal journal = new DurableOperationJournal(journalDir);
        UUID valid = UUID.randomUUID();
        journal.begin(valid, "bank.withdraw", "player:test");

        // Decodable but semantically invalid (blank subject) — validation
        // failure must not poison recovery of every other pending operation.
        UUID semanticallyInvalid = UUID.randomUUID();
        Path invalidPath = journalDir.resolve(semanticallyInvalid + ".json");
        Files.writeString(invalidPath, "{\"schemaVersion\":1,\"data\":{"
                + "\"operationId\":\"" + semanticallyInvalid + "\","
                + "\"operationType\":\"bank.withdraw\",\"subject\":\" \","
                + "\"state\":\"PREPARED\",\"outcomeCode\":null,\"detail\":null,"
                + "\"createdAtEpochMillis\":0,\"updatedAtEpochMillis\":0,"
                + "\"preparedIntent\":null}}");

        // Undecodable bytes land in the same bucket through store quarantine.
        UUID undecodable = UUID.randomUUID();
        Files.writeString(journalDir.resolve(undecodable + ".json"), "not json {{{");

        var scan = journal.pending();
        assertEquals(1, scan.records().size());
        assertEquals(valid, scan.records().get(0).operationId());
        assertTrue(scan.diagnostics().toString().contains(semanticallyInvalid + ".json"));
        assertTrue(scan.diagnostics().toString().contains(undecodable + ".json"));
        assertFalse(Files.exists(invalidPath), "Invalid record is quarantined, not re-scanned forever");
        assertTrue(countFilesContaining(journalDir, ".corrupted.") >= 2);

        // Once quarantined, subsequent scans are clean and direct access to a
        // poisoned operation ID stays fail-closed.
        assertEquals(1, journal.pending().records().size());
        assertTrue(journal.pending().diagnostics().isEmpty());
        assertThrows(IOException.class,
                () -> journal.begin(semanticallyInvalid, "bank.withdraw", "player:test"));
    }
}
