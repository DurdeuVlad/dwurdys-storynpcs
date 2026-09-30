package com.storynpcs.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical request for bank vault operations on a single player subject.
 * Every adapter — commands, GUI/network packets, dialogue actions, API, or
 * scripts — constructs this request; authorization resolves from actor type,
 * subject identity, capability, and operator proof before the journaled
 * mutation runs. The request id binds to the durable operation journal so
 * duplicates classify as replays after the authorization gate.
 */
public record BankOperationRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        com.storynpcs.domain.common.NamespacedId bankerId,
        Action action,
        int tab,
        int slot,
        String itemId,
        int amount,
        UUID requestId,
        int permissionLevel,
        UUID vaultOwnerUuid) {

    /**
     * Compatibility constructor: self-scoped vault (subject owns the vault).
     */
    public BankOperationRequest(
            String actorType,
            UUID actorId,
            UUID playerUuid,
            com.storynpcs.domain.common.NamespacedId bankerId,
            Action action,
            int tab,
            int slot,
            String itemId,
            int amount,
            UUID requestId,
            int permissionLevel) {
        this(actorType, actorId, playerUuid, bankerId, action, tab, slot, itemId,
                amount, requestId, permissionLevel, null);
    }

    public static final int MAX_AMOUNT = 1024;
    public static final int MAX_TAB = 63;
    public static final int MAX_SLOT = 53;
    private static final int MAX_ITEM_ID = 256;

    private static final Set<String> ACTOR_TYPES = Set.of(
            "command", "dialogue", "player", "script", "system");

    public enum Action {
        DEPOSIT, DEPOSIT_AUTO, DEPOSIT_HELD,
        WITHDRAW, WITHDRAW_STACK,
        /** Vault-only whole-stack removal — the non-delivering removal leg. */
        REMOVE_STACK,
        UNLOCK_TAB
    }

    /**
     * @param requestId durable replay binding; a {@code null} id is minted
     *                  fresh downstream so the operation is always journaled.
     *                  Journaled actions (DEPOSIT_HELD, UNLOCK_TAB, WITHDRAW_STACK)
     *                  never run an untracked path.
     */
    public BankOperationRequest {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(action, "action");
        actorType = actorType.trim().toLowerCase(Locale.ROOT);
        if (actorType.isEmpty() || !ACTOR_TYPES.contains(actorType)) {
            throw new IllegalArgumentException("Unsupported actor type: " + actorType);
        }
        if (!isValidTab(tab)) throw new IllegalArgumentException("Invalid bank tab: " + tab);
        switch (action) {
            case DEPOSIT -> {
                requireItem(itemId);
                requireAmount(amount);
                if (!isValidSlot(slot)) throw new IllegalArgumentException("Invalid bank slot: " + slot);
            }
            case DEPOSIT_AUTO -> {
                requireItem(itemId);
                requireAmount(amount);
            }
            case WITHDRAW -> {
                if (!isValidSlot(slot)) throw new IllegalArgumentException("Invalid bank slot: " + slot);
                requireAmount(amount);
            }
            case WITHDRAW_STACK, REMOVE_STACK -> {
                if (!isValidSlot(slot)) throw new IllegalArgumentException("Invalid bank slot: " + slot);
            }
            case DEPOSIT_HELD, UNLOCK_TAB -> {
                // Subject-scoped operations: vault contents resolve durably at execution.
            }
        }
    }

    /**
     * Client-input bound check shared by the constructor and network adapters —
     * adapters pre-validate so a malformed packet fails closed instead of
     * throwing out of the request contract onto the server thread.
     */
    public static boolean isValidTab(int tab) {
        return tab >= 0 && tab <= MAX_TAB;
    }

    public static boolean isValidSlot(int slot) {
        return slot >= 0 && slot <= MAX_SLOT;
    }

    private static void requireItem(String itemId) {
        if (itemId == null || itemId.isBlank() || itemId.length() > MAX_ITEM_ID) {
            throw new IllegalArgumentException("Bank itemId must be 1.." + MAX_ITEM_ID + " characters");
        }
    }

    private static void requireAmount(int amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("Invalid bank amount: " + amount);
        }
    }

    public String operation() {
        return switch (action) {
            case DEPOSIT -> "bank.deposit";
            case DEPOSIT_AUTO -> "bank.deposit.auto";
            case DEPOSIT_HELD -> "bank.deposit.held";
            case WITHDRAW -> "bank.withdraw";
            case WITHDRAW_STACK -> "bank.withdraw.stack";
            case REMOVE_STACK -> "bank.withdraw.stack.remove";
            case UNLOCK_TAB -> "bank.unlock";
        };
    }

    public String capability() {
        return switch (action) {
            case DEPOSIT, DEPOSIT_AUTO, DEPOSIT_HELD -> "bank.deposit";
            case WITHDRAW, WITHDRAW_STACK, REMOVE_STACK -> "bank.withdraw";
            case UNLOCK_TAB -> "bank.unlock";
        };
    }

    /**
     * The durable vault identity this operation applies to. {@code playerUuid}
     * is always the acting subject (items are taken from and delivered to that
     * player's inventory); {@code vaultOwnerUuid} selects the vault. A null
     * owner resolves to the actor's own vault — the historical self-scope.
     */
    public UUID resolvedVaultOwner() {
        return vaultOwnerUuid != null ? vaultOwnerUuid : playerUuid;
    }
}
