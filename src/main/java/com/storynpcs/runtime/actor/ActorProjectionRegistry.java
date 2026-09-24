package com.storynpcs.runtime.actor;

import com.storynpcs.domain.common.NamespacedId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-server actor registry. It intentionally has no static mutable state.
 * Logical IDs survive entity replacement; entity UUIDs are reverse-indexed
 * projections and can be discarded and rebuilt.
 */
public final class ActorProjectionRegistry {
    private final String scopeId;
    private final Map<NamespacedId, ActorRecord> actors = new HashMap<>();
    private final Map<UUID, NamespacedId> projectionToActor = new HashMap<>();

    public ActorProjectionRegistry(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId cannot be blank");
        }
        this.scopeId = scopeId;
    }

    public String scopeId() {
        return scopeId;
    }

    public synchronized ActorProjectionResult bind(
            NamespacedId actorId,
            NamespacedId definitionId,
            UUID projectionId
    ) {
        if (actorId == null || definitionId == null || projectionId == null) {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    ActorLifecycleState.FAILED,
                    "Actor ID, definition ID, and projection UUID are required",
                    false
            );
        }

        NamespacedId existingActor = projectionToActor.get(projectionId);
        if (existingActor != null && !existingActor.equals(actorId)) {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    currentState(actorId),
                    "Projection UUID is already bound to actor " + existingActor,
                    true
            );
        }

        ActorRecord previous = actors.get(actorId);
        ActorLifecycleReason reason;
        if (previous == null || previous.projectionId() == null) {
            reason = previous == null || previous.state() == ActorLifecycleState.UNPROJECTED
                    ? ActorLifecycleReason.SPAWNED
                    : previous.state() == ActorLifecycleState.FAILED
                    ? ActorLifecycleReason.RETRY_SUCCEEDED
                    : ActorLifecycleReason.RELOADED;
        } else if (previous.projectionId().equals(projectionId)) {
            reason = ActorLifecycleReason.PROJECTION_REFRESHED;
        } else {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    previous.state(),
                    "Active projection exists; explicit replacement token is required",
                    true
            );
        }

        ActorRecord current = new ActorRecord(
                actorId,
                definitionId,
                projectionId,
                ActorLifecycleState.PROJECTED,
                reason,
                ""
        );
        actors.put(actorId, current);
        projectionToActor.put(projectionId, actorId);
        return applied(current, reason, "");
    }

    public synchronized ActorProjectionResult replace(
            NamespacedId actorId,
            NamespacedId definitionId,
            UUID expectedProjectionId,
            UUID replacementProjectionId
    ) {
        if (actorId == null || definitionId == null || expectedProjectionId == null || replacementProjectionId == null) {
            return ActorProjectionResult.rejected(
                    actorId,
                    replacementProjectionId,
                    currentState(actorId),
                    "Actor ID, definition ID, expected projection, and replacement projection are required",
                    false
            );
        }
        ActorRecord previous = actors.get(actorId);
        if (previous == null || !expectedProjectionId.equals(previous.projectionId())) {
            return ActorProjectionResult.rejected(
                    actorId,
                    replacementProjectionId,
                    previous == null ? ActorLifecycleState.UNPROJECTED : previous.state(),
                    "Replacement token does not match the active projection",
                    true
            );
        }
        NamespacedId existingActor = projectionToActor.get(replacementProjectionId);
        if (existingActor != null && !existingActor.equals(actorId)) {
            return ActorProjectionResult.rejected(
                    actorId,
                    replacementProjectionId,
                    previous.state(),
                    "Replacement UUID is already bound to actor " + existingActor,
                    true
            );
        }
        projectionToActor.remove(expectedProjectionId);
        ActorRecord current = new ActorRecord(
                actorId,
                definitionId,
                replacementProjectionId,
                ActorLifecycleState.PROJECTED,
                ActorLifecycleReason.REPLACED,
                ""
        );
        actors.put(actorId, current);
        projectionToActor.put(replacementProjectionId, actorId);
        return applied(current, ActorLifecycleReason.REPLACED, "");
    }

    public synchronized ActorProjectionResult detach(
            NamespacedId actorId,
            UUID projectionId,
            ActorLifecycleReason reason
    ) {
        if (actorId == null || projectionId == null || reason == null) {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    currentState(actorId),
                    "Actor ID, projection UUID, and lifecycle reason are required",
                    false
            );
        }
        ActorRecord previous = actors.get(actorId);
        if (previous == null) {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    ActorLifecycleState.UNPROJECTED,
                    "Unknown logical actor " + actorId,
                    true
            );
        }
        if (previous.projectionId() != null && !previous.projectionId().equals(projectionId)) {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    previous.state(),
                    "Projection UUID does not match the active projection",
                    true
            );
        }

        projectionToActor.remove(projectionId);
        ActorLifecycleState nextState = reason == ActorLifecycleReason.UNLOADED
                ? ActorLifecycleState.UNLOADED
                : ActorLifecycleState.UNPROJECTED;
        ActorRecord current = new ActorRecord(
                actorId,
                previous.definitionId(),
                null,
                nextState,
                reason,
                ""
        );
        actors.put(actorId, current);
        return applied(current, reason, "");
    }

    public synchronized ActorProjectionResult fail(
            NamespacedId actorId,
            UUID projectionId,
            String diagnostic
    ) {
        if (actorId == null) {
            return ActorProjectionResult.rejected(null, projectionId, ActorLifecycleState.FAILED,
                    "Actor ID is required", false);
        }
        ActorRecord previous = actors.get(actorId);
        if (previous == null) {
            return ActorProjectionResult.rejected(actorId, projectionId, ActorLifecycleState.FAILED,
                    "Unknown logical actor " + actorId, true);
        }
        if (!java.util.Objects.equals(previous.projectionId(), projectionId)) {
            return ActorProjectionResult.rejected(
                    actorId,
                    projectionId,
                    previous.state(),
                    "Projection failure is stale; active projection is " + previous.projectionId(),
                    false
            );
        }
        if (previous.projectionId() != null) {
            projectionToActor.remove(previous.projectionId());
        }
        ActorRecord current = new ActorRecord(
                actorId,
                previous.definitionId(),
                null,
                ActorLifecycleState.FAILED,
                ActorLifecycleReason.PROJECTION_FAILED,
                diagnostic
        );
        actors.put(actorId, current);
        return new ActorProjectionResult(
                false,
                true,
                actorId,
                projectionId,
                ActorLifecycleReason.PROJECTION_FAILED,
                ActorLifecycleState.FAILED,
                diagnostic
        );
    }

    public synchronized Optional<ActorRecord> find(NamespacedId actorId) {
        return Optional.ofNullable(actors.get(actorId));
    }

    public synchronized List<ActorRecord> findUnprojectedByDefinition(NamespacedId definitionId) {
        if (definitionId == null) {
            return List.of();
        }
        return actors.values().stream()
                .filter(record -> definitionId.equals(record.definitionId()))
                .filter(record -> record.projectionId() == null)
                .toList();
    }

    public synchronized Optional<NamespacedId> actorForProjection(UUID projectionId) {
        return Optional.ofNullable(projectionToActor.get(projectionId));
    }

    public synchronized List<ActorRecord> records() {
        return List.copyOf(actors.values());
    }

    public synchronized ActorRegistrySnapshot snapshot() {
        List<ActorRegistrySnapshot.ActorSnapshot> snapshots = new ArrayList<>();
        for (ActorRecord record : actors.values()) {
            snapshots.add(new ActorRegistrySnapshot.ActorSnapshot(
                    record.actorId().toString(),
                    record.definitionId().toString(),
                    record.lastReason().name(),
                    record.diagnostic()
            ));
        }
        return new ActorRegistrySnapshot(scopeId, snapshots);
    }

    /**
     * Restores logical actors after a server restart. No old entity UUID is
     * trusted; every restored actor waits for a new entity projection.
     */
    public synchronized int restore(ActorRegistrySnapshot snapshot) {
        if (snapshot == null || !scopeId.equals(snapshot.scopeId())) {
            throw new IllegalArgumentException("Actor snapshot scope does not match this registry");
        }
        actors.clear();
        projectionToActor.clear();
        int restored = 0;
        for (ActorRegistrySnapshot.ActorSnapshot saved : snapshot.actors()) {
            try {
                NamespacedId actorId = NamespacedId.of(saved.actorId());
                NamespacedId definitionId = NamespacedId.of(saved.definitionId());
                ActorLifecycleReason reason;
                try {
                    reason = ActorLifecycleReason.valueOf(saved.lastReason());
                } catch (IllegalArgumentException ignored) {
                    reason = ActorLifecycleReason.RESTORED;
                }
                actors.put(actorId, new ActorRecord(
                        actorId,
                        definitionId,
                        null,
                        ActorLifecycleState.UNLOADED,
                        reason == ActorLifecycleReason.RESTORED ? reason : ActorLifecycleReason.RESTORED,
                        saved.diagnostic()
                ));
                restored++;
            } catch (RuntimeException ignored) {
                // A malformed record must not prevent valid actors from loading.
            }
        }
        return restored;
    }

    private ActorLifecycleState currentState(NamespacedId actorId) {
        ActorRecord current = actors.get(actorId);
        return current == null ? ActorLifecycleState.UNPROJECTED : current.state();
    }

    private static ActorProjectionResult applied(ActorRecord record, ActorLifecycleReason reason, String diagnostic) {
        return new ActorProjectionResult(
                true,
                false,
                record.actorId(),
                record.projectionId(),
                reason,
                record.state(),
                diagnostic
        );
    }
}
