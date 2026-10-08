package com.storynpcs.client.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-attempt request ids for serverbound mutations (issue #204 review):
 * a screen keeps one id per attempt key so a retry of an action whose
 * response was lost replays the SAME id (the server's durable journal then
 * classifies it as REPLAYED instead of double-applying), while {@link #ack}
 * — called when the refreshed view arrives — clears the map so the NEXT,
 * distinct action mints a fresh id.
 *
 * <p>The lifetime is the load-bearing part: if ids outlived their ack, a
 * second buy/deposit of the same target would hit the server's COMMITTED
 * record, produce a fake success toast, and never execute.
 *
 * <p>Pure state — unit-testable headless.
 */
public final class AttemptIds {

    private final Map<String, UUID> ids = new HashMap<>();

    /** The id for this attempt; stable until {@link #ack}. */
    public UUID idFor(String key) {
        return ids.computeIfAbsent(key, ignored -> UUID.randomUUID());
    }

    /** The pending attempts resolved — the next {@link #idFor} mints anew. */
    public void ack() {
        ids.clear();
    }
}
