package com.storynpcs.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.storynpcs.domain.script.ScriptDefinition;
import com.storynpcs.script.ScriptBudget.Meter;
import com.storynpcs.script.ScriptScheduler.DispatchOutcome;
import com.storynpcs.script.ScriptScheduler.ScriptHandle;
import com.storynpcs.script.api.ScriptContext;
import com.storynpcs.script.api.ScriptHostApi;
import com.storynpcs.service.StoryNpcsApplicationService;

/**
 * The script runtime (P9-2 / #84): owns registered {@link ScriptDefinition}s,
 * dispatches hooks through the {@link ScriptScheduler}'s budgeted windows,
 * and feeds named timers. Every hook invocation gets a fresh
 * {@link Meter} sized by its hook class, a fresh {@link ScriptHostApi} bound
 * to the dispatch {@link ScriptContext}, and the definition's authored
 * grants — never the script's own claims.
 *
 * <p>Registration compiles the script and fires {@link ScriptHook#INIT}
 * under a standard-budget dispatch, so a broken source fails into the
 * scheduler's failure/quarantine path rather than the loader's.</p>
 */
public final class ScriptRuntime implements ScriptHostApi.TimerSink, ScriptHostApi.LogSink {

    /** Per-script named-timer bound — timers are a resource like any other. */
    public static final int MAX_TIMERS_PER_SCRIPT = 8;

    private final StoryNpcsApplicationService service;
    private final ScriptScheduler scheduler;
    private final ScriptHost host;
    private final Consumer<String> logOutput;
    /** scriptId (namespaced) → live definition; replaced wholesale on reload. */
    private final Map<String, ScriptDefinition> registered = new LinkedHashMap<>();
    /** scriptId → (timer name → due tick). Timers are in-memory dispatch state. */
    private final Map<String, Map<String, Long>> timers = new ConcurrentHashMap<>();
    private long nowTick;

    public ScriptRuntime(StoryNpcsApplicationService service, ScriptScheduler scheduler,
                         Consumer<String> logOutput) {
        this(service, scheduler, new ScriptHost(), logOutput);
    }

    /** Injectable host seam — tests can substitute an instrumented sandbox. */
    public ScriptRuntime(StoryNpcsApplicationService service, ScriptScheduler scheduler,
                         ScriptHost host, Consumer<String> logOutput) {
        this.service = Objects.requireNonNull(service, "service");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.host = Objects.requireNonNull(host, "host");
        this.logOutput = Objects.requireNonNull(logOutput, "logOutput");
    }

    /**
     * Registers a definition: compiles it and fires {@code init} under a
     * standard-budget dispatch. Disabled definitions are retained in the
     * registry but never registered — the loader is the gate.
     */
    public synchronized boolean register(ScriptDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        String scriptId = definition.getId().toString();
        if (registered.containsKey(scriptId)) {
            return false;
        }
        UUID uuid = ScriptHostApi.actorUuidFor(scriptId);
        if (!scheduler.register(uuid, uuid)) {
            return false;
        }
        registered.put(scriptId, definition);
        // Absorb engine/compile cost at registration (load-time), not in the
        // first dispatched hook's wall-clock window.
        host.precompile(definition);
        if (definition.isEnabled() && definition.getHooks().contains(ScriptHook.INIT.jsName())) {
            dispatchOne(definition, ScriptHook.INIT,
                    context(ScriptHook.INIT, null, null, null, null, null, null));
        }
        return true;
    }

    public synchronized boolean unregister(String namespacedId) {
        boolean removed = registered.remove(namespacedId) != null;
        if (removed) {
            scheduler.unregister(ScriptHostApi.actorUuidFor(namespacedId));
            host.evict(namespacedId);
            timers.remove(namespacedId);
        }
        return removed;
    }

    /**
     * Definition reload (YAML re-read or canonical save): drops every
     * registered script, evicts compiled units, clears timers, and
     * re-registers enabled definitions. Bumped {@code version}s recompile;
     * unchanged definitions reuse the host's compiled-unit cache.
     */
    public synchronized int reload(java.util.Collection<ScriptDefinition> definitions) {
        registered.clear();
        host.evictAll();
        timers.clear();
        int count = 0;
        for (ScriptDefinition definition : definitions) {
            if (definition.isEnabled() && register(definition)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Fan-out dispatch: every enabled registered script that declares
     * {@code hook} receives one scheduler dispatch with its own meter.
     * Returns the per-script outcomes in registration order — diagnostics
     * surface deferrals, budget kills, and quarantine transitions.
     */
    public synchronized Map<String, DispatchOutcome> dispatch(
            ScriptHook hook, ScriptContext context) {
        Map<String, DispatchOutcome> outcomes = new LinkedHashMap<>();
        for (ScriptDefinition definition : List.copyOf(registered.values())) {
            if (!definition.getHooks().contains(hook.jsName())) {
                continue;
            }
            outcomes.put(definition.getId().toString(), dispatchOne(definition, hook, context));
        }
        return outcomes;
    }

    /**
     * NPC-scoped dispatch (entity hooks): only the scripts bound in the NPC
     * definition's {@code scripts} list receive the hook. Unknown or
     * unregistered ids are skipped — a missing reference never reaches the
     * sandbox.
     */
    public synchronized Map<String, DispatchOutcome> dispatchFor(
            ScriptHook hook, ScriptContext context,
            java.util.Collection<com.storynpcs.domain.common.NamespacedId> scriptIds) {
        Map<String, DispatchOutcome> outcomes = new LinkedHashMap<>();
        if (scriptIds == null || scriptIds.isEmpty()) {
            return outcomes;
        }
        for (var scriptId : scriptIds) {
            ScriptDefinition definition = registered.get(scriptId.toString());
            if (definition == null || !definition.getHooks().contains(hook.jsName())) {
                continue;
            }
            outcomes.put(scriptId.toString(), dispatchOne(definition, hook, context));
        }
        return outcomes;
    }

    /**
     * Server-tick timer drain — called from the mod's tick driver AFTER
     * {@link ScriptScheduler#beginTick()} opens the aggregate window, so due
     * timer dispatches spend against this tick's budget like any other hook.
     */
    public synchronized void tick(long gameTime) {
        nowTick = gameTime;
        List<Map.Entry<String, Map.Entry<String, Long>>> due = new ArrayList<>();
        for (var perScript : timers.entrySet()) {
            synchronized (perScript.getValue()) {
                perScript.getValue().entrySet().removeIf(entry -> {
                    if (entry.getValue() <= gameTime) {
                        due.add(Map.entry(perScript.getKey(), Map.entry(entry.getKey(), entry.getValue())));
                        return true;
                    }
                    return false;
                });
            }
        }
        for (var fired : due) {
            ScriptDefinition definition;
            synchronized (this) {
                definition = registered.get(fired.getKey());
            }
            if (definition == null) {
                continue;
            }
            dispatchOne(definition, ScriptHook.TIMER,
                    context(ScriptHook.TIMER, null, null, null, null,
                            fired.getValue().getKey(), null));
        }
    }

    // ── ScriptHostApi.TimerSink ──────────────────────────────────────────────

    @Override
    public boolean startTimer(String scriptId, String name, long delayTicks) {
        Map<String, Long> perScript = timers.computeIfAbsent(scriptId, k -> new ConcurrentHashMap<>());
        synchronized (perScript) {
            if (perScript.size() >= MAX_TIMERS_PER_SCRIPT && !perScript.containsKey(name)) {
                return false;
            }
            long due = nowTick + delayTicks;
            if (due < nowTick) due = Long.MAX_VALUE; // overflow guard
            perScript.put(name, due);
            return true;
        }
    }

    @Override
    public boolean cancelTimer(String scriptId, String name) {
        Map<String, Long> perScript = timers.get(scriptId);
        return perScript != null && perScript.remove(name) != null;
    }

    // ── ScriptHostApi.LogSink ────────────────────────────────────────────────

    @Override
    public void log(String scriptId, String line) {
        logOutput.accept("[StoryNPCs:script:" + scriptId + "] " + line);
    }

    // ── Observability + administration ───────────────────────────────────────

    /** Scheduler status for a registered script, or null if unknown. */
    public ScriptHandle status(String namespacedId) {
        return scheduler.handleOf(ScriptHostApi.actorUuidFor(namespacedId));
    }

    /** Operator recovery: unquarantine after a fix/reload. */
    public boolean unquarantine(String namespacedId) {
        return scheduler.unquarantine(ScriptHostApi.actorUuidFor(namespacedId));
    }

    /** Snapshot of registered script ids + scheduler status for `script list`. */
    public synchronized Map<String, ScriptScheduler.Status> statuses() {
        Map<String, ScriptScheduler.Status> out = new LinkedHashMap<>();
        for (String scriptId : registered.keySet()) {
            ScriptHandle handle = scheduler.handleOf(ScriptHostApi.actorUuidFor(scriptId));
            out.put(scriptId, handle == null ? null : handle.status());
        }
        return out;
    }

    /** Namespaced ids of registered scripts, in registration order. */
    public synchronized List<String> registeredIds() {
        return List.copyOf(registered.keySet());
    }

    public ScriptHandle handleOf(String namespacedId) {
        return scheduler.handleOf(ScriptHostApi.actorUuidFor(namespacedId));
    }

    public long tickNanosUsed() {
        return scheduler.tickNanosUsed();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private DispatchOutcome dispatchOne(ScriptDefinition definition, ScriptHook hook,
                                        ScriptContext context) {
        String scriptId = definition.getId().toString();
        UUID uuid = ScriptHostApi.actorUuidFor(scriptId);
        return scheduler.dispatch(uuid, hook, () -> {
            Meter meter = new Meter(ScriptBudget.forHook(hook));
            ScriptHostApi api = new ScriptHostApi(scriptId, context, meter,
                    java.util.Set.copyOf(definition.getCapabilities()),
                    service, this, this);
            host.invoke(definition, hook, api, context, meter);
        });
    }

    private static ScriptContext context(ScriptHook hook, String npcId, String playerUuid,
                                         String dialogueId, String questId,
                                         String timerName, String levelKey) {
        return new ScriptContext(hook.jsName(), npcId, playerUuid, dialogueId, questId,
                timerName, levelKey, null);
    }

    /** Builds a context for entity-bound hook dispatch. */
    public static ScriptContext entityContext(ScriptHook hook, String npcId,
                                              String playerUuid, String levelKey) {
        return context(hook, npcId, playerUuid, null, null, null, levelKey);
    }

    /** Builds a context for dialogue/quest hook dispatch. */
    public static ScriptContext subjectContext(ScriptHook hook, String npcId,
                                               String playerUuid, String dialogueId,
                                               String questId, String levelKey) {
        return context(hook, npcId, playerUuid, dialogueId, questId, null, levelKey);
    }

    /** Operator-triggered context: {@code script trigger} args ride here. */
    public static ScriptContext triggerContext(ScriptHook hook, String npcId,
                                               String playerUuid, String args) {
        return new ScriptContext(hook.jsName(), npcId, playerUuid, null, null, null, null,
                args != null && args.length() > 256 ? args.substring(0, 256) : args);
    }

    /**
     * Single-script dispatch — the {@code script trigger} surface. No-op with
     * a {@code Skipped} outcome when the id is unregistered, disabled, or
     * does not declare the hook.
     */
    public synchronized DispatchOutcome dispatchScript(String namespacedId,
                                                       ScriptHook hook,
                                                       ScriptContext context) {
        ScriptDefinition definition = registered.get(namespacedId);
        if (definition == null || !definition.getHooks().contains(hook.jsName())) {
            return new DispatchOutcome.Skipped(
                    definition == null ? "script not registered" : "hook not declared");
        }
        return dispatchOne(definition, hook, context);
    }
}
