package com.storynpcs.service;

import java.util.Map;

/**
 * Registry of every capability routed through the canonical authorization
 * boundary. Each capability maps to exactly one policy family: definition
 * mutations (operator/tool authority) or player-scoped runtime operations
 * (subject identity, command/dialogue/dialogue-action wrappers, script deny).
 */
public final class CapabilityRegistry {

    public enum Policy {
        /** Authoring/definition mutations — gated by tool style + permission level. */
        DEFINITION,
        /** Runtime operations on a single player subject — same actor rules as progression. */
        PLAYER_SCOPED
    }

    private static final Map<String, Policy> CAPABILITIES = Map.ofEntries(
            // Definition operations (MutationRequest)
            Map.entry("npc.mutate", Policy.DEFINITION),
            Map.entry("npc.edit", Policy.DEFINITION),
            Map.entry("npc.delete", Policy.DEFINITION),
            Map.entry("dialogue.mutate", Policy.DEFINITION),
            Map.entry("dialogue.edit", Policy.DEFINITION),
            Map.entry("dialogue.delete", Policy.DEFINITION),
            Map.entry("quest.mutate", Policy.DEFINITION),
            Map.entry("quest.edit", Policy.DEFINITION),
            Map.entry("quest.delete", Policy.DEFINITION),
            Map.entry("faction.mutate", Policy.DEFINITION),
            Map.entry("faction.edit", Policy.DEFINITION),
            Map.entry("faction.delete", Policy.DEFINITION),
            Map.entry("transport.mutate", Policy.DEFINITION),
            Map.entry("transport.edit", Policy.DEFINITION),
            Map.entry("transport.delete", Policy.DEFINITION),
            Map.entry("template.mutate", Policy.DEFINITION),
            Map.entry("template.edit", Policy.DEFINITION),
            Map.entry("template.delete", Policy.DEFINITION),
            // Player-scoped progression operations
            Map.entry("quest.start", Policy.PLAYER_SCOPED),
            Map.entry("quest.progress", Policy.PLAYER_SCOPED),
            Map.entry("quest.complete", Policy.PLAYER_SCOPED),
            Map.entry("quest.reset", Policy.PLAYER_SCOPED),
            Map.entry("config.mutate", Policy.DEFINITION),
            Map.entry("faction.progress.set", Policy.PLAYER_SCOPED),
            Map.entry("faction.progress.adjust", Policy.PLAYER_SCOPED),
            Map.entry("follower.state.set", Policy.PLAYER_SCOPED),
            Map.entry("follower.formation.set", Policy.PLAYER_SCOPED),
            // Player-scoped economy/runtime operations
            Map.entry("bank.deposit", Policy.PLAYER_SCOPED),
            Map.entry("bank.withdraw", Policy.PLAYER_SCOPED),
            Map.entry("bank.share", Policy.PLAYER_SCOPED),
            Map.entry("bank.unlock", Policy.PLAYER_SCOPED),
            Map.entry("trade.execute", Policy.PLAYER_SCOPED),
            // Player-scoped progression actions (PlayerProgressionActionRequest)
            Map.entry("mail.read", Policy.PLAYER_SCOPED),
            Map.entry("mail.delete", Policy.PLAYER_SCOPED),
            Map.entry("mail.send", Policy.PLAYER_SCOPED),
            Map.entry("transport.unlock", Policy.PLAYER_SCOPED),
            Map.entry("transport.request", Policy.PLAYER_SCOPED),
            Map.entry("dialogue.visit.record", Policy.PLAYER_SCOPED),
            // Shared-party membership actions (P5-5) — subject is the player
            // whose membership changes; ADMIN scope gates cross-subject ops.
            Map.entry("team.create", Policy.PLAYER_SCOPED),
            Map.entry("team.join", Policy.PLAYER_SCOPED),
            Map.entry("team.leave", Policy.PLAYER_SCOPED),
            Map.entry("team.invite", Policy.PLAYER_SCOPED),
            Map.entry("team.owner", Policy.PLAYER_SCOPED)
    );

    private CapabilityRegistry() {}

    public static boolean isRegistered(String capability) {
        return capability != null && CAPABILITIES.containsKey(capability.trim().toLowerCase());
    }

    public static Policy policyOf(String capability) {
        if (capability == null) return null;
        return CAPABILITIES.get(capability.trim().toLowerCase());
    }
}
