package com.storynpcs.service;

import java.util.Set;

/**
 * Small, deterministic policy boundary used by canonical mutations.
 * Platform adapters prove OP/permission-node authority before creating a player request;
 * this layer still rejects unknown principals, capabilities, and unproven player requests.
 */
public final class AuthorizationPolicy {
    private static final Set<String> ADAPTER_ACTORS = Set.of(
            "adapter", "command", "packet", "gui", "api", "console", "system");
    private static final Set<String> DEFINITION_CAPABILITIES = Set.of(
            "npc.mutate", "npc.edit", "npc.delete", "dialogue.mutate", "dialogue.edit", "dialogue.delete",
            "quest.mutate", "quest.edit", "quest.delete", "faction.mutate", "faction.edit", "faction.delete");

    private AuthorizationPolicy() {}

    public static AuthorizationDecision evaluate(MutationRequest request) {
        String actor = request.actorType();
        String capability = request.capability();
        boolean playerActor = actor.startsWith("player:") && actor.length() > "player:".length();
        if (!playerActor && !ADAPTER_ACTORS.contains(actor) && !actor.equals("script")) {
            return AuthorizationDecision.deny("UNKNOWN_ACTOR", "Unknown mutation actor type: " + actor);
        }
        if (!DEFINITION_CAPABILITIES.contains(capability)) {
            return AuthorizationDecision.deny("UNKNOWN_CAPABILITY", "Capability is not registered: " + capability);
        }
        if (actor.equals("script")) {
            return AuthorizationDecision.deny("SCRIPT_CAPABILITY_REQUIRED",
                    "Scripts cannot invoke definition mutations without an explicit granted capability.");
        }
        if (playerActor && request.permissionLevel() < 2) {
            return AuthorizationDecision.deny("PERMISSION_DENIED",
                    "Player mutation requests require operator level 2 proof.");
        }
        return AuthorizationDecision.allow();
    }

    /** Authorization for player-scoped quest mutations; the subject is not inferred from a quest ID. */
    public static AuthorizationDecision evaluate(QuestProgressionMutationRequest request) {
        String actor = request.actorType();
        if (actor.equals("script")) {
            return AuthorizationDecision.deny("SCRIPT_CAPABILITY_REQUIRED",
                    "Scripts cannot mutate quest progression without an explicit granted capability.");
        }
        if (actor.equals("player") || actor.equals("dialogue")) {
            if (!request.playerUuid().equals(request.actorId())) {
                return AuthorizationDecision.deny("PLAYER_SUBJECT_MISMATCH",
                        "Player and dialogue actors may only mutate their own quest progression.");
            }
            return AuthorizationDecision.allow();
        }
        if (actor.equals("command")) {
            boolean self = request.playerUuid().equals(request.actorId());
            if (!self && request.permissionLevel() < 2) {
                return AuthorizationDecision.deny("PERMISSION_DENIED",
                        "Changing another player's quest progression requires permission level 2.");
            }
            return AuthorizationDecision.allow();
        }
        if (actor.equals("system")) return AuthorizationDecision.allow();
        return AuthorizationDecision.deny("UNKNOWN_ACTOR", "Unknown quest progression actor: " + actor);
    }
}
