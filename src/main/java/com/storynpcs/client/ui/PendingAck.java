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

    /**
     * Marks an action in flight from {@code nowMillis} until either
     * {@link #ack} or {@code nowMillis + timeoutMillis} — whichever first.
     */
    public void begin(String label, long nowMillis, long timeoutMillis) {
        // A blank label would arm an invisible pending state — treat as no-op.
        if (label == null || label.isBlank()) return;
        this.label = label;
        this.deadlineMillis = nowMillis + Math.max(0, timeoutMillis);
    }

    /** Convenience for {@link #begin} with {@link #DEFAULT_TIMEOUT_MS}. */
    public void begin(String label, long nowMillis) {
        begin(label, nowMillis, DEFAULT_TIMEOUT_MS);
    }

    /** Clears the pending state — call when the server payload arrives. */
    public void ack() {
        label = null;
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
