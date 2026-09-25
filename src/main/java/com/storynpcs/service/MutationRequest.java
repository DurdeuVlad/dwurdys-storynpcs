package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;
import java.util.UUID;

/** Typed context carried by a canonical definition mutation. */
public record MutationRequest(
        String operation,
        String actorType,
        String capability,
        NamespacedId targetId,
        long expectedRevision,
        UUID requestId,
        int permissionLevel) {

    public MutationRequest(String operation, String actorType, String capability,
                           NamespacedId targetId, long expectedRevision, UUID requestId) {
        this(operation, actorType, capability, targetId, expectedRevision, requestId, -1);
    }

    public MutationRequest {
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation is required");
        if (actorType == null || actorType.isBlank()) throw new IllegalArgumentException("actorType is required");
        if (capability == null || capability.isBlank()) throw new IllegalArgumentException("capability is required");
        Objects.requireNonNull(targetId, "targetId");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
        Objects.requireNonNull(requestId, "requestId");
        if (permissionLevel < -1) throw new IllegalArgumentException("permissionLevel must be >= -1");
    }
}
