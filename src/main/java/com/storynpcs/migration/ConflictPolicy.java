package com.storynpcs.migration;

/**
 * P11-1: how a definition ID collision with existing content resolves.
 * FAIL aborts the whole apply before any write; RENAME is deterministic.
 */
public enum ConflictPolicy {
    /** Any collision fails the import before a single write happens. */
    FAIL,
    /** Colliding documents are skipped; existing content wins. */
    SKIP,
    /** Colliding documents import under {@code id__import<N>} for the lowest free N. */
    RENAME,
    /** Colliding documents overwrite; the prior definition is snapshotted for rollback. */
    REPLACE
}
