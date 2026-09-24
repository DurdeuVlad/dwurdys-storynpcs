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
}
