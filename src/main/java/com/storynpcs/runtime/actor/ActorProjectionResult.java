package com.storynpcs.runtime.actor;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/** Result of an actor/projection lifecycle transition. */
public record ActorProjectionResult(
        boolean applied,
        boolean retryable,
        NamespacedId actorId,
        UUID projectionId,
        ActorLifecycleReason reason,
        ActorLifecycleState state,
        String diagnostic
) {
    public ActorProjectionResult {
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static ActorProjectionResult rejected(
            NamespacedId actorId,
            UUID projectionId,
            ActorLifecycleState state,
            String diagnostic,
            boolean retryable
    ) {
        return new ActorProjectionResult(
                false,
                retryable,
                actorId,
                projectionId,
                ActorLifecycleReason.PROJECTION_FAILED,
                state == null ? ActorLifecycleState.FAILED : state,
                diagnostic
        );
    }
}
