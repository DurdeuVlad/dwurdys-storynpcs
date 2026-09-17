package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record QuestStartEvent(UUID playerUuid, NamespacedId questId) implements StoryNpcsEvent {}
