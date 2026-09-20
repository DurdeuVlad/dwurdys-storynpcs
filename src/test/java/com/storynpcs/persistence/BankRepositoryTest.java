package com.storynpcs.persistence;

import com.storynpcs.api.event.BankTransactionEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BankRepositoryTest {

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
    }

    @Test
    @DisplayName("Corrupted bank vault file creates backup on load")
    void testCorruptedBankBackup(@TempDir Path tempDir) throws IOException {
        Path bankDir = tempDir.resolve("banks_corrupt");
        BankRepository repo = new BankRepository(bankDir);
        UUID playerUuid = UUID.randomUUID();

        Path targetFile = bankDir.resolve(playerUuid + ".json");
        Files.writeString(targetFile, "{ broken json content");

        BankVault vault = repo.getOrCreate(playerUuid);
        assertNotNull(vault);

        // Verify a .corrupted backup was created
        try (var stream = Files.list(bankDir)) {
            boolean hasBackup = stream.anyMatch(p -> p.getFileName().toString().contains(".corrupted."));
            assertTrue(hasBackup);
        }
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
}