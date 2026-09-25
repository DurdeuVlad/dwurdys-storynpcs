package com.storynpcs.service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable command for a player-scoped progression action that carries no numeric
 * payload — mail read/delete, transport-location unlock, and similar (issue #54 —
 * P1-4 authorization policy coverage). Mirrors
 * {@link QuestProgressionMutationRequest}/{@link FactionProgressionMutationRequest}'s
 * actor/subject shape without a resource-specific field: the actual target (a mail
 * ID, a transport location ID, ...) is passed as a separate method parameter by the
 * caller, since it varies by operation and {@link AuthorizationPolicy#evaluate(PlayerProgressionActionRequest)}
 * never needs to inspect it — only who is acting and on whose progression.
 */
public record PlayerProgressionActionRequest(
        String operation,
        String actorType,
        UUID actorId,
        UUID playerUuid,
        UUID requestId,
        int permissionLevel) {

    private static final Set<String> ACTORS = Set.of("command", "dialogue", "player", "script", "system");

    public PlayerProgressionActionRequest {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        if (actorType == null || !ACTORS.contains(actorType)) {
            throw new IllegalArgumentException("actorType must be a registered progression actor");
        }
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(requestId, "requestId");
        if (permissionLevel < -1) {
            throw new IllegalArgumentException("permissionLevel must be >= -1");
        }
    }
}
