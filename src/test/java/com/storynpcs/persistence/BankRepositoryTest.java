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

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        bankRepo = new BankRepository(tempDir.resolve("banks"));
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
}