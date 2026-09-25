package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable command for a player-scoped quest-state mutation. */
public record QuestProgressionMutationRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        NamespacedId questId,
        Action action,
        String objectiveId,
        int amount,
        long expectedRevision,
        UUID requestId,
        int permissionLevel) {

    public enum Action { START, PROGRESS }

    private static final Set<String> ACTORS = Set.of("command", "dialogue", "player", "script", "system");

    public QuestProgressionMutationRequest {
        if (actorType == null || !ACTORS.contains(actorType)) {
            throw new IllegalArgumentException("actorType must be a registered quest actor");
        }
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestId, "requestId");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
        if (permissionLevel < -1) throw new IllegalArgumentException("permissionLevel must be >= -1");
        if (action == Action.START) {
            if (objectiveId != null && !objectiveId.isBlank()) {
                throw new IllegalArgumentException("START requests cannot name an objective");
            }
            if (amount != 0) throw new IllegalArgumentException("START requests cannot carry an amount");
            objectiveId = "";
        } else {
            if (objectiveId == null || objectiveId.isBlank()) {
                throw new IllegalArgumentException("PROGRESS requests require an objective ID");
            }
            objectiveId = objectiveId.trim();
            if (objectiveId.length() > 128) throw new IllegalArgumentException("objectiveId is too long");
            if (objectiveId.indexOf('\0') >= 0) throw new IllegalArgumentException("objectiveId cannot contain a null character");
            if (amount < -100_000 || amount > 100_000) {
                throw new IllegalArgumentException("amount must be between -100000 and 100000");
            }
        }
    }

    public static QuestProgressionMutationRequest start(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId questId,
            long expectedRevision, UUID requestId) {
        return new QuestProgressionMutationRequest(actorType, actorId, playerUuid, questId,
                Action.START, "", 0, expectedRevision, requestId, -1);
    }

    public static QuestProgressionMutationRequest progress(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId questId,
            String objectiveId, int amount, long expectedRevision, UUID requestId) {
        return new QuestProgressionMutationRequest(actorType, actorId, playerUuid, questId,
                Action.PROGRESS, objectiveId, amount, expectedRevision, requestId, -1);
    }

    public String operation() {
        return action == Action.START ? "quest.start" : "quest.progress";
    }

    public String capability() {
        return action == Action.START ? "quest.start" : "quest.progress";
    }
}
