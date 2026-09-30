package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable command for a player-scoped quest completion operation. */
public record QuestCompletionMutationRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        NamespacedId questId,
        long expectedRevision,
        UUID requestId,
        int permissionLevel) {

    private static final Set<String> ACTORS = Set.of("command", "dialogue", "player", "script", "system");

    public QuestCompletionMutationRequest {
        if (actorType == null || !ACTORS.contains(actorType)) {
            throw new IllegalArgumentException("actorType must be a registered quest actor");
        }
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(requestId, "requestId");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
        if (permissionLevel < -1) throw new IllegalArgumentException("permissionLevel must be >= -1");
    }

    public static QuestCompletionMutationRequest of(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId questId,
            long expectedRevision, UUID requestId) {
        return new QuestCompletionMutationRequest(actorType, actorId, playerUuid, questId,
                expectedRevision, requestId, -1);
    }

    public String operation() {
        return "quest.complete";
    }

    public String capability() {
        return "quest.complete";
    }
}
