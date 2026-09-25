package com.storynpcs.persistence;

import com.storynpcs.api.event.BankTransactionEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.service.BankWithdrawalOperationResult;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BankRepositoryTest {

    private static final class FailingBankRepository extends BankRepository {
        private boolean failSaves;

        private FailingBankRepository(Path storageDirectory) {
            super(storageDirectory);
        }

        private void setFailSaves(boolean failSaves) {
            this.failSaves = failSaves;
        }

        @Override
        void saveVault(UUID playerUuid, BankVault vault) throws IOException {
            if (failSaves) throw new IOException("injected bank commit failure");
            super.saveVault(playerUuid, vault);
        }
    }

    private static final class RacingBankRepository extends BankRepository {
        private final UUID playerUuid;
        private boolean mutateBeforeNextTransaction;

        private RacingBankRepository(Path storageDirectory, UUID playerUuid) {
            super(storageDirectory);
            this.playerUuid = playerUuid;
        }

        private void mutateBeforeNextTransaction() {
            mutateBeforeNextTransaction = true;
        }

        @Override
        public <T> BankTransactionResult<T> transact(
                UUID targetPlayerUuid,
                java.util.function.Function<BankVault, BankMutation<T>> operation) {
            if (mutateBeforeNextTransaction) {
                mutateBeforeNextTransaction = false;
                BankTransactionResult<Boolean> concurrentMutation = super.transact(playerUuid, vault ->
                        vault.deposit(0, 0, "minecraft:diamond", 1)
                                ? BankMutation.changed(true)
                                : BankMutation.unchanged(false));
                if (!concurrentMutation.committed()) {
                    return BankTransactionResult.failed(null, "injected concurrent vault mutation failed");
                }
            }
            return super.transact(targetPlayerUuid, operation);
        }
    }

    private static final class BlockingBankRepository extends BankRepository {
        private final AtomicBoolean blockNextTransaction = new AtomicBoolean();
        private volatile CountDownLatch transactionEntered;
        private volatile CountDownLatch allowTransaction;

        private BlockingBankRepository(Path storageDirectory) {
            super(storageDirectory);
        }

        private void blockNextTransaction(CountDownLatch entered, CountDownLatch allow) {
            transactionEntered = entered;
            allowTransaction = allow;
            blockNextTransaction.set(true);
        }

        @Override
        public <T> BankTransactionResult<T> transact(
                UUID targetPlayerUuid,
                java.util.function.Function<BankVault, BankMutation<T>> operation) {
            if (blockNextTransaction.compareAndSet(true, false)) {
                transactionEntered.countDown();
                try {
                    if (!allowTransaction.await(10, TimeUnit.SECONDS)) {
                        return BankTransactionResult.failed(null, "test transaction release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return BankTransactionResult.failed(null, "test transaction was interrupted");
                }
            }
            return super.transact(targetPlayerUuid, operation);
        }
    }

    private BankRepository bankRepo;
    private StoryNpcsApplicationService service;
    private List<BankTransactionEvent> bankEvents;
    private Path banksDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        banksDir = tempDir.resolve("banks");
        bankRepo = new BankRepository(banksDir);
        ProgressionRepository progRepo = new ProgressionRepository(tempDir.resolve("progression"));
        DefinitionRegistry reg = new DefinitionRegistry();
        EventPublisher pub = new EventPublisher();
        bankEvents = new ArrayList<>();
        pub.register(event -> {
            if (event instanceof BankTransactionEvent be) {
                bankEvents.add(be);
            }
        });

        service = new StoryNpcsApplicationService(reg, progRepo, pub);
    }

    @Test
    @DisplayName("Bank vault deposits items, stacks on existing items, and withdraws partially/fully")
    void testVaultDepositAndWithdrawal() {
        UUID playerUuid = UUID.randomUUID();

        // 1. Deposit into slot 0
        boolean dep1 = service.depositToBank(playerUuid, bankRepo, 0, 0, "minecraft:diamond", 10);
        assertTrue(dep1);
        assertEquals(1, bankEvents.size());
        assertEquals(BankTransactionEvent.Type.DEPOSIT, bankEvents.get(0).type());

        // 2. Deposit same item into slot 0 -> stacks
        boolean dep2 = service.depositToBank(playerUuid, bankRepo, 0, 0, "minecraft:diamond", 5);
        assertTrue(dep2);

        BankVault vault = bankRepo.getOrCreate(playerUuid);
        assertEquals(1, vault.getTabItems(0).size());
        assertEquals(15, vault.getTabItems(0).get(0).getCount());

        // 3. Withdraw partial count
        Optional<BankVault.VaultItem> w1 = service.withdrawFromBank(playerUuid, bankRepo, 0, 0, 5);
        assertTrue(w1.isPresent());
        assertEquals(5, w1.get().getCount());
        assertEquals(10, vault.getTabItems(0).get(0).getCount());

        // 4. Withdraw remaining count -> removes item
        Optional<BankVault.VaultItem> w2 = service.withdrawFromBank(playerUuid, bankRepo, 0, 0, 10);
        assertTrue(w2.isPresent());
        assertEquals(10, w2.get().getCount());
        assertEquals(0, vault.getTabItems(0).size());
    }

    @Test
    @DisplayName("Canonical whole-stack withdrawal owns amount selection")
    void wholeStackWithdrawalDoesNotRequireAdapterVaultInspection() {
        UUID playerUuid = UUID.randomUUID();
        assertTrue(service.depositToBank(playerUuid, bankRepo, 0, 0, "minecraft:stone", 12));

        var result = service.withdrawEntireStackFromBank(playerUuid, bankRepo, 0, 0);

        assertTrue(result.accepted());
        assertNotNull(result.item());
        assertEquals(12, result.item().getCount());
        assertTrue(bankRepo.getOrCreate(playerUuid).getTabItems(0).isEmpty());
    }

    @Test
    @DisplayName("Whole-stack withdrawal preserves rejection diagnostics")
    void wholeStackWithdrawalReportsValidationAndStateRejections() {
        UUID playerUuid = UUID.randomUUID();

        assertEquals("INVALID_TAB", service.withdrawEntireStackFromBank(playerUuid, bankRepo, -1, 0).code());
        assertEquals("INVALID_SLOT", service.withdrawEntireStackFromBank(playerUuid, bankRepo, 0, 54).code());
        assertEquals("TAB_LOCKED", service.withdrawEntireStackFromBank(playerUuid, bankRepo, 1, 0).code());
        assertEquals("SLOT_EMPTY", service.withdrawEntireStackFromBank(playerUuid, bankRepo, 0, 0).code());
    }

    @Test
    @DisplayName("Whole-stack withdrawal request IDs replay without withdrawing twice")
    void wholeStackWithdrawalJournalClassifiesDuplicateRequest() {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        assertTrue(service.depositToBank(playerUuid, bankRepo, 0, 0, "minecraft:stone", 12));

        var first = service.withdrawAndDeliverFromBank(playerUuid, bankRepo, 0, 0, requestId);
        var replay = service.withdrawAndDeliverFromBank(playerUuid, bankRepo, 0, 0, requestId);

        assertTrue(first.accepted());
        assertEquals("REPLAYED", replay.code());
        assertTrue(bankRepo.getOrCreate(playerUuid).getTabItems(0).isEmpty());
    }

    @Test
    @DisplayName("Journaled withdrawal rejects a vault revision change after intent capture")
    void wholeStackWithdrawalRejectsStaleCapturedVaultRevision(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RacingBankRepository repo = new RacingBankRepository(
                tempDir.resolve("banks_racing_withdrawal"), playerUuid);
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));
        repo.mutateBeforeNextTransaction();

        var withdrawal = service.withdrawAndDeliverFromBank(playerUuid, repo, 0, 0, requestId);

        assertFalse(withdrawal.accepted());
        assertEquals("STALE_VAULT", withdrawal.code());
        assertEquals(DurableOperationJournal.State.ABORTED,
                repo.operationJournal().read(requestId).state());
        assertEquals(13, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Recovery cannot abort a withdrawal between prepare and bank commit")
    void recoveryWaitsForInFlightJournaledWithdrawal(@TempDir Path tempDir) throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BlockingBankRepository repo = new BlockingBankRepository(
                tempDir.resolve("banks_withdrawal_recovery_race"));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));

        CountDownLatch transactionEntered = new CountDownLatch(1);
        CountDownLatch allowTransaction = new CountDownLatch(1);
        repo.blockNextTransaction(transactionEntered, allowTransaction);
        AtomicReference<BankWithdrawalOperationResult> withdrawalResult = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicInteger recoveryResult = new AtomicInteger(-1);
        Thread withdrawal = new Thread(() -> {
            try {
                withdrawalResult.set(service.withdrawAndDeliverFromBank(
                        playerUuid, repo, 0, 0, requestId));
            } catch (Throwable failure) {
                workerFailure.compareAndSet(null, failure);
            }
        }, "bank-withdrawal-race-test");
        Thread recovery = new Thread(() -> {
            try {
                recoveryResult.set(service.recoverBankOperations(playerUuid, repo));
            } catch (Throwable failure) {
                workerFailure.compareAndSet(null, failure);
            }
        }, "bank-recovery-race-test");

        try {
            withdrawal.start();
            assertTrue(transactionEntered.await(5, TimeUnit.SECONDS),
                    "withdrawal should reach its bank transaction after journaling PREPARED");
            recovery.start();
            assertTrue(awaitOperationLockWait(recovery, 5, TimeUnit.SECONDS),
                    "recovery should wait on the same request lock held by the withdrawal");
        } finally {
            allowTransaction.countDown();
        }

        withdrawal.join(TimeUnit.SECONDS.toMillis(10));
        if (recovery.getState() != Thread.State.NEW) {
            recovery.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(withdrawal.isAlive(), "withdrawal worker should complete");
        assertFalse(recovery.isAlive(), "recovery worker should complete");
        assertNull(workerFailure.get(), "concurrent workers should not throw");
        assertNotNull(withdrawalResult.get());
        assertTrue(withdrawalResult.get().accepted());
        assertEquals(0, recoveryResult.get(), "recovery should observe the committed request");
        assertEquals(DurableOperationJournal.State.COMMITTED,
                repo.operationJournal().read(requestId).state());
        assertTrue(repo.getOrCreate(playerUuid).getTabItems(0).isEmpty());
    }

    private static boolean awaitOperationLockWait(Thread thread, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            boolean waitingOnOperationLock = thread.getState() == Thread.State.BLOCKED
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(DurableOperationJournal.class.getName())
                            && frame.getMethodName().equals("withOperationLock"));
            if (waitingOnOperationLock) return true;
            Thread.sleep(1);
        }
        return false;
    }

    @Test
    @DisplayName("BankRepository saves and reloads vaults atomically from disk")
    void testPersistence(@TempDir Path tempDir) throws IOException {
        Path bankDir = tempDir.resolve("banks");
        BankRepository repo = new BankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();

        BankVault vault = repo.getOrCreate(playerUuid);
        vault.deposit(0, 1, "minecraft:emerald", 64);
        repo.save(playerUuid);

        Path expectedFile = bankDir.resolve(playerUuid + ".json");
        assertTrue(Files.exists(expectedFile));

        // Reload from clean cache
        repo.clearCache();
        BankVault loaded = repo.getOrCreate(playerUuid);
        assertEquals(1, loaded.getTabItems(0).size());
        assertEquals("minecraft:emerald", loaded.getTabItems(0).get(0).getItemId());
        assertEquals(64, loaded.getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Deposit and withdrawal operations persist automatically to disk")
    void testAutomaticPersistenceOnTransactions(@TempDir Path tempDir) {
        Path bankDir = tempDir.resolve("banks_auto");
        BankRepository repo = new BankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();

        service.depositToBank(playerUuid, repo, 0, 2, "minecraft:netherite_ingot", 16);

        // Verify file was written to disk immediately without manual save
        Path targetFile = bankDir.resolve(playerUuid + ".json");
        assertTrue(Files.exists(targetFile));

        // Reload from fresh repository instance
        BankRepository reloadedRepo = new BankRepository(bankDir);
        BankVault reloadedVault = reloadedRepo.getOrCreate(playerUuid);
        assertEquals(1, reloadedVault.getTabItems(0).size());
        assertEquals(16, reloadedVault.getTabItems(0).get(0).getCount());
        assertEquals(1, reloadedVault.getRevision(),
                "The durable vault revision must survive reload for recovery identity");
    }

    @Test
    @DisplayName("Corrupted bank vault fails closed: quarantined, blocked, writes refused")
    void testCorruptedBankBackup(@TempDir Path tempDir) throws IOException {
        Path bankDir = tempDir.resolve("banks_corrupt");
        BankRepository repo = new BankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();

        Path targetFile = bankDir.resolve(playerUuid + ".json");
        Files.writeString(targetFile, "{ broken json content");

        // Fail closed: an unrecoverable vault must never become empty state.
        assertThrows(UnrecoverablePlayerDataException.class, () -> repo.getOrCreate(playerUuid));
        assertTrue(repo.isUnavailable(playerUuid));
        assertNotNull(repo.unavailabilityReason(playerUuid));

        // Verify a .corrupted backup was created — the evidence survives.
        try (var stream = Files.list(bankDir)) {
            boolean hasBackup = stream.anyMatch(p -> p.getFileName().toString().contains(".corrupted."));
            assertTrue(hasBackup);
        }

        // Later writes must not overwrite the surviving evidence with empty state.
        assertThrows(IOException.class, () -> repo.save(playerUuid));
        // Routed transactions fail closed rather than initializing a fresh vault.
        var result = repo.transact(playerUuid, vault ->
                vault.deposit(0, 0, "minecraft:diamond", 1)
                        ? BankRepository.BankMutation.changed(true)
                        : BankRepository.BankMutation.unchanged(false));
        assertFalse(result.committed());
        assertNotNull(result.failureReason());
    }

    @Test
    @DisplayName("Future-schema bank vault fails closed instead of downgrading")
    void testFutureSchemaVaultBlocked(@TempDir Path tempDir) throws IOException {
        Path bankDir = tempDir.resolve("banks_future");
        BankRepository repo = new BankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();
        Files.writeString(bankDir.resolve(playerUuid + ".json"),
                "{\"schemaVersion\":999,\"data\":{\"playerUuid\":\"" + playerUuid + "\"}}");

        assertThrows(UnrecoverablePlayerDataException.class, () -> repo.getOrCreate(playerUuid));
        assertTrue(repo.isUnavailable(playerUuid));
        assertThrows(IOException.class, () -> repo.save(playerUuid));
    }

    @Test
    @DisplayName("Quarantined vault artifact alone blocks empty initialization")
    void testQuarantinedVaultArtifactBlocks(@TempDir Path tempDir) throws IOException {
        Path bankDir = tempDir.resolve("banks_quarantine");
        BankRepository repo = new BankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();
        Files.writeString(bankDir.resolve(playerUuid + ".json.corrupted.1"), "garbage");

        assertThrows(UnrecoverablePlayerDataException.class, () -> repo.getOrCreate(playerUuid));
        assertThrows(IOException.class, () -> repo.save(playerUuid));

        // Unload clears only the in-memory block; durable evidence re-blocks on next access.
        repo.unload(playerUuid);
        assertThrows(UnrecoverablePlayerDataException.class, () -> repo.getOrCreate(playerUuid));
    }

    @Test
    @DisplayName("unlockBankTab replay with the same request id never unlocks twice")
    void testUnlockReplayIsIdempotent(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_replay"));
        var banker = new com.storynpcs.domain.role.banker.BankerRole("Replay Bank");
        banker.setMaxTabs(4);
        banker.setTabUpgradeCost(0);

        assertTrue(service.unlockBankTab(playerUuid, repo, banker, requestId));
        assertEquals(2, repo.getOrCreate(playerUuid).getUnlockedTabs());
        assertEquals(DurableOperationJournal.State.COMMITTED,
                repo.operationJournal().read(requestId).state());
        assertNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId),
                "A completed unlock cleans its durable marker");

        // Replay of the committed request id must not grant another tab.
        assertTrue(service.unlockBankTab(playerUuid, repo, banker, requestId));
        assertEquals(2, repo.getOrCreate(playerUuid).getUnlockedTabs());
    }

    @Test
    @DisplayName("Crash before the vault commit aborts the prepared unlock — nothing was paid or unlocked")
    void testPreparedUnlockWithoutMarkerIsAborted(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_abort"));
        var intent = new BankOperationIntent(
                playerUuid, BankOperationIntent.ACTION_UNLOCK_TAB, 1, -1, "minecraft:emerald", null,
                0, 0, 0, false, repo.getOrCreate(playerUuid).getRevision());
        repo.operationJournal().begin(requestId, "bank.unlock_tab", playerUuid.toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.ABORTED,
                repo.operationJournal().read(requestId).state());
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());
    }

    @Test
    @DisplayName("Crash between vault commit and journal commit finalizes the free unlock once")
    void testPreparedUnlockWithMarkerFinalizes(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_finalize"));
        var intent = new BankOperationIntent(
                playerUuid, BankOperationIntent.ACTION_UNLOCK_TAB, 1, -1, "minecraft:emerald", null,
                0, 0, 0, false, repo.getOrCreate(playerUuid).getRevision());
        String intentJson = com.storynpcs.domain.role.RoleSerde.toJson(intent);
        repo.operationJournal().begin(requestId, "bank.unlock_tab", playerUuid.toString(), intentJson);

        // Simulate the vault leg landing without the journal commit.
        var applied = repo.transact(playerUuid, vault -> {
            vault.markOperation(requestId, "bank.unlock_tab", intentJson);
            vault.setUnlockedTabs(2);
            return BankRepository.BankMutation.changed(true);
        });
        assertTrue(applied.committed());

        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.COMMITTED,
                repo.operationJournal().read(requestId).state());
        assertEquals(2, repo.getOrCreate(playerUuid).getUnlockedTabs());
        assertNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId));
    }

    @Test
    @DisplayName("Committed unlock with a surviving marker only needs marker cleanup")
    void testCommittedUnlockMarkerCleanupOnRecovery(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_cleanup"));
        var intent = new BankOperationIntent(
                playerUuid, BankOperationIntent.ACTION_UNLOCK_TAB, 1, -1, "minecraft:emerald", null,
                0, 0, 0, false, 0L);
        String intentJson = com.storynpcs.domain.role.RoleSerde.toJson(intent);
        repo.operationJournal().begin(requestId, "bank.unlock_tab", playerUuid.toString(), intentJson);
        var applied = repo.transact(playerUuid, vault -> {
            vault.markOperation(requestId, "bank.unlock_tab", intentJson);
            vault.setUnlockedTabs(2);
            vault.markOperationInventoryApplied(requestId);
            return BankRepository.BankMutation.changed(true);
        });
        assertTrue(applied.committed());
        repo.operationJournal().commit(requestId, "APPLIED", "2");

        // Crash window: journal committed, marker cleanup interrupted.
        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
        assertNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId));
        assertEquals(2, repo.getOrCreate(playerUuid).getUnlockedTabs());
    }

    @Test
    @DisplayName("Paid unlock owed to an offline player stays pending — never double-charges, never free-grants")
    void testPaidUnlockWithDeferredPaymentStaysPending(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_paid"));
        var intent = new BankOperationIntent(
                playerUuid, BankOperationIntent.ACTION_UNLOCK_TAB, 1, -1, "minecraft:emerald", null,
                5, 10, 5, true, repo.getOrCreate(playerUuid).getRevision());
        String intentJson = com.storynpcs.domain.role.RoleSerde.toJson(intent);
        repo.operationJournal().begin(requestId, "bank.unlock_tab", playerUuid.toString(), intentJson);
        var applied = repo.transact(playerUuid, vault -> {
            vault.markOperation(requestId, "bank.unlock_tab", intentJson);
            vault.setUnlockedTabs(2);
            return BankRepository.BankMutation.changed(true);
        });
        assertTrue(applied.committed());
        repo.operationJournal().commit(requestId, "APPLIED", "2");

        // No live player: the owed payment cannot be verified, so the operation
        // must remain pending rather than guess at inventory state.
        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        assertEquals(2, repo.getOrCreate(playerUuid).getUnlockedTabs());
        assertNotNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId),
                "The unpaid marker must survive until payment is proven");
    }

    @Test
    @DisplayName("Malformed unlock marker intent stays pending for manual recovery")
    void testMalformedUnlockMarkerStaysPending(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_malformed"));
        var applied = repo.transact(playerUuid, vault -> {
            vault.markOperation(requestId, "bank.unlock_tab", "not-json");
            vault.setUnlockedTabs(2);
            return BankRepository.BankMutation.changed(true);
        });
        assertTrue(applied.committed());

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        assertNotNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId));
    }

    @Test
    @DisplayName("Unlock request id bound to a different player is refused")
    void testUnlockRejectsForeignRequestId(@TempDir Path tempDir) throws IOException {
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_foreign"));
        repo.operationJournal().begin(requestId, "bank.unlock_tab", owner.toString(), "{}");

        var banker = new com.storynpcs.domain.role.banker.BankerRole("Foreign Bank");
        banker.setTabUpgradeCost(0);
        assertFalse(service.unlockBankTab(attacker, repo, banker, requestId));
        assertEquals(1, repo.getOrCreate(attacker).getUnlockedTabs());
        // Owner-scoped recovery still arbitrates the record.
        service.recoverBankOperations(owner, repo);
        assertEquals(1, repo.getOrCreate(owner).getUnlockedTabs());
    }

    @Test
    @DisplayName("Unlock vault-commit failure aborts the journal and never retries the grant")
    void testUnlockVaultCommitFailureRecoversCleanly(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        FailingBankRepository repo = new FailingBankRepository(tempDir.resolve("banks_unlock_fail"));
        var banker = new com.storynpcs.domain.role.banker.BankerRole("Fail Bank");
        banker.setMaxTabs(4);
        banker.setTabUpgradeCost(0);
        repo.setFailSaves(true);

        assertFalse(service.unlockBankTab(playerUuid, repo, banker, requestId));
        // The vault commit provably never landed, so the journal aborts immediately.
        assertEquals(DurableOperationJournal.State.ABORTED,
                repo.operationJournal().read(requestId).state());
        assertEquals("VAULT_COMMIT_FAILED", repo.operationJournal().read(requestId).outcomeCode());
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());

        // An aborted request id stays terminal — a replay cannot grant the tab.
        repo.setFailSaves(false);
        assertFalse(service.unlockBankTab(playerUuid, repo, banker, requestId));
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());
        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
    }

    @Test
    @DisplayName("BankVault enforces [0, 53] slot bounds and clamps integer additions against overflow")
    void testSlotBoundsAndOverflowClamping() {
        BankVault vault = new BankVault(UUID.randomUUID());

        // Slot bounds
        assertFalse(vault.deposit(0, -1, "minecraft:dirt", 1));
        assertFalse(vault.deposit(0, 54, "minecraft:dirt", 1));
        assertTrue(vault.withdraw(0, -1, 1).isEmpty());
        assertTrue(vault.withdraw(0, 54, 1).isEmpty());

        // Valid slot 0 deposit
        assertTrue(vault.deposit(0, 0, "minecraft:dirt", 1_000_000));
        assertEquals(1_000_000, vault.getTabItems(0).get(0).getCount());

        // Deposit amount causing overflow
        assertTrue(vault.deposit(0, 0, "minecraft:dirt", Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, vault.getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("BankVault prevents concurrent race double-withdrawal dupes")
    void testConcurrentWithdrawals() throws InterruptedException {
        BankVault vault = new BankVault(UUID.randomUUID());
        vault.deposit(0, 0, "minecraft:diamond", 100);

        int threadCount = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.atomic.AtomicInteger totalWithdrawn = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    var itemOpt = vault.withdraw(0, 0, 10);
                    itemOpt.ifPresent(item -> totalWithdrawn.addAndGet(item.getCount()));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(100, totalWithdrawn.get(), "Total withdrawn across threads must match initial balance without duplication");
        assertTrue(vault.getTabItems(0).isEmpty(), "Vault must be empty after full withdrawal");
    }

    @Test
    @DisplayName("BankRepository unloads cached player vault safely")
    void testUnloadPlayer() {
        UUID playerUuid = UUID.randomUUID();
        bankRepo.getOrCreate(playerUuid);
        bankRepo.unload(playerUuid);
        // Should not throw and reload freshly if asked
        assertNotNull(bankRepo.getOrCreate(playerUuid));
    }

    @Test
    @DisplayName("depositToBankAuto merges identical stacks, picks first free slot, and rejects locked tabs")
    void testDepositToBankAuto() {
        UUID playerUuid = UUID.randomUUID();

        int slot1 = service.depositToBankAuto(playerUuid, bankRepo, 0, "minecraft:diamond", 10, null);
        assertEquals(0, slot1, "First deposit should claim slot 0");

        // Same item+tag merges onto the existing stack
        int merged = service.depositToBankAuto(playerUuid, bankRepo, 0, "minecraft:diamond", 5, null);
        assertEquals(0, merged);
        assertEquals(15, bankRepo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());

        // Different item claims the next free slot
        int slot2 = service.depositToBankAuto(playerUuid, bankRepo, 0, "minecraft:bread", 3, null);
        assertEquals(1, slot2);

        // Locked tab (only tab 0 unlocked by default) is rejected
        assertEquals(-1, service.depositToBankAuto(playerUuid, bankRepo, 1, "minecraft:diamond", 1, null));
        // Invalid inputs are rejected
        assertEquals(-1, service.depositToBankAuto(playerUuid, bankRepo, 0, "minecraft:diamond", 0, null));
        assertEquals(-1, service.depositToBankAuto(playerUuid, bankRepo, 0, "", 1, null));

        // Deposit events published, vault persisted to disk
        assertEquals(3, bankEvents.stream().filter(e -> e.type() == BankTransactionEvent.Type.DEPOSIT).count());
        BankRepository reloaded = new BankRepository(banksDir);
        assertEquals(15, reloaded.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("unlockBankTab unlocks up to maxTabs and publishes UNLOCK_TAB events")
    void testUnlockBankTabFree() {
        UUID playerUuid = UUID.randomUUID();
        var banker = new com.storynpcs.domain.role.banker.BankerRole("Test Bank");
        banker.setMaxTabs(3);
        banker.setTabUpgradeCost(0); // free — no server inventory needed

        assertTrue(service.unlockBankTab(playerUuid, bankRepo, banker));
        assertEquals(2, bankRepo.getOrCreate(playerUuid).getUnlockedTabs());
        assertTrue(service.unlockBankTab(playerUuid, bankRepo, banker));
        assertEquals(3, bankRepo.getOrCreate(playerUuid).getUnlockedTabs());

        // Bounded by maxTabs
        assertFalse(service.unlockBankTab(playerUuid, bankRepo, banker));
        assertEquals(3, bankRepo.getOrCreate(playerUuid).getUnlockedTabs());

        assertEquals(2, bankEvents.stream().filter(e -> e.type() == BankTransactionEvent.Type.UNLOCK_TAB).count());

        // Paid unlock requires a live server inventory — must refuse without one
        var banker2 = new com.storynpcs.domain.role.banker.BankerRole("Paid Bank");
        banker2.setMaxTabs(4);
        banker2.setTabUpgradeCost(10);
        UUID other = UUID.randomUUID();
        assertFalse(service.unlockBankTab(other, bankRepo, banker2),
                "Cost>0 unlock must fail when no minecraftServer is bound");
        assertEquals(1, bankRepo.getOrCreate(other).getUnlockedTabs());
    }

    @Test
    @DisplayName("Reading unvalidated tab indexes does not persist junk keys into the vault file")
    void testInvalidTabReadDoesNotPersist() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        BankVault vault = bankRepo.getOrCreate(playerUuid);
        vault.deposit(0, 0, "minecraft:emerald", 10);

        // Simulate the forged-packet path: reads on arbitrary indexes before withdraw's
        // bounds check. These must leave the persisted map untouched.
        vault.getTabItems(Integer.MAX_VALUE);
        vault.getTabItems(-1);
        vault.getTabItems(999);
        bankRepo.save(playerUuid);

        BankRepository reloaded = new BankRepository(banksDir);
        BankVault restored = reloaded.getOrCreate(playerUuid);
        assertEquals(1, restored.getTabs().size(),
                "Junk tab indexes must not survive a save/reload cycle");
        assertTrue(restored.getTabs().containsKey(0));
        assertEquals(10, restored.getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Failed bank commits restore cached state, preserve disk, and publish no success event")
    void testFailedBankCommitRollsBackAllRoutedMutations(@TempDir Path tempDir) throws IOException {
        Path bankDir = tempDir.resolve("banks_failure");
        FailingBankRepository repo = new FailingBankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();

        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 10));
        int eventCountAfterSeed = bankEvents.size();
        repo.setFailSaves(true);

        assertFalse(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 5));
        assertEquals(10, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
        assertEquals(eventCountAfterSeed, bankEvents.size());

        assertEquals(-1, service.depositToBankAuto(playerUuid, repo, 0, "minecraft:emerald", 2, null));
        assertEquals(1, repo.getOrCreate(playerUuid).getTabItems(0).size());
        assertEquals(10, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
        assertEquals(eventCountAfterSeed, bankEvents.size());

        assertTrue(service.withdrawFromBank(playerUuid, repo, 0, 0, 4).isEmpty());
        assertEquals(10, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
        assertEquals(eventCountAfterSeed, bankEvents.size());

        var banker = new com.storynpcs.domain.role.banker.BankerRole("Failure Bank");
        banker.setMaxTabs(2);
        banker.setTabUpgradeCost(0);
        assertFalse(service.unlockBankTab(playerUuid, repo, banker));
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());
        assertEquals(eventCountAfterSeed, bankEvents.size());

        BankRepository reloaded = new BankRepository(bankDir);
        BankVault restored = reloaded.getOrCreate(playerUuid);
        assertEquals(10, restored.getTabItems(0).get(0).getCount(),
                "A failed transaction must not overwrite the last durable bank state");
        assertEquals(1, restored.getUnlockedTabs());
        assertEquals(1, repo.getOrCreate(playerUuid).getRevision(),
                "Failed cached mutations must restore the durable revision too");
        assertEquals(1, restored.getRevision(),
                "Failed transactions must not advance the durable revision on disk");
    }

    @Test
    @DisplayName("Bank transaction restores the snapshot when the mutation itself throws")
    void testMutationExceptionRestoresSnapshot(@TempDir Path tempDir) {
        BankRepository repo = new BankRepository(tempDir.resolve("banks_mutation_exception"));
        UUID playerUuid = UUID.randomUUID();
        BankVault vault = repo.getOrCreate(playerUuid);
        assertTrue(vault.deposit(0, 0, "minecraft:diamond", 7));

        var result = repo.transact(playerUuid, candidate -> {
            candidate.deposit(0, 1, "minecraft:emerald", 3);
            throw new IllegalStateException("injected mutation failure");
        });

        assertFalse(result.committed());
        assertTrue(result.failureReason().contains("injected mutation failure"));
        assertEquals(1, vault.getTabItems(0).size());
        assertEquals(7, vault.getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Each durable bank write failure point restores the previous valid record")
    void testDurableFailurePointsPreservePreviousBankRecord(@TempDir Path tempDir) {
        UUID playerUuid = UUID.randomUUID();

        for (DurableJsonStore.FailurePoint failurePoint : DurableJsonStore.FailurePoint.values()) {
            Path bankDir = tempDir.resolve(failurePoint.name().toLowerCase());
            BankRepository seedRepo = new BankRepository(bankDir);
            assertTrue(service.depositToBank(playerUuid, seedRepo, 0, 0, "minecraft:diamond", 10));

            BankRepository faultedRepo = new BankRepository(bankDir, point -> {
                if (point == failurePoint) {
                    throw new IOException("injected " + failurePoint + " failure");
                }
            });
            assertFalse(service.depositToBank(playerUuid, faultedRepo, 0, 1, "minecraft:emerald", 2),
                    "failure point must reject the bank operation: " + failurePoint);

            BankRepository reloaded = new BankRepository(bankDir);
            BankVault restored = reloaded.getOrCreate(playerUuid);
            assertEquals(1, restored.getTabItems(0).size(), failurePoint.toString());
            assertEquals("minecraft:diamond", restored.getTabItems(0).get(0).getItemId(), failurePoint.toString());
            assertEquals(10, restored.getTabItems(0).get(0).getCount(), failurePoint.toString());
        }
    }

    @Test
    @DisplayName("Held-item deposit fails closed without a server and does not create journal state")
    void testHeldDepositRequiresServer(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_held"));

        var result = service.depositHeldToBank(playerUuid, repo, 0, requestId);

        assertEquals(com.storynpcs.service.BankDepositOperationResult.Outcome.REJECTED, result.outcome());
        assertEquals("SERVER_UNAVAILABLE", result.code());
        assertNull(repo.operationJournal().read(requestId));
    }

    @Test
    @DisplayName("Held-item deposit replay is classified before server or inventory access")
    void testHeldDepositReplayDoesNotRequireHeldStack(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_replay"));
        var journal = repo.operationJournal();

        // A committed replay verifies the vault still holds the deposited stack —
        // no server or held-item access is needed for that check.
        var intent = new BankOperationIntent(
                playerUuid, "bank.deposit_held", 0, 0, "minecraft:diamond", null,
                17, 0, 17, true, 0L);
        journal.begin(requestId, "bank.deposit_held", playerUuid.toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        journal.commit(requestId, "APPLIED", "0");
        var applied = repo.transact(playerUuid, vault ->
                vault.deposit(0, 0, "minecraft:diamond", 17)
                        ? BankRepository.BankMutation.changed(true)
                        : BankRepository.BankMutation.unchanged(false));
        assertTrue(applied.committed());

        var result = service.depositHeldToBank(playerUuid, repo, 0, requestId);

        assertEquals(com.storynpcs.service.BankDepositOperationResult.Outcome.REPLAYED, result.outcome());
        assertEquals(0, result.slot());
        assertEquals(0, bankEvents.size());
    }

    @Test
    @DisplayName("Login recovery aborts a deferred deposit whose vault marker never committed")
    void testDeferredDepositWithoutVaultMarkerIsAborted(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_pending"));
        var intent = new BankOperationIntent(
                playerUuid, "bank.deposit_held", 0, 0, "minecraft:diamond", null,
                3, 0, 3, true);
        repo.operationJournal().begin(requestId, "bank.deposit_held", playerUuid.toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.ABORTED,
                repo.operationJournal().read(requestId).state());
        assertEquals("BANK_NOT_COMMITTED",
                repo.operationJournal().read(requestId).outcomeCode());
    }

    @Test
    @DisplayName("Login recovery aborts a prepared withdrawal only while its exact source stack remains")
    void testPreparedWithdrawalWithUntouchedVaultStackIsAborted(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_pending_withdrawal"));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));

        var intent = new BankOperationIntent(
                playerUuid, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, repo.getOrCreate(playerUuid).getRevision());
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
        var recovered = repo.operationJournal().read(requestId);
        assertEquals(DurableOperationJournal.State.ABORTED, recovered.state());
        assertEquals("BANK_NOT_COMMITTED", recovered.outcomeCode());
        assertEquals(12, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Login recovery can reconcile an untouched legacy vault at revision zero")
    void testPreparedWithdrawalAtLegacyVaultRevisionZeroIsAborted(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_legacy_revision"));
        assertTrue(repo.getOrCreate(playerUuid).deposit(0, 0, "minecraft:diamond", 12));
        repo.save(playerUuid);
        assertEquals(0, repo.getOrCreate(playerUuid).getRevision());

        var intent = new BankOperationIntent(
                playerUuid, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, 0L);
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        assertEquals(0, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.ABORTED,
                repo.operationJournal().read(requestId).state());
    }

    @Test
    @DisplayName("Login recovery leaves a removed withdrawal prepared instead of guessing delivery")
    void testPreparedWithdrawalAfterVaultRemovalRequiresRecovery(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_removed_withdrawal"));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));

        var intent = new BankOperationIntent(
                playerUuid, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, repo.getOrCreate(playerUuid).getRevision());
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        var removed = repo.transact(playerUuid, vault -> {
            Optional<BankVault.VaultItem> withdrawn = vault.withdraw(0, 0, 12);
            return withdrawn.isPresent()
                    ? BankRepository.BankMutation.changed(withdrawn)
                    : BankRepository.BankMutation.unchanged(withdrawn);
        });
        assertTrue(removed.committed());

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        var recovered = repo.operationJournal().read(requestId);
        assertEquals(DurableOperationJournal.State.PREPARED, recovered.state());
        assertTrue(repo.getOrCreate(playerUuid).getTabItems(0).isEmpty());
    }

    @Test
    @DisplayName("Login recovery does not abort a prepared withdrawal after an ambiguous restock")
    void testPreparedWithdrawalAfterRestockRequiresRecovery(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_restocked_withdrawal"));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));

        var intent = new BankOperationIntent(
                playerUuid, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, repo.getOrCreate(playerUuid).getRevision());
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 3));

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        var recovered = repo.operationJournal().read(requestId);
        assertEquals(DurableOperationJournal.State.PREPARED, recovered.state());
        assertEquals(15, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Login recovery rejects an identical restock as proof of an untouched withdrawal")
    void testPreparedWithdrawalAfterIdenticalRestockRequiresRecovery(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_identical_restock"));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));

        var intent = new BankOperationIntent(
                playerUuid, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, repo.getOrCreate(playerUuid).getRevision());
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        var removed = repo.transact(playerUuid, vault -> {
            Optional<BankVault.VaultItem> withdrawn = vault.withdraw(0, 0, 12);
            return withdrawn.isPresent()
                    ? BankRepository.BankMutation.changed(withdrawn)
                    : BankRepository.BankMutation.unchanged(withdrawn);
        });
        assertTrue(removed.committed());
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.PREPARED,
                repo.operationJournal().read(requestId).state());
        assertEquals(12, repo.getOrCreate(playerUuid).getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Bank recovery filters compound withdrawal subjects to the requested player")
    void testWithdrawalRecoveryDoesNotTouchAnotherPlayer(@TempDir Path tempDir) throws IOException {
        UUID recoveringPlayer = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_cross_player"));
        assertTrue(service.depositToBank(otherPlayer, repo, 0, 0, "minecraft:diamond", 12));

        var intent = new BankOperationIntent(
                otherPlayer, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, repo.getOrCreate(otherPlayer).getRevision());
        repo.operationJournal().begin(requestId, "bank.withdraw",
                otherPlayer + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));

        assertEquals(0, service.recoverBankOperations(recoveringPlayer, repo));
        assertEquals(DurableOperationJournal.State.PREPARED,
                repo.operationJournal().read(requestId).state());
        assertEquals(12, repo.getOrCreate(otherPlayer).getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Malformed prepared withdrawal intent remains pending for explicit recovery")
    void testMalformedPreparedWithdrawalFailsClosed(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_malformed_withdrawal"));

        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12", "not-json");

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.PREPARED,
                repo.operationJournal().read(requestId).state());
    }

    @Test
    @DisplayName("Legacy prepared withdrawal without captured revision remains pending")
    void testLegacyPreparedWithdrawalWithoutRevisionFailsClosed(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_legacy_intent"));
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));
        String legacyIntent = "{\"playerUuid\":\"" + playerUuid
                + "\",\"action\":\"withdraw\",\"tab\":0,\"slot\":0,"
                + "\"itemId\":\"minecraft:diamond\",\"tag\":null,\"count\":12,"
                + "\"beforeCount\":12,\"expectedCount\":12}";
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12", legacyIntent);

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.PREPARED,
                repo.operationJournal().read(requestId).state());
    }

    @Test
    @DisplayName("Failed withdrawal recovery journal abort remains pending and is reported")
    void testPreparedWithdrawalAbortWriteFailureRemainsPending(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var failWrites = new java.util.concurrent.atomic.AtomicBoolean(false);
        BankRepository repo = new BankRepository(tempDir.resolve("banks_abort_failure"), point -> {
            if (failWrites.get()) throw new IOException("injected recovery abort write failure");
        });
        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 12));
        var intent = new BankOperationIntent(
                playerUuid, "withdraw", 0, 0, "minecraft:diamond", null,
                12, 12, 12, false, repo.getOrCreate(playerUuid).getRevision());
        repo.operationJournal().begin(requestId, "bank.withdraw",
                playerUuid + "|0|0|minecraft:diamond|12",
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        failWrites.set(true);

        assertEquals(1, service.recoverBankOperations(playerUuid, repo));
        assertEquals(DurableOperationJournal.State.PREPARED,
                repo.operationJournal().read(requestId).state());
    }

    @Test
    @DisplayName("Vault operation markers survive durable bank reload")
    void testVaultOperationMarkerReload(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Path bankDir = tempDir.resolve("banks_marker");
        BankRepository repo = new BankRepository(bankDir);
        assertTrue(repo.getOrCreate(playerUuid).markOperation(
                requestId, "bank.deposit_held", "validated-intent"));
        repo.save(playerUuid);

        BankRepository reloaded = new BankRepository(bankDir);
        var marker = reloaded.getOrCreate(playerUuid).getOperationMarker(requestId);
        assertNotNull(marker);
        assertEquals("validated-intent", marker.getIntent());
        assertFalse(marker.isInventoryApplied());
    }

    @Test
    @DisplayName("A vault commit binds the mutated instance — mid-flight cache eviction cannot drop the write")
    void testVaultCommitSurvivesMidMutationCacheEviction(@TempDir Path tempDir) {
        UUID playerUuid = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_evict_mid")) {
            @Override
            public <T> BankTransactionResult<T> transact(
                    UUID targetPlayerUuid,
                    java.util.function.Function<BankVault, BankMutation<T>> operation) {
                return super.transact(targetPlayerUuid, vault -> {
                    var mutation = operation.apply(vault);
                    unload(targetPlayerUuid); // evict the cached instance before the commit leg
                    return mutation;
                });
            }
        };

        assertTrue(service.depositToBank(playerUuid, repo, 0, 0, "minecraft:diamond", 7));

        repo.clearCache();
        BankVault reloaded = repo.getOrCreate(playerUuid);
        assertEquals(1, reloaded.getTabItems(0).size());
        assertEquals(7, reloaded.getTabItems(0).get(0).getCount());
    }

    @Test
    @DisplayName("Committed unlock replay cross-checks the vault — a restored backup cannot erase a paid unlock")
    void testCommittedUnlockReplayWithRolledBackVaultStaysPending(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_rollback"));
        var intent = new BankOperationIntent(
                playerUuid, BankOperationIntent.ACTION_UNLOCK_TAB, 1, -1, "minecraft:emerald", null,
                5, 10, 5, true, 0L);
        repo.operationJournal().begin(requestId, "bank.unlock_tab", playerUuid.toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        repo.operationJournal().commit(requestId, "APPLIED", "2");

        // Simulate a vault backup restore that erased both the unlock and its marker.
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());
        assertNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId));

        var banker = new com.storynpcs.domain.role.banker.BankerRole("Restored Bank");
        banker.setMaxTabs(4);
        banker.setTabUpgradeCost(0);
        assertFalse(service.unlockBankTab(playerUuid, repo, banker, requestId),
                "A committed unlock whose vault effect vanished must stay pending, not silently resolve");
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());
    }

    @Test
    @DisplayName("Committed unlock replay resolves when the vault still reflects the committed outcome")
    void testCommittedUnlockReplayWithIntactVaultResolves(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_unlock_intact"));
        var intent = new BankOperationIntent(
                playerUuid, BankOperationIntent.ACTION_UNLOCK_TAB, 1, -1, "minecraft:emerald", null,
                0, 0, 0, false, 0L);
        repo.operationJournal().begin(requestId, "bank.unlock_tab", playerUuid.toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        repo.operationJournal().commit(requestId, "APPLIED", "2");

        var applied = repo.transact(playerUuid, vault -> {
            vault.setUnlockedTabs(2);
            return BankRepository.BankMutation.changed(true);
        });
        assertTrue(applied.committed());
        assertNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId),
                "The marker was cleaned before the crash — the replay must still resolve");

        var banker = new com.storynpcs.domain.role.banker.BankerRole("Intact Bank");
        banker.setMaxTabs(4);
        banker.setTabUpgradeCost(0);
        assertTrue(service.unlockBankTab(playerUuid, repo, banker, requestId));
        assertEquals(2, repo.getOrCreate(playerUuid).getUnlockedTabs());
    }

    @Test
    @DisplayName("Committed held-deposit replay requires recovery when the vault stack was rolled back")
    void testCommittedDepositReplayWithRolledBackVaultRequiresRecovery(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_deposit_rollback"));
        var intent = new BankOperationIntent(
                playerUuid, "bank.deposit_held", 0, 0, "minecraft:diamond", null,
                5, 0, 5, true, 0L);
        repo.operationJournal().begin(requestId, "bank.deposit_held", playerUuid.toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(intent));
        repo.operationJournal().commit(requestId, "APPLIED", "0");

        // The vault was restored from a backup predating the deposit: no stack, no marker.
        assertTrue(repo.getOrCreate(playerUuid).getTabItems(0).isEmpty());
        assertNull(repo.getOrCreate(playerUuid).getOperationMarker(requestId));

        var result = service.depositHeldToBank(playerUuid, repo, 0, requestId);
        assertEquals(com.storynpcs.service.BankDepositOperationResult.Outcome.RECOVERY_REQUIRED,
                result.outcome());
        assertEquals("BANK_MARKER_MISSING", result.code());
    }

    @Test
    @DisplayName("Unlock vault leg refuses to overwrite a conflicting durable operation marker")
    void testUnlockRefusesConflictingVaultMarker(@TempDir Path tempDir) throws IOException {
        UUID playerUuid = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BankRepository repo = new BankRepository(tempDir.resolve("banks_marker_conflict"));

        // The journal record is gone but a vault marker for the same request id
        // committed under different terms — the grant must not overwrite it.
        var applied = repo.transact(playerUuid, vault -> {
            vault.markOperation(requestId, "bank.unlock_tab", "different-intent");
            return BankRepository.BankMutation.changed(true);
        });
        assertTrue(applied.committed());

        var banker = new com.storynpcs.domain.role.banker.BankerRole("Conflict Bank");
        banker.setMaxTabs(4);
        banker.setTabUpgradeCost(0);
        assertFalse(service.unlockBankTab(playerUuid, repo, banker, requestId),
                "A conflicting durable marker must block the grant");
        assertEquals(1, repo.getOrCreate(playerUuid).getUnlockedTabs());
        // The surviving marker is untouched — it still belongs to the other intent.
        assertEquals("different-intent",
                repo.getOrCreate(playerUuid).getOperationMarker(requestId).getIntent());
    }
}
