package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

/** Emitted when a dialogue definition is reloaded and open sessions are re-evaluated. */
public record DialogueReloadedEvent(
        NamespacedId dialogueId,
        int affectedSessions,
        int closedSessions) implements StoryNpcsEvent {}
