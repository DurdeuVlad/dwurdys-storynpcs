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
}
