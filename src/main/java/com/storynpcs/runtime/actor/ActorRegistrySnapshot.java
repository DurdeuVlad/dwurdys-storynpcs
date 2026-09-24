package com.storynpcs.runtime.actor;

import java.util.List;

/** Serializable logical-actor snapshot. Projection UUIDs are intentionally absent. */
public record ActorRegistrySnapshot(String scopeId, List<ActorSnapshot> actors) {
    public ActorRegistrySnapshot {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId cannot be blank");
        }
        actors = actors == null ? List.of() : List.copyOf(actors);
    }

    public record ActorSnapshot(
            String actorId,
            String definitionId,
            String lastReason,
            String diagnostic
    ) {
        public ActorSnapshot {
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalArgumentException("actorId cannot be blank");
            }
            if (definitionId == null || definitionId.isBlank()) {
                throw new IllegalArgumentException("definitionId cannot be blank");
            }
            lastReason = lastReason == null ? ActorLifecycleReason.RESTORED.name() : lastReason;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }
}
