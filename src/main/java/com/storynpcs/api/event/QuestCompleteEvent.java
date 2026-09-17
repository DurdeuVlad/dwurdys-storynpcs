package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

public record QuestCompleteEvent(UUID playerUuid, NamespacedId questId) implements StoryNpcsEvent {}
