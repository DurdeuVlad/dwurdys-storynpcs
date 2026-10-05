package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.job.JobInstance;
import com.storynpcs.domain.job.JobType;

import java.util.UUID;

/**
 * Published when an actor's job transitions lifecycle state (P6-4): bound
 * (null → RUNNING), paused/stopped on unload or removal, or stopped when the
 * definition drops the job. {@code npcId} is the actor's definition id and is
 * null only when the actor carries no resolvable definition.
 */
public record NpcJobLifecycleEvent(
        NamespacedId npcId,
        UUID actorUuid,
        JobType jobType,
        JobInstance.State previousState,
        JobInstance.State newState
) implements StoryNpcsEvent {}
