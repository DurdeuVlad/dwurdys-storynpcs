package com.storynpcs.api.event;

import java.util.UUID;

import com.storynpcs.domain.common.NamespacedId;

/** Fired when a follower's owning player is reassigned (P9-3 owner command). */
public record FollowerOwnerChangeEvent(
        UUID playerUuid, NamespacedId npcId, UUID oldOwnerUuid, UUID newOwnerUuid)
        implements StoryNpcsEvent {}
