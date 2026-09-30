package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Published when an explicit reset clears a completed RESET-type quest back to
 * unstarted — the quest can be taken again.
 */
public record QuestResetEvent(UUID playerUuid, NamespacedId questId) implements StoryNpcsEvent {}
