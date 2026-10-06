package com.storynpcs.runtime.session;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSessionRegistryTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final NamespacedId NPC = NamespacedId.of("storynpcs:merchant");

    @Test
    void roleSessionIsStableForTheSameNpcAndInvalidAfterReplacement() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        UUID first = registry.openRoleSession(PLAYER, "trade", NPC);
        assertNotNull(first);
        assertEquals(first, registry.openRoleSession(PLAYER, "trade", NPC));
        assertTrue(registry.isRoleSession(PLAYER, "trade", NPC, first));

        UUID second = registry.openRoleSession(PLAYER, "bank", NPC);
        assertNotEquals(first, second);
        assertFalse(registry.isRoleSession(PLAYER, "trade", NPC, first));
        assertTrue(registry.isRoleSession(PLAYER, "bank", NPC, second));
    }

    @Test
    void requestIdsAreReplaySafeAndPlayerCleanupExpiresThem() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        UUID request = UUID.randomUUID();
        assertTrue(registry.acceptRequest(PLAYER, request));
        assertFalse(registry.acceptRequest(PLAYER, request));
        assertEquals(RuntimeSessionRegistry.RequestAdmission.DUPLICATE,
                registry.admitRequest(PLAYER, request));
        UUID durableRetry = UUID.randomUUID();
        assertEquals(RuntimeSessionRegistry.RequestAdmission.NEW,
                registry.admitRequest(PLAYER, durableRetry));
        assertEquals(RuntimeSessionRegistry.RequestAdmission.DUPLICATE,
                registry.admitRequest(PLAYER, durableRetry));
        registry.clearPlayer(PLAYER);
        assertTrue(registry.acceptRequest(PLAYER, request));
    }

    @Test
    void entitySessionsAreBoundToKindAndEntityAndExpireWithPlayer() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        UUID first = registry.openEntitySession(PLAYER, "nbt_book", 42);
        assertNotNull(first);
        assertEquals(first, registry.openEntitySession(PLAYER, "nbt_book", 42));
        assertTrue(registry.isEntitySession(PLAYER, "nbt_book", 42, first));
        // A different entity gets a new token and invalidates the old one.
        UUID second = registry.openEntitySession(PLAYER, "nbt_book", 77);
        assertNotEquals(first, second);
        assertFalse(registry.isEntitySession(PLAYER, "nbt_book", 42, first));
        assertTrue(registry.isEntitySession(PLAYER, "nbt_book", 77, second));
        // Wrong kind never validates.
        assertFalse(registry.isEntitySession(PLAYER, "mounter", 77, second));
        registry.clearPlayer(PLAYER);
        assertFalse(registry.isEntitySession(PLAYER, "nbt_book", 77, second));
    }

    @Test
    void closeEntitySessionInvalidatesOnlyTheMatchingSession() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        UUID session = registry.openEntitySession(PLAYER, "nbt_book", 42);
        // A stale close (wrong session id) must not clobber the live session.
        registry.closeEntitySession(PLAYER, "nbt_book", UUID.randomUUID());
        assertTrue(registry.isEntitySession(PLAYER, "nbt_book", 42, session));
        // A stale kind likewise cannot close it.
        registry.closeEntitySession(PLAYER, "mounter", session);
        assertTrue(registry.isEntitySession(PLAYER, "nbt_book", 42, session));
        registry.closeEntitySession(PLAYER, "nbt_book", session);
        assertFalse(registry.isEntitySession(PLAYER, "nbt_book", 42, session));
        // Reopening after close issues a fresh token.
        UUID next = registry.openEntitySession(PLAYER, "nbt_book", 42);
        assertNotEquals(session, next);
    }

    @Test
    void panelSessionsRotateLikeRoleSessionsAndAreKindScoped() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        UUID questPanel = registry.openPanelSession(PLAYER, "quest_log");
        assertNotNull(questPanel);
        assertTrue(registry.isPanelSession(PLAYER, "quest_log", questPanel));

        // Same panel → same token; a different panel rotates it.
        assertEquals(questPanel, registry.openPanelSession(PLAYER, "quest_log"));
        UUID mailPanel = registry.openPanelSession(PLAYER, "mail");
        assertNotEquals(questPanel, mailPanel);
        assertFalse(registry.isPanelSession(PLAYER, "quest_log", questPanel));
        assertTrue(registry.isPanelSession(PLAYER, "mail", mailPanel));

        // A role session opened later evicts the panel session entirely.
        UUID role = registry.openRoleSession(PLAYER, "trade", NPC);
        assertFalse(registry.isPanelSession(PLAYER, "mail", mailPanel));
        assertTrue(registry.isRoleSession(PLAYER, "trade", NPC, role));
    }

    @Test
    void toolConfirmationsRequireAMatchingSecondClickInsideTheTtl() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        long now = System.currentTimeMillis();
        registry.armToolConfirmation(PLAYER, "remover", 42, 10_000);

        // Same tool + same entity inside the window → confirmed, exactly once.
        var confirmed = registry.consumeToolConfirmation(PLAYER, "remover", 42, now + 1);
        assertNotNull(confirmed);
        assertEquals(42, confirmed.targetEntityId());
        // Second consume — the confirmation was spent.
        assertNull(registry.consumeToolConfirmation(PLAYER, "remover", 42, now + 2));
    }

    @Test
    void toolConfirmationMismatchesAreConsumedAndExpiryFailsClosed() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        long now = System.currentTimeMillis();

        // A different entity must not inherit the pending confirmation —
        // and the mismatching click spends it (fail-closed).
        registry.armToolConfirmation(PLAYER, "remover", 42, 10_000);
        assertNull(registry.consumeToolConfirmation(PLAYER, "remover", 99, now + 1));
        assertNull(registry.consumeToolConfirmation(PLAYER, "remover", 42, now + 1));

        // A different tool likewise never confirms a remover arm.
        registry.armToolConfirmation(PLAYER, "remover", 42, 10_000);
        assertNull(registry.consumeToolConfirmation(PLAYER, "soulstone", 42, now + 1));

        // An expired arm cannot execute.
        registry.armToolConfirmation(PLAYER, "remover", 42, 10_000);
        assertNull(registry.consumeToolConfirmation(PLAYER, "remover", 42, now + 20_000));

        // clearPlayer drops pending confirmations — a re-login can't inherit one.
        registry.armToolConfirmation(PLAYER, "remover", 42, 10_000);
        registry.clearPlayer(PLAYER);
        assertNull(registry.consumeToolConfirmation(PLAYER, "remover", 42, now + 1));
    }

    @Test
    void teleportSelectionsArePerPlayerAndRecoverOnClear() {
        RuntimeSessionRegistry registry = new RuntimeSessionRegistry();
        UUID other = UUID.randomUUID();
        registry.selectTeleportEntity(PLAYER, 7);
        assertEquals(7, registry.selectedTeleportEntity(PLAYER));
        assertNull(registry.selectedTeleportEntity(other), "selections never leak across players");
        registry.clearSelectedTeleportEntity(PLAYER);
        assertNull(registry.selectedTeleportEntity(PLAYER));
        registry.selectTeleportEntity(PLAYER, 9);
        registry.clearPlayer(PLAYER);
        assertNull(registry.selectedTeleportEntity(PLAYER));
    }
}
