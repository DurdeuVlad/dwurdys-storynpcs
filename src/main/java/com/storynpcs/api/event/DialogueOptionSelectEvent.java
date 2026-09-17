package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record DialogueOptionSelectEvent(
        UUID playerUuid,
        NamespacedId dialogueId,
        String fromNodeId,
        String toNodeId,
        int optionIndex
) implements StoryNpcsEvent {}
