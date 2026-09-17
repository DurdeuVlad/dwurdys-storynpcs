package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record QuestObjectiveProgressEvent(
        UUID playerUuid,
        NamespacedId questId,
        String objectiveId,
        int currentCount,
        int requiredCount
) implements StoryNpcsEvent {}
