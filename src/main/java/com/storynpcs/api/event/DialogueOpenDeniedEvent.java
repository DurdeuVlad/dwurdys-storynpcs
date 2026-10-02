package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/** Emitted when a dialogue open is denied — e.g. graph-level availability conditions fail. */
public record DialogueOpenDeniedEvent(
        UUID playerUuid,
        NamespacedId dialogueId,
        String reason) implements StoryNpcsEvent {}
