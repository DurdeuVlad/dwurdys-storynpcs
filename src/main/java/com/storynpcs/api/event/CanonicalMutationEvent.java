package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/** Audit event emitted once for each non-replayed canonical mutation attempt. */
public record CanonicalMutationEvent(
        String operation,
        String actorType,
        NamespacedId targetId,
        UUID requestId,
        boolean applied,
        long revision,
        String outcome,
        UUID actorId,
        UUID subjectId) implements StoryNpcsEvent {

    public CanonicalMutationEvent(String operation, String actorType, NamespacedId targetId,
                                  UUID requestId, boolean applied, long revision, String outcome) {
        this(operation, actorType, targetId, requestId, applied, revision, outcome, null, null);
    }
}
