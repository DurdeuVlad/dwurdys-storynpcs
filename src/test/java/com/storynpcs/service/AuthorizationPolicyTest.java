package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationPolicyTest {
    private static final NamespacedId NPC = NamespacedId.of("storynpcs:guard");

    private static MutationRequest request(String actor, String capability, int permissionLevel) {
        return new MutationRequest("npc.replace", actor, capability, NPC, 0L, UUID.randomUUID(), permissionLevel);
    }

    @Test
    void trustedAdaptersMustStillUseRegisteredCapabilities() {
        assertTrue(AuthorizationPolicy.evaluate(request("command", "npc.edit", -1)).allowed());
        assertEquals("UNKNOWN_CAPABILITY",
                AuthorizationPolicy.evaluate(request("command", "npc.arbitrary", -1)).code());
    }

    @Test
    void playerMutationNeedsFreshOperatorProof() {
        assertEquals("PERMISSION_DENIED",
                AuthorizationPolicy.evaluate(request("player:abc", "npc.edit", -1)).code());
        assertTrue(AuthorizationPolicy.evaluate(request("player:abc", "npc.edit", 2)).allowed());
    }

    @Test
    void scriptsCannotEscalateIntoDefinitionMutation() {
        AuthorizationDecision decision = AuthorizationPolicy.evaluate(request("script", "npc.edit", -1));
        assertFalse(decision.allowed());
        assertEquals("SCRIPT_CAPABILITY_REQUIRED", decision.code());
    }

    @Test
    void unknownActorsFailClosed() {
        assertEquals("UNKNOWN_ACTOR",
                AuthorizationPolicy.evaluate(request("client", "npc.edit", 4)).code());
    }

    @Test
    void transportCapabilitiesAreRegistered() {
        assertTrue(AuthorizationPolicy.evaluate(request("command", "transport.mutate", -1)).allowed());
        assertTrue(AuthorizationPolicy.evaluate(request("adapter", "transport.edit", -1)).allowed());
        assertTrue(AuthorizationPolicy.evaluate(request("system", "transport.delete", -1)).allowed());
    }

    @Test
    void unprovenPlayerTransportMutationIsDenied() {
        assertEquals("PERMISSION_DENIED",
                AuthorizationPolicy.evaluate(request("player:abc", "transport.mutate", -1)).code());
        assertTrue(AuthorizationPolicy.evaluate(request("player:abc", "transport.mutate", 2)).allowed());
    }

    @Test
    void scriptsCannotEscalateIntoTransportMutation() {
        AuthorizationDecision decision = AuthorizationPolicy.evaluate(request("script", "transport.mutate", -1));
        assertFalse(decision.allowed());
        assertEquals("SCRIPT_CAPABILITY_REQUIRED", decision.code());
    }

    private static PlayerProgressionActionRequest progressionAction(
            String actorType, UUID actorId, UUID playerUuid, int permissionLevel) {
        return new PlayerProgressionActionRequest("mail.read", actorType, actorId, playerUuid, UUID.randomUUID(), permissionLevel);
    }

    @Test
    void playerAndDialogueActorsMayOnlyActOnTheirOwnProgression() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(AuthorizationPolicy.evaluate(progressionAction("player", player, player, -1)).allowed());
        assertTrue(AuthorizationPolicy.evaluate(progressionAction("dialogue", player, player, -1)).allowed());

        AuthorizationDecision mismatch = AuthorizationPolicy.evaluate(progressionAction("player", other, player, -1));
        assertFalse(mismatch.allowed());
        assertEquals("PLAYER_SUBJECT_MISMATCH", mismatch.code());
    }

    @Test
    void commandActorsNeedOperatorProofToActOnAnotherPlayer() {
        UUID commandInvoker = UUID.randomUUID();
        UUID targetPlayer = UUID.randomUUID();

        assertTrue(AuthorizationPolicy.evaluate(
                progressionAction("command", commandInvoker, commandInvoker, -1)).allowed());

        AuthorizationDecision denied = AuthorizationPolicy.evaluate(
                progressionAction("command", commandInvoker, targetPlayer, -1));
        assertFalse(denied.allowed());
        assertEquals("PERMISSION_DENIED", denied.code());

        assertTrue(AuthorizationPolicy.evaluate(
                progressionAction("command", commandInvoker, targetPlayer, 2)).allowed());
    }

    @Test
    void systemActorAlwaysAllowedAndScriptsAlwaysDeniedForProgressionActions() {
        UUID player = UUID.randomUUID();
        assertTrue(AuthorizationPolicy.evaluate(progressionAction("system", player, player, -1)).allowed());

        AuthorizationDecision scriptDenied = AuthorizationPolicy.evaluate(progressionAction("script", player, player, -1));
        assertFalse(scriptDenied.allowed());
        assertEquals("SCRIPT_CAPABILITY_REQUIRED", scriptDenied.code());
    }
}
