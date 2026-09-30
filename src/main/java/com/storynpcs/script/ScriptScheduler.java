package com.storynpcs.script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded script scheduler (P9-2). Per-tick aggregate budget caps total script
 * time; a script that exceeds its budget repeatedly is quarantined — stopped
 * and observable, never crashing the server.
 */
public final class ScriptScheduler {

    /** Aggregate script budget per server tick: 4 ms. */
    public static final long TICK_AGGREGATE_NANOS = 4_000_000L;
    /** TICK hooks get 1 ms each, other hooks 5 ms. */
    public static final long TICK_HOOK_NANOS = 1_000_000L;
    public static final long STANDARD_HOOK_NANOS = 5_000_000L;
    public static final int MAX_REGISTERED_SCRIPTS = 4_096;

    private static final int QUARANTINE_AFTER_FAILURES = 3;

    public enum Status { ACTIVE, QUARANTINED }

    public record ScriptHandle(UUID scriptId, UUID actorId, Status status, int consecutiveFailures) {}

    public sealed interface DispatchOutcome {
        record Completed() implements DispatchOutcome {}
        record Skipped(String reason) implements DispatchOutcome {}
        record OverBudget(String metric) implements DispatchOutcome {}
    }

    private final Map<UUID, ScriptHandle> scripts = new LinkedHashMap<>();
    private final com.storynpcs.admin.RuntimeTunables tunables; // nullable — live budget source
    private long tickNanosUsed;

    public ScriptScheduler() {
        this(null);
    }

    /** Live-budget scheduler: budget constants resolve per dispatch. */
    public ScriptScheduler(com.storynpcs.admin.RuntimeTunables tunables) {
        this.tunables = tunables;
    }

    private long tickAggregateNanos() {
        return tunables != null
                ? tunables.longValue(com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS)
                : TICK_AGGREGATE_NANOS;
    }

    private long tickHookNanos() {
        return tunables != null
                ? tunables.longValue(com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_HOOK_NANOS)
                : TICK_HOOK_NANOS;
    }

    private long standardHookNanos() {
        return tunables != null
                ? tunables.longValue(com.storynpcs.admin.RuntimeTunables.SCRIPT_STANDARD_HOOK_NANOS)
                : STANDARD_HOOK_NANOS;
    }

    public boolean register(UUID scriptId, UUID actorId) {
        java.util.Objects.requireNonNull(scriptId, "scriptId");
        java.util.Objects.requireNonNull(actorId, "actorId");
        if (!scripts.containsKey(scriptId) && scripts.size() >= MAX_REGISTERED_SCRIPTS) {
            return false;
        }
        scripts.put(scriptId, new ScriptHandle(scriptId, actorId, Status.ACTIVE, 0));
        return true;
    }

    public boolean unregister(UUID scriptId) {
        return scripts.remove(scriptId) != null;
    }

    /** Begin a server-tick window — resets the aggregate budget. */
    public void beginTick() {
        tickNanosUsed = 0;
    }

    /**
     * Dispatch a hook with budget accounting. {@code runner} performs the actual
     * work inside a {@link ScriptBudget.Meter}; failures increment the script's
     * failure count and quarantine it after repeated over-budget runs.
     *
     * <p>The wall-clock elapsed time is measured around the dispatch: a hook
     * that never touches the meter still counts against its budget, so a
     * callback that blocks past its allocation is charged a failure and can be
     * quarantined — enforcement does not rely on callback cooperation.</p>
     */
    public DispatchOutcome dispatch(UUID scriptId, ScriptHook hook, Runnable runner) {
        ScriptHandle handle = scripts.get(scriptId);
        if (handle == null) {
            return new DispatchOutcome.Skipped("unknown script");
        }
        if (handle.status() == Status.QUARANTINED) {
            return new DispatchOutcome.Skipped("quarantined");
        }
        long hookBudget = hook.budgetClass() == ScriptHook.BudgetClass.TICK
                ? tickHookNanos() : standardHookNanos();
        // Per-tick aggregate caps spend, not hook size — a hook with a larger
        // budget charges what remains this tick and may span multiple ticks.
        long remaining = tickAggregateNanos() - tickNanosUsed;
        if (remaining <= 0) {
            return new DispatchOutcome.Skipped("aggregate tick budget exhausted");
        }
        tickNanosUsed += Math.min(hookBudget, remaining);
        long startedNanos = System.nanoTime();
        try {
            runner.run();
            long elapsedNanos = System.nanoTime() - startedNanos;
            if (elapsedNanos > hookBudget) {
                // Wall-clock enforcement: the callback completed but consumed
                // more real time than its class allows — a budget failure even
                // though the body finished.
                return recordBudgetFailure(scriptId, handle, "elapsed_nanos");
            }
            scripts.put(scriptId, new ScriptHandle(scriptId, handle.actorId(), Status.ACTIVE, 0));
            return new DispatchOutcome.Completed();
        } catch (ScriptBudget.BudgetExceeded over) {
            return recordBudgetFailure(scriptId, handle, over.metric());
        } catch (RuntimeException failure) {
            int failures = handle.consecutiveFailures() + 1;
            Status next = failures >= QUARANTINE_AFTER_FAILURES ? Status.QUARANTINED : Status.ACTIVE;
            scripts.put(scriptId, new ScriptHandle(scriptId, handle.actorId(), next, failures));
            return new DispatchOutcome.Skipped("script threw: " + failure.getMessage());
        }
    }

    private DispatchOutcome recordBudgetFailure(UUID scriptId, ScriptHandle handle, String metric) {
        int failures = handle.consecutiveFailures() + 1;
        Status next = failures >= QUARANTINE_AFTER_FAILURES ? Status.QUARANTINED : Status.ACTIVE;
        scripts.put(scriptId, new ScriptHandle(scriptId, handle.actorId(), next, failures));
        return new DispatchOutcome.OverBudget(metric);
    }

    public ScriptHandle handleOf(UUID scriptId) {
        return scripts.get(scriptId);
    }

    public long tickNanosUsed() {
        return tickNanosUsed;
    }

    public boolean unquarantine(UUID scriptId) {
        ScriptHandle h = scripts.get(scriptId);
        if (h == null || h.status() != Status.QUARANTINED) {
            return false;
        }
        scripts.put(scriptId, new ScriptHandle(scriptId, h.actorId(), Status.ACTIVE, 0));
        return true;
    }
}
