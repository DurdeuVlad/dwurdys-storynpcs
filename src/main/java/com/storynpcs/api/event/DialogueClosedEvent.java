package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/** Emitted when a dialogue session ends — by player choice, timeout, or server close. */
public record DialogueClosedEvent(
        UUID playerUuid,
        NamespacedId dialogueId,
        String lastNodeId,
        Reason reason) implements StoryNpcsEvent {

    public enum Reason { PLAYER_EXIT, TIMEOUT, SERVER_CLOSE, GRAPH_END }
}
