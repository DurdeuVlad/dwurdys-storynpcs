package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Fired when an NPC acquires or clears combat aggression towards a target.
 */
public record NpcAggroChangeEvent(
        NamespacedId npcId,
        UUID targetUuid,
        boolean isAggro,
        String reason
) implements StoryNpcsEvent {}
