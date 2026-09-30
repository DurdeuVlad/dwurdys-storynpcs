package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/** Emitted when a dialogue choice is rejected — stale token, expired session, invalid edge. */
public record DialogueChoiceRejectedEvent(
        UUID playerUuid,
        NamespacedId dialogueId,
        String nodeId,
        String reason) implements StoryNpcsEvent {}
