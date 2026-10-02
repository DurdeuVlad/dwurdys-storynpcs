package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Fired when an NPC that resolved fatal damage with the authored HIDE defeat
 * mode reappears after its respawn timer. Complements {@link NpcDefeatedEvent}:
 * DIE-mode entities are removed permanently and never emit this event.
 */
public record NpcRespawnedEvent(
        NamespacedId definitionId,
        UUID entityUuid
) implements StoryNpcsEvent {}
