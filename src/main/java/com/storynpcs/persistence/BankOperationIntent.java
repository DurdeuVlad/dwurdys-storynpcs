package com.storynpcs.persistence;

import com.storynpcs.domain.role.banker.BankVault;

import java.util.UUID;

/**
 * Bounded intent needed to reconcile a bank operation after a crash. The
 * expected count is captured from the planned vault state, and the durable
 * vault revision lets withdrawal recovery distinguish the original source
 * stack from an identical stack restocked later.
 */
public record BankOperationIntent(
        UUID playerUuid,
        String action,
        int tab,
        int slot,
        String itemId,
        String tag,
        int count,
        int beforeCount,
        int expectedCount,
        boolean inventoryDeferred,
        Long vaultRevision
) {
    public BankOperationIntent(UUID playerUuid, String action, int tab, int slot,
                               String itemId, String tag, int count,
                               int beforeCount, int expectedCount) {
        this(playerUuid, action, tab, slot, itemId, tag, count,
                beforeCount, expectedCount, false, null);
    }

    public BankOperationIntent(UUID playerUuid, String action, int tab, int slot,
                               String itemId, String tag, int count,
                               int beforeCount, int expectedCount,
                               boolean inventoryDeferred) {
        this(playerUuid, action, tab, slot, itemId, tag, count,
                beforeCount, expectedCount, inventoryDeferred, null);
    }

    /** Tab-unlock intents carry the emerald cost, not a vault slot stack. */
    public static final String ACTION_UNLOCK_TAB = "unlock_tab";

    public BankOperationIntent {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid cannot be null");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action cannot be blank");
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId cannot be blank");
        boolean unlock = ACTION_UNLOCK_TAB.equals(action);
        int minSlot = unlock ? -1 : 0;
        int minCount = unlock ? 0 : 1;
        int minExpected = unlock ? 0 : 1;
        if (tab < 0 || slot < minSlot || slot >= 54 || count < minCount || beforeCount < 0
                || expectedCount < minExpected || (vaultRevision != null && vaultRevision < 0)) {
            throw new IllegalArgumentException("invalid bank operation bounds");
        }
    }

    public boolean matches(BankVault.VaultItem item) {
        return item != null
                && item.getSlot() == slot
                && itemId.equals(item.getItemId())
                && java.util.Objects.equals(tag, item.getTag())
                && item.getCount() >= expectedCount;
    }
}
