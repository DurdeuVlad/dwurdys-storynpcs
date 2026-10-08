package com.storynpcs.client.ui;

/**
 * Pending-acknowledgement tracker for serverbound panel actions
 * (issue #201): a screen begins a pending state when it sends a mutation,
 * the state clears when the server's refreshed view payload arrives
 * ({@link #ack}), and auto-expires after a timeout so a rejection that only
 * arrives as a chat toast cannot wedge the UI forever.
 *
 * <p>Pure int/long state — unit-testable headless. Screens render the
 * {@link #label} and disable the triggering buttons while pending.
 */
public final class PendingAck {

    /** Default window after which an unacknowledged action is declared lost. */
    public static final long DEFAULT_TIMEOUT_MS = 4_000L;

    private String label;
    private long deadlineMillis;
    private java.util.UUID requestId;

    /**
     * Marks an action in flight from {@code nowMillis} until either
     * {@link #ack} or {@code nowMillis + timeoutMillis} — whichever first.
     */
    public void begin(String label, long nowMillis, long timeoutMillis) {
        // A blank label would arm an invisible pending state — treat as no-op.
        if (label == null || label.isBlank()) return;
        this.label = label;
        this.deadlineMillis = nowMillis + Math.max(0, timeoutMillis);
        this.requestId = null;
    }

    /**
     * Arms pending bound to the request id sent with the action (issue #219):
     * only a refresh echoing that id acks — a delayed refresh for an earlier,
     * timed-out attempt must not clear this attempt's pending state.
     */
    public void begin(String label, long nowMillis, long timeoutMillis, java.util.UUID requestId) {
        begin(label, nowMillis, timeoutMillis);
        if (label != null && !label.isBlank()) {
            this.requestId = requestId;
        }
    }

    /** Convenience for the request-bound {@link #begin} with {@link #DEFAULT_TIMEOUT_MS}. */
    public void begin(String label, long nowMillis, java.util.UUID requestId) {
        begin(label, nowMillis, DEFAULT_TIMEOUT_MS, requestId);
    }

    /** Convenience for {@link #begin} with {@link #DEFAULT_TIMEOUT_MS}. */
    public void begin(String label, long nowMillis) {
        begin(label, nowMillis, DEFAULT_TIMEOUT_MS);
    }

    /** Clears the pending state — call when the server payload arrives. */
    public void ack() {
        label = null;
        requestId = null;
    }

    /**
     * Correlated ack: clears pending only when the echoed request id matches
     * the armed attempt's id. Uncorrelated refreshes (unsolicited opens carry
     * no id, stale echoes carry an earlier attempt's) leave pending armed and
     * return {@code false}.
     */
    public boolean ack(java.util.UUID echoId) {
        if (requestId == null || echoId == null || !requestId.equals(echoId)) {
            return false;
        }
        ack();
        return true;
    }

    /** Whether the action is still in flight (unacknowledged, unexpired). */
    public boolean pending(long nowMillis) {
        return label != null && nowMillis < deadlineMillis;
    }

    /** The pending label, or {@code null} when not pending / expired. */
    public String label(long nowMillis) {
        return pending(nowMillis) ? label : null;
    }
}
