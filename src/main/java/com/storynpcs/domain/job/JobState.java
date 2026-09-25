package com.storynpcs.domain.job;

/** Lifecycle state for a {@link JobAssignment} (issue #73). */
public enum JobState {
    /** Assigned but not yet started. */
    IDLE,
    RUNNING,
    PAUSED,
    /** Terminal — a stopped job must be reassigned (a fresh {@link JobAssignment}), not resumed. */
    STOPPED
}
