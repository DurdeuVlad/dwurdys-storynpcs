package com.storynpcs.runtime.actor;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Immutable runtime record for a logical actor.
 *
 * <p>{@code actorId} is durable identity.  {@code projectionId} is the
 * current entity UUID only and is deliberately not persisted across a server
 * restart.</p>
 */
public record ActorRecord(
        NamespacedId actorId,
        NamespacedId definitionId,
        UUID projectionId,
        ActorLifecycleState state,
        ActorLifecycleReason lastReason,
        String diagnostic
) {
    public ActorRecord {
        if (actorId == null) {
            throw new IllegalArgumentException("actorId cannot be null");
        }
        if (definitionId == null) {
            throw new IllegalArgumentException("definitionId cannot be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        if (lastReason == null) {
            throw new IllegalArgumentException("lastReason cannot be null");
        }
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}
