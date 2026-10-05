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
}
