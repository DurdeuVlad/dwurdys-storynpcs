package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.follower.FormationType;

import java.util.UUID;

public record FollowerFormationChangeEvent(
        UUID playerUuid,
        NamespacedId npcId,
        FormationType previousFormation,
        FormationType newFormation,
        int slotIndex,
        double spacing
) implements StoryNpcsEvent {}
