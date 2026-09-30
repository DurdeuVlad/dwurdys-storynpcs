package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record FactionReputationChangeEvent(
        UUID playerUuid,
        NamespacedId factionId,
        int oldPoints,
        int newPoints,
        /** Change source — actorType of the canonical request (command/dialogue/quest/kill/api/script/system). */
        String source
) implements StoryNpcsEvent {
    /** Back-compat convenience for sites that predate source tagging. */
    public FactionReputationChangeEvent(UUID playerUuid, NamespacedId factionId, int oldPoints, int newPoints) {
        this(playerUuid, factionId, oldPoints, newPoints, "system");
    }
}
