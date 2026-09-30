package com.storynpcs.admin;

import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueChoiceProtocol;
import com.storynpcs.script.ScriptScheduler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded live runtime configuration (P9-4). This is the production consumer
 * of {@link ConfigTransaction}: every tunable change stages through the
 * transaction's validate → commit-or-rollback path under this store's lock,
 * so a multi-key update is atomic and a failed validation or stale revision
 * leaves the live map untouched.
 *
 * <p>Consumers resolve values per call, so a committed change takes effect on
 * the next use — dialogue token issuance, script dispatch budgets. Keys absent
 * from {@link #KEYS} are rejected by {@link #validate}.</p>
 */
public final class RuntimeTunables {

    /** Descriptor for one tunable: parse bound + default. */
    private record Tunable(long min, long max, long defaultValue) {}

    public static final String DIALOGUE_TOKEN_EXPIRY_TICKS = "dialogue.token.expiry.ticks";
    public static final String DIALOGUE_TOKEN_MAX_PENDING = "dialogue.token.max.pending";
    public static final String SCRIPT_TICK_AGGREGATE_NANOS = "script.tick.aggregate.nanos";
    public static final String SCRIPT_TICK_HOOK_NANOS = "script.hook.tick.nanos";
    public static final String SCRIPT_STANDARD_HOOK_NANOS = "script.hook.standard.nanos";

    private static final Map<String, Tunable> KEYS;
    static {
        Map<String, Tunable> keys = new LinkedHashMap<>();
        keys.put(DIALOGUE_TOKEN_EXPIRY_TICKS,
                new Tunable(20L, 72_000L, DialogueChoiceProtocol.DEFAULT_EXPIRY_TICKS));
        keys.put(DIALOGUE_TOKEN_MAX_PENDING,
                new Tunable(16L, 65_536L, DialogueChoiceProtocol.DEFAULT_MAX_PENDING));
        keys.put(SCRIPT_TICK_AGGREGATE_NANOS,
                new Tunable(500_000L, 100_000_000L, ScriptScheduler.TICK_AGGREGATE_NANOS));
        keys.put(SCRIPT_TICK_HOOK_NANOS,
                new Tunable(100_000L, 50_000_000L, ScriptScheduler.TICK_HOOK_NANOS));
        keys.put(SCRIPT_STANDARD_HOOK_NANOS,
                new Tunable(100_000L, 50_000_000L, ScriptScheduler.STANDARD_HOOK_NANOS));
        KEYS = Map.copyOf(keys);
    }

    private final Map<String, String> live = new LinkedHashMap<>();
    private long revision;

    public RuntimeTunables() {
        for (var entry : KEYS.entrySet()) {
            live.put(entry.getKey(), Long.toString(entry.getValue().defaultValue()));
        }
    }

    /** Immutable view of every live tunable. */
    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(live);
    }

    /** Current durable-style revision — bumped once per committed transaction. */
    public synchronized long revision() {
        return revision;
    }

    /** The validation contract applied inside {@link ConfigTransaction#commit}. */
    public static ValidationResult validate(Map<String, String> candidate) {
        ValidationResult result = ValidationResult.valid();
        for (var entry : candidate.entrySet()) {
            Tunable bound = KEYS.get(entry.getKey());
            if (bound == null) {
                result.addError("UNKNOWN_TUNABLE",
                        "Unknown runtime tunable: " + entry.getKey());
                continue;
            }
            long value;
            try {
                value = Long.parseLong(entry.getValue() == null ? "" : entry.getValue().trim());
            } catch (NumberFormatException malformed) {
                result.addError("INVALID_TUNABLE_VALUE",
                        "Tunable " + entry.getKey() + " must be an integer, got '" + entry.getValue() + "'");
                continue;
            }
            if (value < bound.min() || value > bound.max()) {
                result.addError("TUNABLE_OUT_OF_RANGE",
                        "Tunable " + entry.getKey() + " must be in [" + bound.min() + "," + bound.max()
                                + "], got " + value);
            }
        }
        return result;
    }

    /**
     * Stage + commit a change set through {@link ConfigTransaction} under this
     * store's lock — the committed map swaps in only after the transaction
     * reports success, so readers never observe a partially applied update.
     */
    public synchronized ConfigTransaction.CommitOutcome applyChanges(
            Map<String, String> changes, long expectedRevision) {
        var transaction = new ConfigTransaction(live, revision, RuntimeTunables::validate);
        transaction.stage(changes == null ? Map.of() : changes);
        ConfigTransaction.CommitOutcome outcome = transaction.commit(expectedRevision);
        if (outcome.committed()) {
            live.clear();
            live.putAll(transaction.current());
            revision = transaction.revision();
        }
        return outcome;
    }

    /**
     * Typed live read used by consumers: resolves the current value each call
     * and falls back to the key default if the stored text cannot parse —
     * validated commits keep values in range, the fallback is a belt for
     * hand-edited or legacy state.
     */
    public long longValue(String key) {
        Tunable bound = KEYS.get(key);
        long fallback = bound != null ? bound.defaultValue() : 0L;
        String raw;
        synchronized (this) {
            raw = live.get(key);
        }
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
