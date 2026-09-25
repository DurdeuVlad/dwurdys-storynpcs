package com.storynpcs.domain.progression;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;
import java.util.UUID;

/** Durable idempotency intent for an objective mutation awaiting quest completion. */
public record PendingQuestCompletion(
        UUID requestId,
        NamespacedId questId,
        String payloadFingerprint,
        long questStateRevision) {

    public PendingQuestCompletion {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(questId, "questId");
        if (payloadFingerprint == null || !payloadFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadFingerprint must be a SHA-256 fingerprint");
        }
        if (questStateRevision < 0) {
            throw new IllegalArgumentException("questStateRevision must be non-negative");
        }
    }
}
