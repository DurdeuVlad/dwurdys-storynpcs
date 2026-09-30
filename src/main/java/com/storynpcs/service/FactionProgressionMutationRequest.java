package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable command for a player-scoped faction-standing mutation. */
public record FactionProgressionMutationRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        NamespacedId factionId,
        Action action,
        int amount,
        long expectedRevision,
        UUID requestId,
        int permissionLevel) {

    public enum Action { SET, ADJUST }

    private static final Set<String> ACTORS = Set.of("command", "dialogue", "player", "script", "system");

    public FactionProgressionMutationRequest {
        if (actorType == null || !ACTORS.contains(actorType)) {
            throw new IllegalArgumentException("actorType must be a registered faction actor");
        }
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(factionId, "factionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestId, "requestId");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
        if (permissionLevel < -1) throw new IllegalArgumentException("permissionLevel must be >= -1");
        // amount is intentionally unbounded: the domain clamps faction standing to +/-100_000.
    }

    public static FactionProgressionMutationRequest set(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId factionId,
            int points, long expectedRevision, UUID requestId) {
        return new FactionProgressionMutationRequest(actorType, actorId, playerUuid, factionId,
                Action.SET, points, expectedRevision, requestId, -1);
    }

    public static FactionProgressionMutationRequest adjust(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId factionId,
            int delta, long expectedRevision, UUID requestId) {
        return new FactionProgressionMutationRequest(actorType, actorId, playerUuid, factionId,
                Action.ADJUST, delta, expectedRevision, requestId, -1);
    }

    public String operation() {
        return action == Action.SET ? "faction.progress.set" : "faction.progress.adjust";
    }

    public String capability() {
        return operation();
    }
}
