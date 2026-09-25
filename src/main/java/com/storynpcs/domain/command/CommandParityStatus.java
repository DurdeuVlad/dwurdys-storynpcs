package com.storynpcs.domain.command;

/**
 * Parity status of one target CustomNPCs command leaf against this codebase
 * (issue #85 — P9-3 command and suggestion parity).
 */
public enum CommandParityStatus {
    /** A StoryNPCs command exists that provides equivalent capability. */
    SUPPORTED,
    /** A conscious decision was made not to reproduce this command as-is. */
    INTENTIONAL_DEVIATION,
    /** No StoryNPCs equivalent exists yet; not yet triaged as a deviation. */
    UNVERIFIED
}
