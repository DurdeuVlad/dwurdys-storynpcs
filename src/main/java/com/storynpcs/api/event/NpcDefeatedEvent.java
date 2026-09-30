package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcStats;

import java.util.UUID;

/**
 * Fired when an NPC resolves fatal damage. {@code mode} reports the authored
 * defeat behavior (DIE/HIDE/FLEE), {@code respawnTimeSeconds} the authored
 * respawn delay, and {@code entityUuid} the removed or disengaging projection.
 */
public record NpcDefeatedEvent(
        NamespacedId definitionId,
        UUID entityUuid,
        NpcStats.Defeat.Mode mode,
        int respawnTimeSeconds,
        int xpReward
) implements StoryNpcsEvent {}
