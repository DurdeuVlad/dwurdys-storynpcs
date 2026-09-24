package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.runtime.actor.ActorLifecycleReason;
import com.storynpcs.runtime.actor.ActorLifecycleState;

import java.util.UUID;

/** Published when a logical actor is attached to or detached from an entity projection. */
public record ActorLifecycleEvent(
        String scopeId,
        NamespacedId actorId,
        UUID projectionId,
        ActorLifecycleReason reason,
        ActorLifecycleState state,
        boolean applied,
        boolean retryable,
        String diagnostic
) implements StoryNpcsEvent {
    public ActorLifecycleEvent {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId cannot be blank");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("actorId cannot be null");
        }
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}
