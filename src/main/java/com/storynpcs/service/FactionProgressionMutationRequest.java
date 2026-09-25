package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable command for a player-scoped faction-reputation mutation. */
public record FactionProgressionMutationRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        NamespacedId factionId,
        Action action,
        int value,
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
        if (value < -100_000 || value > 100_000) {
            throw new IllegalArgumentException("value must be between -100000 and 100000");
        }
    }

    public static FactionProgressionMutationRequest set(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId factionId,
            int points, long expectedRevision, UUID requestId, int permissionLevel) {
        return new FactionProgressionMutationRequest(actorType, actorId, playerUuid, factionId,
                Action.SET, points, expectedRevision, requestId, permissionLevel);
    }

    public static FactionProgressionMutationRequest adjust(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId factionId,
            int delta, long expectedRevision, UUID requestId, int permissionLevel) {
        return new FactionProgressionMutationRequest(actorType, actorId, playerUuid, factionId,
                Action.ADJUST, delta, expectedRevision, requestId, permissionLevel);
    }

    public String operation() {
        return action == Action.SET ? "faction.set" : "faction.adjust";
    }

    public String capability() {
        return action == Action.SET ? "faction.set" : "faction.adjust";
    }
}
