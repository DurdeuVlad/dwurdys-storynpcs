package com.storynpcs.editor;

import java.util.Objects;
import java.util.UUID;

/** Reuses an id only while retrying the exact serialized editor submission. */
public final class PayloadBoundRequestId {
    private String payload;
    private UUID requestId;

    public synchronized UUID forPayload(String submittedPayload) {
        Objects.requireNonNull(submittedPayload, "submittedPayload");
        if (requestId == null || !submittedPayload.equals(payload)) {
            payload = submittedPayload;
            requestId = UUID.randomUUID();
        }
        return requestId;
    }

    public synchronized boolean matchesCurrent(UUID responseRequestId) {
        return requestId != null && requestId.equals(responseRequestId);
    }

    /**
     * Ends this request lifecycle after a matching server response, whether it was
     * accepted or rejected. An unacknowledged retry keeps its ID; a new user
     * attempt after a definitive response receives a fresh one.
     */
    public synchronized boolean acknowledge(UUID responseRequestId) {
        if (requestId == null || !requestId.equals(responseRequestId)) return false;
        payload = null;
        requestId = null;
        return true;
    }
}
