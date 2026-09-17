package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record FactionReputationChangeEvent(
        UUID playerUuid,
        NamespacedId factionId,
        int oldPoints,
        int newPoints
) implements StoryNpcsEvent {}
