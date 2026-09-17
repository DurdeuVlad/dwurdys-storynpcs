package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Fired when a guard NPC witnesses an unlawful assault and engages the assailant.
 */
public record AssaultWitnessedEvent(
        NamespacedId guardId,
        UUID assailantUuid,
        UUID victimUuid
) implements StoryNpcsEvent {}
