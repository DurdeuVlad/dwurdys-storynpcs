package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record DialogueOpenEvent(UUID playerUuid, NamespacedId dialogueId, String entryNodeId) implements StoryNpcsEvent {}
