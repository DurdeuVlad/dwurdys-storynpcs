package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.follower.FollowerRole;

import java.util.UUID;

public record FollowerStateChangeEvent(
        UUID playerUuid,
        NamespacedId npcId,
        FollowerRole.State previousState,
        FollowerRole.State newState
) implements StoryNpcsEvent {}