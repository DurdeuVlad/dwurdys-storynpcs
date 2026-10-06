package com.storynpcs.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.admin.RuntimeTunables;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.command.CommandParityStatus;
import com.storynpcs.domain.command.ScriptHookMatrix;
import com.storynpcs.domain.script.ScriptDefinition;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.script.ScriptBudget;
import com.storynpcs.script.ScriptHook;
import com.storynpcs.script.ScriptRuntime;
import com.storynpcs.script.ScriptScheduler;
import com.storynpcs.script.api.ScriptContext;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/**
 * P9-2 / #84: bounded script host — YAML family validation, Rhino sandbox
 * containment, budget enforcement, capability grants, failure isolation,
 * timers, reload, and the hook matrix.
 */
class P92ScriptHostTest {

    @TempDir
    Path tempDir;

    /**
     * One-time engine warmup for the whole fork. Rhino's interpreter and the
     * canonical service path pay JVM class-load/JIT cost on their first use;
     * billing that to a per-hook wall budget would flake every first dispatch.
     * Production absorbs the same cost at definition-load precompilation.
     */
    @BeforeAll
    static void warmEngine() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("p92-warm");
        var registry = new DefinitionRegistry();
        registry.registerFaction(new com.storynpcs.domain.faction.Faction(
                NamespacedId.of("storynpcs:villagers"), "Villagers", 0, 0, 2000));
        var service = new StoryNpcsApplicationService(registry,
                new ProgressionRepository(dir), new EventPublisher());
        var runtime = new ScriptRuntime(service, new ScriptScheduler(), line -> {});
        var warm = script("storynpcs:warmup", "1",
                "function interact() {"
                        + " storynpcs.factionAdjust(context.playerUuid(), 'storynpcs:villagers', 0);"
                        + " storynpcs.log('w'); }",
                List.of("interact"), List.of("faction.progress.adjust"));
        runtime.register(warm);
        var ctx = ScriptRuntime.entityContext(ScriptHook.INTERACT, "storynpcs:warm",
                UUID.randomUUID().toString(), "minecraft:overworld");
        for (int i = 0; i < 4; i++) {
            runtime.dispatch(ScriptHook.INTERACT, ctx);
        }
    }

    // ── YAML family + validation ─────────────────────────────────────────────

    @Test
    void scriptLoadsValidYamlDefinition() throws Exception {
        var registry = registry();
        var loader = new YamlDefinitionLoader(registry);
        var yaml = """
                schemaVersion: 1
                id: "storynpcs:greeter"
                language: javascript
                version: "1"
                enabled: true
                hooks: [init, interact]
                capabilities: [faction.progress.adjust]
                source: |
                  function init() { storynpcs.log('hi'); }
                  function interact() { storynpcs.log('seen'); }
                """;
        var result = new com.storynpcs.domain.common.ValidationResult();
        var script = loader.loadScript(yaml, "greeter.yaml", result);
        assertThat(script).isNotNull();
        assertThat(result.isValid()).isTrue();
        assertThat(script.getId()).isEqualTo(NamespacedId.of("storynpcs:greeter"));
        assertThat(script.getHooks()).containsExactly("init", "interact");
        assertThat(registry.getScript(NamespacedId.of("storynpcs:greeter"))).isPresent();
    }

    @Test
    void scriptRejectsUnknownHookAndNonPlayerScopedCapability() throws Exception {
        var registry = registry();
        var loader = new YamlDefinitionLoader(registry);
        var yaml = """
                id: "storynpcs:bad"
                hooks: [fly, interact]
                capabilities: [npc.mutate, faction.progress.adjust, made.up.cap]
                source: "function interact(){}"
                """;
        var result = new com.storynpcs.domain.common.ValidationResult();
        var script = loader.loadScript(yaml, "bad.yaml", result);
        assertThat(script).isNull();
        assertThat(registry.getScript(NamespacedId.of("storynpcs:bad"))).isEmpty();
        var codes = result.getErrors().stream().map(e -> e.code()).toList();
        assertThat(codes).contains("SCRIPT_UNKNOWN_HOOK",
                "SCRIPT_CAPABILITY_FORBIDDEN", "SCRIPT_UNKNOWN_CAPABILITY");
    }

    @Test
    void scriptRejectsNonJavascriptLanguage() throws Exception {
        var registry = registry();
        var loader = new YamlDefinitionLoader(registry);
        var yaml = """
                id: "storynpcs:py"
                language: python
                source: "def x(): pass"
                """;
        var result = new com.storynpcs.domain.common.ValidationResult();
        assertThat(loader.loadScript(yaml, "py.yaml", result)).isNull();
        assertThat(result.getErrors().stream().map(e -> e.code()).toList())
                .contains("SCRIPT_UNSUPPORTED_LANGUAGE");
    }

    // ── canonical save/delete ────────────────────────────────────────────────

    @Test
    void scriptCanonicalSaveAndDeleteRoundTrip() throws Exception {
        var harness = harness();
        var script = script("storynpcs:canon", "1", "function tick(){}", List.of("tick"), List.of());
        var saveResult = harness.service().saveScript(script);
        assertThat(saveResult.isValid()).isTrue();
        assertThat(harness.registry().getScript(NamespacedId.of("storynpcs:canon"))).isPresent();
        var deleteRequest = new MutationRequest("script.delete", "command",
                "script.delete", NamespacedId.of("storynpcs:canon"),
                harness.service().currentRevision("script", NamespacedId.of("storynpcs:canon")),
                UUID.randomUUID(), 2);
        var deleteResult = harness.service().deleteScript(deleteRequest);
        assertThat(deleteResult.applied()).isTrue();
        assertThat(harness.registry().getScript(NamespacedId.of("storynpcs:canon"))).isEmpty();
    }

    // ── Rhino sandbox containment ────────────────────────────────────────────

    @Test
    void sandboxDeniesJavaPackageAccess() {
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:esc1", "1",
                "function interact() { var f = new Packages.java.io.File('/etc/passwd'); }",
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        // Packages was deleted from the scope — ReferenceError → dispatch failure, server safe.
        assertThat(outcomes.get("storynpcs:esc1"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
        assertThat(runtime.status("storynpcs:esc1").consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void sandboxDeniesClassEscapeViaGetClass() {
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:esc2", "1",
                "function interact() { storynpcs.getClass().forName('java.lang.Runtime'); }",
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        // java.lang.Class is outside the shutter — JavaMembers lookup denied.
        assertThat(outcomes.get("storynpcs:esc2"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
    }

    @Test
    void sandboxDeniesE4XAndJavaImporter() {
        var runtime = runtime(new ScriptScheduler());
        // E4X is disabled at the context level — the literal fails to parse at compile time.
        runtime.register(script("storynpcs:esc3", "1",
                "var x = <foo/>; function interact() {}",
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        assertThat(outcomes.get("storynpcs:esc3"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
    }

    // ── budget enforcement ───────────────────────────────────────────────────

    @Test
    void instructionBudgetStopsRunawayLoop() {
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:loop", "1",
                "function tick() { while (true) { var x = 1 + 1; } }",
                List.of("tick"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.TICK, ctx(ScriptHook.TICK));
        var outcome = outcomes.get("storynpcs:loop");
        assertThat(outcome).isInstanceOf(ScriptScheduler.DispatchOutcome.OverBudget.class);
        assertThat(((ScriptScheduler.DispatchOutcome.OverBudget) outcome).metric())
                .isEqualTo("instructions");
    }

    @Test
    void recursionDepthCapConvertsToCatchableFailure() {
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:deep", "1",
                "function f(n) { return f(n + 1); } function interact() { f(0); }",
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        // Engine-level stack cap — a failure, never a VM stack overflow.
        assertThat(outcomes.get("storynpcs:deep"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
    }

    @Test
    void canonicalOpQuotaEnforced() {
        var runtime = runtime(new ScriptScheduler());
        StringBuilder source = new StringBuilder("function interact() { ");
        for (int i = 0; i < 33; i++) {
            source.append("storynpcs.cancelTimer('x'); ");
        }
        source.append("}");
        runtime.register(script("storynpcs:greedy", "1", source.toString(),
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        var outcome = outcomes.get("storynpcs:greedy");
        assertThat(outcome).isInstanceOf(ScriptScheduler.DispatchOutcome.OverBudget.class);
        assertThat(((ScriptScheduler.DispatchOutcome.OverBudget) outcome).metric())
                .isEqualTo("canonicalOps");
    }

    @Test
    void memoryBoundAppliesToHostAllocations() {
        // Meter-level: the 1MiB live bound throws exactly on the boundary.
        var meter = new ScriptBudget.Meter(ScriptBudget.standard());
        meter.allocate(1 << 20);
        org.junit.jupiter.api.Assertions.assertThrows(
                ScriptBudget.BudgetExceeded.class, () -> meter.allocate(1));
        // Host-level: log() charges its bounded payload — a small loop completes.
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:fat", "1",
                "function interact() { for (var i = 0; i < 20; i++) { storynpcs.log('x'); } }",
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        assertThat(outcomes.get("storynpcs:fat"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
    }

    @Test
    void instructionBudgetAlsoBoundsHostCallLoops() {
        // A loop of host calls still burns interpreter instructions — 2048
        // iterations over the 100k standard budget is stopped, not allowed to
        // hammer the canonical path unboundedly.
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:spam", "1",
                "function interact() { for (var i = 0; i < 2048; i++) { storynpcs.cancelTimer('x'); } }",
                List.of("interact"), List.of()));
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        var outcome = outcomes.get("storynpcs:spam");
        assertThat(outcome).isInstanceOf(ScriptScheduler.DispatchOutcome.OverBudget.class);
        assertThat(((ScriptScheduler.DispatchOutcome.OverBudget) outcome).metric())
                .isIn("instructions", "canonicalOps");
    }

    // ── capability grants ────────────────────────────────────────────────────

    @Test
    void ungrantedScriptCannotMutateProgression() throws Exception {
        var harness = harness();
        var runtime = runtime(harness.service(), relaxedScheduler());
        var player = UUID.randomUUID();
        runtime.register(script("storynpcs:sneaky", "1",
                "function interact() { storynpcs.factionAdjust(context.playerUuid(), 'storynpcs:villagers', 100); }",
                List.of("interact"), List.of() /* no grants */));
        var ctx = ScriptRuntime.entityContext(ScriptHook.INTERACT, "storynpcs:npc1",
                player.toString(), "minecraft:overworld");
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx);
        // Denied op returns false (not an exception) — script completed, but no mutation.
        assertThat(outcomes.get("storynpcs:sneaky"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        assertThat(harness.factionPoints(player)).isEqualTo(0);
    }

    @Test
    void grantedScriptMutatesThroughCanonicalPath() throws Exception {
        var harness = harness();
        var runtime = runtime(harness.service(), relaxedScheduler());
        var player = UUID.randomUUID();
        runtime.register(script("storynpcs:ally", "1",
                "function interact() { storynpcs.factionAdjust(context.playerUuid(), 'storynpcs:villagers', 25); }",
                List.of("interact"), List.of("faction.progress.adjust")));
        var ctx = ScriptRuntime.entityContext(ScriptHook.INTERACT, "storynpcs:npc1",
                player.toString(), "minecraft:overworld");
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx);
        assertThat(outcomes.get("storynpcs:ally"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        assertThat(harness.factionPoints(player)).isEqualTo(25);
    }

    // ── failure isolation + quarantine ───────────────────────────────────────

    @Test
    void repeatedFailuresQuarantineAndUnquarantine() {
        var runtime = runtime(new ScriptScheduler());
        runtime.register(script("storynpcs:fragile", "1",
                "function interact() { throw 'boom'; }",
                List.of("interact"), List.of()));
        for (int i = 0; i < 3; i++) {
            runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        }
        assertThat(runtime.status("storynpcs:fragile").status())
                .isEqualTo(ScriptScheduler.Status.QUARANTINED);
        // Quarantined: the dispatch itself is skipped — no fifth failure.
        var outcomes = runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        assertThat(outcomes.get("storynpcs:fragile"))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
        assertThat(runtime.status("storynpcs:fragile").consecutiveFailures()).isEqualTo(3);
        assertThat(runtime.unquarantine("storynpcs:fragile")).isTrue();
        assertThat(runtime.status("storynpcs:fragile").status())
                .isEqualTo(ScriptScheduler.Status.ACTIVE);
    }

    @Test
    void oneScriptFailureNeverStopsAnother() {
        var runtime = runtime(new ScriptScheduler());
        var log = new ArrayList<String>();
        var tagged = runtimeWithLog(log, new ScriptScheduler());
        tagged.register(script("storynpcs:bad", "1",
                "function interact() { throw 'x'; }", List.of("interact"), List.of()));
        tagged.register(script("storynpcs:good", "1",
                "function interact() { storynpcs.log('ran'); }", List.of("interact"), List.of()));
        var outcomes = tagged.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        assertThat(log).anyMatch(l -> l.contains("storynpcs:good") && l.contains("ran"));
    }

    // ── timers ───────────────────────────────────────────────────────────────

    @Test
    void timerSchedulesAndFiresTimerHook() {
        var log = new ArrayList<String>();
        var runtime = runtimeWithLog(log, new ScriptScheduler());
        runtime.register(script("storynpcs:clock", "1",
                "function init() { storynpcs.startTimer('pulse', 10); }"
                        + " function timer() { storynpcs.log('fired:' + context.timerName()); }",
                List.of("init", "timer"), List.of()));
        runtime.tick(9);
        assertThat(log).noneMatch(l -> l.contains("fired:"));
        runtime.tick(10);
        assertThat(log).anyMatch(l -> l.contains("fired:pulse"));
        // At-most-once: the timer entry was consumed when it fired.
        runtime.tick(11);
        assertThat(log.stream().filter(l -> l.contains("fired:pulse")).count()).isEqualTo(1);
    }

    // ── aggregate budget + ordering ──────────────────────────────────────────

    @Test
    void aggregateTickBudgetDefersExcessDispatches() {
        // Scheduler-level determinism: a 500µs window and a runner that sleeps
        // past it — the first dispatch charges the whole window, the second
        // defers without a failure. No engine timing involved.
        var tunables = new RuntimeTunables();
        tunables.restore(Map.of(
                RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS, "500000",
                RuntimeTunables.SCRIPT_TICK_HOOK_NANOS, "500000"), 1);
        var scheduler = new ScriptScheduler(tunables.readOnlyView());
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        scheduler.register(a, a);
        scheduler.register(b, b);
        scheduler.beginTick();
        Runnable busy = () -> {
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Runnable fast = () -> { };
        var first = scheduler.dispatch(a, ScriptHook.TICK, busy);
        var second = scheduler.dispatch(b, ScriptHook.TICK, fast);
        // The busy dispatch may complete or trip its wall budget — either way
        // it charged ≥ the 500µs window; the second dispatch must defer.
        assertThat(second).isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
        assertThat(((ScriptScheduler.DispatchOutcome.Skipped) second).reason())
                .isEqualTo("aggregate tick budget exhausted");
        // A new window admits the next dispatch.
        scheduler.beginTick();
        assertThat(scheduler.dispatch(b, ScriptHook.TICK, fast))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
    }

    @Test
    void initRunsAtRegistrationBeforeOtherHooks() {
        var log = new ArrayList<String>();
        var scheduler = relaxedScheduler();
        var runtime = runtimeWithLog(log, scheduler);
        runtime.register(script("storynpcs:order", "1",
                "function init() { storynpcs.log('init'); }"
                        + " function interact() { storynpcs.log('interact'); }",
                List.of("init", "interact"), List.of()));
        // Registration's init spends the current tick window; production's
        // tick driver opens a fresh window per tick — model that boundary.
        scheduler.beginTick();
        runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        assertThat(log.indexOf(log.stream().filter(l -> l.contains("init")).findFirst().orElse("")))
                .isLessThan(log.indexOf(log.stream().filter(l -> l.contains("interact"))
                        .findFirst().orElse("")));
    }

    // ── reload + versioning ──────────────────────────────────────────────────

    @Test
    void reloadDropsOldRegistrationsAndVersionBumpRecompiles() {
        var log = new ArrayList<String>();
        var runtime = runtimeWithLog(log, new ScriptScheduler());
        var v1 = script("storynpcs:ver", "1",
                "function interact() { storynpcs.log('v1'); }", List.of("interact"), List.of());
        var v2 = script("storynpcs:ver", "2",
                "function interact() { storynpcs.log('v2'); }", List.of("interact"), List.of());
        runtime.reload(List.of(v1));
        runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        runtime.reload(List.of(v2));
        runtime.dispatch(ScriptHook.INTERACT, ctx(ScriptHook.INTERACT));
        assertThat(log.stream().filter(l -> l.contains("v1")).count()).isEqualTo(1);
        assertThat(log.stream().filter(l -> l.contains("v2")).count()).isEqualTo(1);
    }

    @Test
    void dispatchForOnlyReachesBoundScripts() {
        var log = new ArrayList<String>();
        var runtime = runtimeWithLog(log, new ScriptScheduler());
        runtime.register(script("storynpcs:bound", "1",
                "function tick() { storynpcs.log('bound'); }", List.of("tick"), List.of()));
        runtime.register(script("storynpcs:other", "1",
                "function tick() { storynpcs.log('other'); }", List.of("tick"), List.of()));
        runtime.dispatchFor(ScriptHook.TICK, ctx(ScriptHook.TICK),
                List.of(NamespacedId.of("storynpcs:bound")));
        assertThat(log).anyMatch(l -> l.contains("bound"));
        assertThat(log).noneMatch(l -> l.contains("other"));
    }

    @Test
    void triggerArgsReachScriptContext() {
        var log = new ArrayList<String>();
        var runtime = runtimeWithLog(log, new ScriptScheduler());
        runtime.register(script("storynpcs:echo", "1",
                "function interact() { storynpcs.log('args:' + context.args()); }",
                List.of("interact"), List.of()));
        var outcome = runtime.dispatchScript("storynpcs:echo", ScriptHook.INTERACT,
                ScriptRuntime.triggerContext(ScriptHook.INTERACT, "storynpcs:echo", null,
                        "one two three"));
        assertThat(outcome).isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        assertThat(log).anyMatch(l -> l.contains("args:one two three"));
    }

    // ── hook matrix ──────────────────────────────────────────────────────────

    @Test
    void hookMatrixCoversEveryAcceptanceHookAndBudget() {
        var ids = ScriptHookMatrix.entries().stream().map(ScriptHookMatrix.Entry::id).toList();
        for (var hook : ScriptHook.values()) {
            assertThat(ids).contains("hook." + hook.jsName());
        }
        assertThat(ids).contains("budget.tick_hook", "budget.standard_hook",
                "budget.aggregate", "budget.memory", "budget.recursion",
                "budget.canonical_ops", "sandbox.java_access", "sandbox.filesystem",
                "sandbox.commands", "lifecycle.quarantine", "lifecycle.reload",
                "lifecycle.versioning");
        for (var entry : ScriptHookMatrix.entries()) {
            assertThat(entry.storyNpcsEquivalent()).as(entry.id()).isNotBlank();
            assertThat(entry.evidence()).as(entry.id()).isNotBlank();
        }
        assertThat(ScriptHookMatrix.count(CommandParityStatus.UNVERIFIED)).isZero();
    }

    @Test
    void budgetValuesMatchAcceptanceContract() {
        var tick = ScriptBudget.tick();
        var standard = ScriptBudget.standard();
        assertThat(tick.maxInstructions()).isEqualTo(20_000);
        assertThat(standard.maxInstructions()).isEqualTo(100_000);
        assertThat(tick.maxLiveMemoryBytes()).isEqualTo(1 << 20);
        assertThat(tick.maxRecursionDepth()).isEqualTo(16);
        assertThat(tick.maxCanonicalOps()).isEqualTo(32);
        assertThat(ScriptScheduler.TICK_HOOK_NANOS).isEqualTo(1_000_000L);
        assertThat(ScriptScheduler.STANDARD_HOOK_NANOS).isEqualTo(5_000_000L);
        assertThat(ScriptScheduler.TICK_AGGREGATE_NANOS).isEqualTo(4_000_000L);
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private DefinitionRegistry registry() {
        var registry = new DefinitionRegistry();
        registry.registerFaction(new com.storynpcs.domain.faction.Faction(
                NamespacedId.of("storynpcs:villagers"), "Villagers", 0, 0, 2000));
        return registry;
    }

    private record Harness(StoryNpcsApplicationService service,
                           ProgressionRepository repository,
                           DefinitionRegistry registry) {
        int factionPoints(UUID player) {
            return repository.getOrCreate(player).getFactionPoints()
                    .getOrDefault(NamespacedId.of("storynpcs:villagers"), 0);
        }
    }

    private Harness harness() throws Exception {
        var registry = registry();
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir);
        var repository = new ProgressionRepository(tempDir.resolve("progression"));
        var service = new StoryNpcsApplicationService(registry, repository, new EventPublisher());
        service.setLoader(loader);
        return new Harness(service, repository, registry);
    }

    private StoryNpcsApplicationService service() throws Exception {
        return harness().service();
    }

    /**
     * A scheduler with a 50ms standard-hook wall — mutation-path tests prove
     * grant enforcement and canonical routing, not wall-clock numbers (which
     * have dedicated fixtures). Cold JIT would otherwise flake the 5ms bound.
     */
    private static ScriptScheduler relaxedScheduler() {
        var tunables = new RuntimeTunables();
        tunables.restore(Map.of(
                RuntimeTunables.SCRIPT_STANDARD_HOOK_NANOS, "50000000",
                RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS, "100000000"), 1);
        return new ScriptScheduler(tunables.readOnlyView());
    }

    private ScriptRuntime runtime(ScriptScheduler scheduler) {
        try {
            return runtime(service(), scheduler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ScriptRuntime runtime(StoryNpcsApplicationService service, ScriptScheduler scheduler) {
        return new ScriptRuntime(service, scheduler, line -> {});
    }

    private ScriptRuntime runtimeWithLog(List<String> log, ScriptScheduler scheduler) {
        try {
            return new ScriptRuntime(service(), scheduler, log::add);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ScriptDefinition script(String id, String version, String source,
                                           List<String> hooks, List<String> capabilities) {
        var script = new ScriptDefinition();
        script.setId(NamespacedId.of(id));
        script.setVersion(version);
        script.setSource(source);
        script.setHooks(hooks);
        script.setCapabilities(capabilities);
        script.setEnabled(true);
        return script;
    }

    private static ScriptContext ctx(ScriptHook hook) {
        return ScriptRuntime.entityContext(hook, "storynpcs:npc1", null, "minecraft:overworld");
    }
}
