package com.storynpcs.client.ui.widgets;

/**
 * Pure pending-state machine for in-flight server actions (issue #199):
 * an action is "busy" from {@link #begin} until {@link #end} or the timeout
 * elapses — the timeout exists because a dropped clientbound reply must not
 * wedge the button forever. {@link BusyButton} renders it.
 */
public final class BusyState {

    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    private long busyUntilMillis = Long.MIN_VALUE;

    public boolean isPending(long nowMillis) {
        return nowMillis < busyUntilMillis;
    }

    /** Marks the action in-flight until {@code nowMillis + timeoutMillis}. */
    public void begin(long nowMillis, long timeoutMillis) {
        busyUntilMillis = nowMillis + timeoutMillis;
    }

    /** The server replied (success or failure) — clear the pending flag. */
    public void end() {
        busyUntilMillis = Long.MIN_VALUE;
    }

    /** Alias for {@link #end} — readable at expiry-check call sites. */
    public boolean expired(long nowMillis) {
        return !isPending(nowMillis);
    }
}
