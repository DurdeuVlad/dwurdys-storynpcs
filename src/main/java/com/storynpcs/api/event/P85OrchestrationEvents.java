package com.storynpcs.api.event;

import java.util.UUID;

import com.storynpcs.domain.common.NamespacedId;

/** P8-5 orchestration events — one file, one record per surface. */
public final class P85OrchestrationEvents {

    private P85OrchestrationEvents() {}

    /** Parity surface for the target's {@code NpcEvent$TimerEvent}. */
    public record NpcTimerEvent(UUID timerId, UUID actorId, String eventId,
                                long firedTick) implements StoryNpcsEvent {}

    /** Link create/remove/cleanup on the actor relationship graph. */
    public record NpcLinkEvent(UUID actorUuid, UUID targetUuid, Action action)
            implements StoryNpcsEvent {
        public enum Action { LINKED, UNLINKED, TARGET_MISSING_CLEANUP }
    }

    /** Scene session lifecycle: start, stage advance, completion, cancellation. */
    public record SceneLifecycleEvent(NamespacedId sceneId, Status status,
                                      String detail) implements StoryNpcsEvent {
        public enum Status { STARTED, STAGE, COMPLETED, CANCELLED }
    }

    /** Actor transformation applied (PRESERVE or REPLACE identity). */
    public record NpcTransformEvent(UUID actorUuid, NamespacedId ruleId,
                                    String policy, String outcome) implements StoryNpcsEvent {}

    /** A natural-spawn rule produced an actor in the world. */
    public record NaturalSpawnEvent(NamespacedId ruleId, UUID actorUuid,
                                    String position) implements StoryNpcsEvent {}
}
