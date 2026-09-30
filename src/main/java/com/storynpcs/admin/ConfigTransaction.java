package com.storynpcs.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.storynpcs.domain.common.ValidationResult;

/**
 * Atomic config change (P9-4): validate → stage → commit-or-rollback with a
 * revision check. A failed validation or a stale revision leaves the prior
 * config untouched; nothing is applied partially.
 */
public final class ConfigTransaction {

    public enum Stage { OPEN, STAGED, COMMITTED, ROLLED_BACK }

    private final Map<String, String> current;
    private final Function<Map<String, String>, ValidationResult> validator;
    private Map<String, String> staged;
    private Stage stage = Stage.OPEN;
    private long revision;

    public ConfigTransaction(Map<String, String> current, long revision,
                             Function<Map<String, String>, ValidationResult> validator) {
        this.current = new LinkedHashMap<>(
                java.util.Objects.requireNonNull(current, "current"));
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.revision = revision;
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
    }

    /** Stage a change set — merged over the current config. */
    public void stage(Map<String, String> changes) {
        if (stage != Stage.OPEN && stage != Stage.STAGED) {
            throw new IllegalStateException("cannot stage on a " + stage + " transaction");
        }
        staged = new LinkedHashMap<>(current);
        staged.putAll(changes);
        stage = Stage.STAGED;
    }

    /**
     * Commit: validator must pass and the expected revision must match.
     * On either failure the transaction rolls back — the live map never
     * received the staged change.
     */
    public CommitOutcome commit(long expectedRevision) {
        if (stage != Stage.STAGED) {
            return new CommitOutcome(false, "nothing staged", revision);
        }
        if (expectedRevision != revision) {
            stage = Stage.ROLLED_BACK;
            return new CommitOutcome(false, "stale revision: expected " + expectedRevision
                    + ", actual " + revision, revision);
        }
        ValidationResult result = validator.apply(java.util.Collections.unmodifiableMap(staged));
        if (result.hasErrors()) {
            stage = Stage.ROLLED_BACK;
            return new CommitOutcome(false, "validation failed: " + result.formatReport(3), revision);
        }
        current.clear();
        current.putAll(staged);
        revision++;
        stage = Stage.COMMITTED;
        return new CommitOutcome(true, "committed", revision);
    }

    /** Explicit rollback — drops the staged change set. */
    public void rollback() {
        staged = null;
        stage = Stage.ROLLED_BACK;
    }

    public record CommitOutcome(boolean committed, String detail, long newRevision) {}

    public Map<String, String> current() { return Map.copyOf(current); }
    public Stage stage() { return stage; }
    public long revision() { return revision; }
}
