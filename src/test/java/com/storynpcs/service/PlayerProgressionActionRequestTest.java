package com.storynpcs.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProgressionActionRequestTest {

    private static PlayerProgressionActionRequest request(String actorType, UUID actorId, UUID playerUuid, int permissionLevel) {
        return new PlayerProgressionActionRequest("mail.read", actorType, actorId, playerUuid, UUID.randomUUID(), permissionLevel);
    }

    @Test
    void rejectsBlankOperation() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                new PlayerProgressionActionRequest("", "system", player, player, UUID.randomUUID(), -1));
        assertThrows(IllegalArgumentException.class, () ->
                new PlayerProgressionActionRequest(null, "system", player, player, UUID.randomUUID(), -1));
    }

    @Test
    void rejectsUnregisteredActorType() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> request("client", player, player, -1));
    }

    @Test
    void rejectsNullPlayerUuidAndRequestId() {
        UUID actor = UUID.randomUUID();
        assertThrows(NullPointerException.class, () ->
                new PlayerProgressionActionRequest("mail.read", "system", actor, null, UUID.randomUUID(), -1));
        assertThrows(NullPointerException.class, () ->
                new PlayerProgressionActionRequest("mail.read", "system", actor, actor, null, -1));
    }

    @Test
    void rejectsPermissionLevelBelowNegativeOne() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> request("command", player, player, -2));
    }

    @Test
    void acceptsEveryRegisteredActorType() {
        UUID player = UUID.randomUUID();
        for (String actor : new String[]{"command", "dialogue", "player", "script", "system"}) {
            assertDoesNotThrow(() -> request(actor, player, player, -1));
        }
    }
}
