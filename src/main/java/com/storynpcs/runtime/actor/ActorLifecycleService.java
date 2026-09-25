package com.storynpcs.runtime.actor;

import com.storynpcs.api.event.ActorLifecycleEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Application-facing actor lifecycle service. Entity hooks call this service;
 * they do not own identity or persistence rules themselves.
 */
public final class ActorLifecycleService {
    private final ActorProjectionRegistry registry;
    private final EventPublisher eventPublisher;

    public ActorLifecycleService(ActorProjectionRegistry registry, EventPublisher eventPublisher) {
        this.registry = registry == null ? new ActorProjectionRegistry("default") : registry;
        this.eventPublisher = eventPublisher;
    }

    public ActorProjectionRegistry registry() {
        return registry;
    }

    public ActorProjectionResult bindProjection(NamespacedId actorId, NamespacedId definitionId, UUID projectionId) {
        return publish(registry.bind(actorId, definitionId, projectionId));
    }

    public ActorProjectionResult replaceProjection(
            NamespacedId actorId,
            NamespacedId definitionId,
            UUID expectedProjectionId,
            UUID replacementProjectionId
    ) {
        return publish(registry.replace(actorId, definitionId, expectedProjectionId, replacementProjectionId));
    }

    public ActorProjectionResult unloadProjection(NamespacedId actorId, UUID projectionId) {
        return publish(registry.detach(actorId, projectionId, ActorLifecycleReason.UNLOADED));
    }

    public ActorProjectionResult despawnProjection(NamespacedId actorId, UUID projectionId) {
        return publish(registry.detach(actorId, projectionId, ActorLifecycleReason.DESPAWNED));
    }

    public ActorProjectionResult failProjection(NamespacedId actorId, UUID projectionId, String diagnostic) {
        return publish(registry.fail(actorId, projectionId, diagnostic));
    }

    public ActorProjectionResult retryProjection(NamespacedId actorId, UUID projectionId) {
        return registry.find(actorId)
                .map(record -> bindProjection(actorId, record.definitionId(), projectionId))
                .orElseGet(() -> publish(ActorProjectionResult.rejected(
                        actorId,
                        projectionId,
                        ActorLifecycleState.FAILED,
                        "Unknown logical actor " + actorId,
                        false
                )));
    }

    /**
     * Reconciles legacy entity data that has a definition but no durable actor
     * ID. A single unloaded actor with that definition is reused; otherwise a
     * new actor is created only when no durable candidate exists.
     */
    public ActorProjectionResult bindLegacyProjection(NamespacedId definitionId, UUID projectionId) {
        if (definitionId == null || projectionId == null) {
            NamespacedId diagnosticActor = projectionId == null
                    ? NamespacedId.of("storynpcs:legacy/unresolved")
                    : NamespacedId.of("storynpcs:legacy/" + projectionId);
            return publish(ActorProjectionResult.rejected(
                    diagnosticActor,
                    projectionId,
                    ActorLifecycleState.FAILED,
                    "Legacy projection requires a definition ID and projection UUID",
                    true
            ));
        }
        var candidates = registry.findUnprojectedByDefinition(definitionId);
        if (candidates.size() == 1) {
            ActorRecord candidate = candidates.get(0);
            return bindProjection(candidate.actorId(), definitionId, projectionId);
        }
        if (candidates.size() > 1) {
            NamespacedId diagnosticActor = NamespacedId.of("storynpcs:legacy/" + projectionId);
            return publish(ActorProjectionResult.rejected(
                    diagnosticActor,
                    projectionId,
                    ActorLifecycleState.FAILED,
                    "Legacy projection is ambiguous: " + candidates.size()
                            + " unloaded actors use definition " + definitionId,
                    true
            ));
        }
        NamespacedId generatedActor = NamespacedId.of("storynpcs:actor/" + projectionId);
        return bindProjection(generatedActor, definitionId, projectionId);
    }

    /** Restores durable actors and emits one explicit RESTORED event per actor. */
    public int restore(ActorRegistrySnapshot snapshot) {
        int restored = registry.restore(snapshot);
        if (eventPublisher != null) {
            registry.records().forEach(record -> eventPublisher.publish(new ActorLifecycleEvent(
                    registry.scopeId(),
                    record.actorId(),
                    null,
                    ActorLifecycleReason.RESTORED,
                    record.state(),
                    true,
                    false,
                    record.diagnostic()
            )));
        }
        return restored;
    }

    private ActorProjectionResult publish(ActorProjectionResult result) {
        if (eventPublisher != null && result.actorId() != null) {
            eventPublisher.publish(new ActorLifecycleEvent(
                    registry.scopeId(),
                    result.actorId(),
                    result.projectionId(),
                    result.reason(),
                    result.state(),
                    result.applied(),
                    result.retryable(),
                    result.diagnostic()
            ));
        }
        return result;
    }
}
