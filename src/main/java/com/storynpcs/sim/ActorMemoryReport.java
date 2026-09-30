package com.storynpcs.sim;

/**
 * Separates shared archetype cost (amortized once across all actors) from
 * incremental per-actor cost. DORMANT/UNLOADED actors must hold only their
 * incremental durable state — the {@code ≤32KiB} certification bound.
 */
public record ActorMemoryReport(
        int actorCount,
        long sharedArchetypeBytes,
        long incrementalBytesPerActor) {

    public long totalBytes() {
        return sharedArchetypeBytes + (incrementalBytesPerActor * (long) actorCount);
    }

    /** Incremental memory with archetype amortized out — the certified metric. */
    public long incrementalTotalBytes() {
        return incrementalBytesPerActor * (long) actorCount;
    }
}
