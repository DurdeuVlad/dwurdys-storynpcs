package com.storynpcs.domain.role.banker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BankVaultTest {

    @Test
    @DisplayName("getTabItems on invalid or unused indexes never materializes map entries")
    void testGetTabItemsDoesNotPolluteTabs() {
        var vault = new BankVault(UUID.randomUUID());
        int keysBefore = vault.getTabs().size();

        for (int badIndex : new int[]{-1, 1, 999, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertTrue(vault.getTabItems(badIndex).isEmpty(),
                    "tab " + badIndex + " should read empty");
        }
        assertEquals(keysBefore, vault.getTabs().size(),
                "Reads must not create tab entries — client-supplied indexes could otherwise "
                        + "pollute the persisted vault map");
    }

    @Test
    @DisplayName("getTabItems returns a read-only view")
    void testGetTabItemsIsUnmodifiable() {
        var vault = new BankVault(UUID.randomUUID());
        vault.deposit(0, 0, "minecraft:diamond", 5);
        var items = vault.getTabItems(0);
        assertEquals(1, items.size());
        assertThrows(UnsupportedOperationException.class,
                () -> items.add(new BankVault.VaultItem(1, "minecraft:dirt", 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> vault.getTabItems(999).add(new BankVault.VaultItem(0, "minecraft:dirt", 1)));
    }

    @Test
    @DisplayName("Deposit and withdraw still work on valid unlocked tabs")
    void testDepositWithdrawUnchanged() {
        var vault = new BankVault(UUID.randomUUID());
        assertTrue(vault.deposit(0, 3, "minecraft:diamond", 10));
        assertEquals(1, vault.getTabItems(0).size());
        assertEquals(10, vault.getTabItems(0).get(0).getCount());

        var taken = vault.withdraw(0, 3, 4);
        assertTrue(taken.isPresent());
        assertEquals(4, taken.get().getCount());
        assertEquals(6, vault.getTabItems(0).get(0).getCount());

        var rest = vault.withdraw(0, 3, 6);
        assertTrue(rest.isPresent());
        assertTrue(vault.getTabItems(0).isEmpty());
    }

    @Test
    @DisplayName("Mutations reject out-of-range tabs without creating entries")
    void testMutationsRejectInvalidTabs() {
        var vault = new BankVault(UUID.randomUUID()); // 1 unlocked tab: only index 0 valid
        int keysBefore = vault.getTabs().size();

        assertFalse(vault.deposit(7, 0, "minecraft:diamond", 5));
        assertEquals(-1, vault.depositAuto(7, "minecraft:diamond", 5, null));
        assertTrue(vault.withdraw(7, 0, 1).isEmpty());
        assertFalse(vault.deposit(-1, 0, "minecraft:diamond", 5));

        assertEquals(keysBefore, vault.getTabs().size(),
                "Rejected mutations must not leave stray tab entries behind");
    }

    @Test
    @DisplayName("depositAuto merges into existing stacks and uses the lowest free slot")
    void testDepositAuto() {
        var vault = new BankVault(UUID.randomUUID());
        int first = vault.depositAuto(0, "minecraft:bread", 3, null);
        assertEquals(0, first);
        int merged = vault.depositAuto(0, "minecraft:bread", 2, null);
        assertEquals(0, merged);
        assertEquals(5, vault.getTabItems(0).get(0).getCount());
        int other = vault.depositAuto(0, "minecraft:dirt", 1, null);
        assertEquals(1, other);
    }

    @Test
    @DisplayName("Operation markers survive detached snapshots and are removed idempotently")
    void testOperationMarkerSnapshotAndCleanup() {
        UUID operationId = UUID.randomUUID();
        var vault = new BankVault(UUID.randomUUID());
        assertTrue(vault.markOperation(operationId, "bank.deposit_held", "intent"));
        assertTrue(vault.markOperation(operationId, "bank.deposit_held", "intent"));
        assertFalse(vault.markOperation(operationId, "bank.deposit_held", "different"));

        var restored = vault.copy();
        assertNotNull(restored.getOperationMarker(operationId));
        assertEquals("intent", restored.getOperationMarker(operationId).getIntent());
        assertFalse(restored.getOperationMarker(operationId).isInventoryApplied());
        assertTrue(restored.markOperationInventoryApplied(operationId));
        assertTrue(restored.copy().getOperationMarker(operationId).isInventoryApplied());
        assertTrue(restored.removeOperationMarker(operationId));
        assertFalse(restored.removeOperationMarker(operationId));
    }

    @Test
    @DisplayName("setUnlockedTabs rejects a tab count above the target's six-tab ceiling")
    void testSetUnlockedTabsRejectsAboveMaxTabs() {
        var vault = new BankVault(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> vault.setUnlockedTabs(BankerRole.MAX_TABS + 1));
        assertDoesNotThrow(() -> vault.setUnlockedTabs(BankerRole.MAX_TABS));
        assertEquals(BankerRole.MAX_TABS, vault.getUnlockedTabs());
    }

    @Test
    @DisplayName("setUnlockedTabs rejects a negative tab count")
    void testSetUnlockedTabsRejectsNegative() {
        var vault = new BankVault(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> vault.setUnlockedTabs(-1));
        assertDoesNotThrow(() -> vault.setUnlockedTabs(0));
    }
}
