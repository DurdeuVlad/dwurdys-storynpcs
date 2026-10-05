package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Fired server-side after an NPC's authored drop table rolled on death
 * (P3-4). {@code items} are the winning stacks (immutable) and
 * {@code experience} the rolled XP amount; {@code killerUuid} is the resolved
 * damage-source entity when one exists. The event is observational — the
 * drops have already been determined; subscribers observe, they do not
 * rewrite the roll.
 */
public record NpcLootDroppedEvent(
        NamespacedId definitionId,
        UUID entityUuid,
        UUID killerUuid,
        List<NpcItemStack> items,
        int experience
) implements StoryNpcsEvent {}
