package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Fired when an NPC issues a warning for an accidental strike before retaliating.
 */
public record NpcToleranceWarnEvent(
        NamespacedId npcId,
        UUID attackerUuid,
        int currentStrikes,
        int maxTolerance
) implements StoryNpcsEvent {}
