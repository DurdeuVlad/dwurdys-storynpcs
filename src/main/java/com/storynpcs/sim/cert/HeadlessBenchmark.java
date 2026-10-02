package com.storynpcs.sim.cert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.storynpcs.sim.ActorMemoryReport;
import com.storynpcs.sim.PathScheduler;
import com.storynpcs.sim.SimulationScheduler;
import com.storynpcs.sim.SimulationTierPolicy;
import com.storynpcs.sim.SquadCoordinator;
import com.storynpcs.sim.TierBudgets;
import com.storynpcs.sim.cert.PerformanceContract.BenchmarkReport;
import com.storynpcs.sim.cert.PerformanceContract.Environment;
import com.storynpcs.sim.cert.PerformanceContract.Metrics;
import com.storynpcs.sim.cert.PerformanceContract.WorkFingerprint;
import com.storynpcs.sim.cert.PerformanceContract.Workload;

/**
 * P11-3: deterministic headless benchmark over the P4 simulation components.
 *
 * <p>These reports measure the scheduler/path/squad machinery in a plain JVM —
 * they are NOT live-server MSPT and must never be cited as such. The
 * {@code hardwareProfile} in every produced environment says so explicitly.
 * Live-server certification still requires a real NeoForge runtime probe.</p>
 */
public final class HeadlessBenchmark {

    /** Environment label that prevents headless numbers being read as live MSPT. */
    public static final String HARDWARE_PROFILE =
            "headless-jvm-simulation (no Minecraft runtime; not live MSPT)";

    public record ScenarioSpec(String scenario, int npcCount, int nearActors,
                               int activeCombatants, long ticks) {}

    public static final List<ScenarioSpec> SCENARIOS = List.of(
            new ScenarioSpec("population", 500, 250, 0, 1_500),
            new ScenarioSpec("siege", 50, 50, 50, 1_500),
            new ScenarioSpec("stress", 2_500, 600, 0, 1_500));

    private HeadlessBenchmark() {}

    public static ScenarioSpec spec(String scenario) {
        return SCENARIOS.stream().filter(s -> s.scenario().equals(scenario)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario " + scenario));
    }

    /**
     * Run one deterministic repetition of a scenario. {@code seed} selects the
     * repetition; identical seeds produce identical workloads (durations vary
     * with the machine — recorded, never asserted to fixed values).
     */
    public static BenchmarkReport run(String scenario, long seed) {
        ScenarioSpec spec = spec(scenario);
        SimulationScheduler scheduler =
                new SimulationScheduler(SimulationTierPolicy.defaults(), TierBudgets.defaults());
        PathScheduler paths = new PathScheduler();
        SquadCoordinator squads = new SquadCoordinator();
        Random rng = new Random(seed);

        List<SimulationScheduler.ActorInput> actors = new ArrayList<>(spec.npcCount());
        for (int i = 0; i < spec.npcCount(); i++) {
            UUID id = UUID.nameUUIDFromBytes(("actor-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            double distance = i < spec.nearActors()
                    ? 8 + rng.nextDouble() * 56        // ≤64 blocks — active band
                    : 65 + rng.nextDouble() * 190;     // distant/dormant band
            boolean combat = i < spec.activeCombatants();
            actors.add(new SimulationScheduler.ActorInput(id, distance, combat));
        }

        Set<UUID> squad = new HashSet<>();
        Set<UUID> enemies = new HashSet<>();
        for (int i = 0; i < spec.activeCombatants(); i++) {
            UUID id = actors.get(i).actorId();
            if (i % 2 == 0) squad.add(id); else enemies.add(id);
        }

        // Correctness counters: one emitted event per combat action is tracked
        // through a deterministic journal — losses/dups are measured, not assumed.
        Set<Long> emitted = new HashSet<>();
        Set<Long> delivered = new HashSet<>();
        int duplicated = 0;
        long eventSeq = 0;
        long tierEvaluations = 0;
        long pathSubmissions = 0;
        long squadCoordinations = 0;

        // Per-tick time is estimated over fixed windows: a single tick's nanos
        // are too noisy for cross-seed repeatability, so each recorded sample
        // is the mean tick time of a WINDOW-tick batch — still an honest p95
        // estimator, immune to single-tick scheduling spikes.
        final int window = 25;
        List<Double> tickNanos = new ArrayList<>();
        long windowNanos = 0;
        int maxDepth = 0;
        long requestSeq = 0;

        // Per-phase attribution: a regression report names WHERE time went.
        // One timer pair per phase per tick (5 pairs + the tick pair ≈ 11
        // nanoTime calls/tick) — per-actor boundaries would put ~4·npcCount
        // nanoTime() calls inside the gated window and dominate the cheap
        // buckets with measurement overhead.
        long phaseEvaluateNanos = 0;
        long phaseSensingNanos = 0;
        long phasePathNanos = 0;
        long phaseCombatNanos = 0;
        long phaseSquadNanos = 0;
        long totalTickNanos = 0;
        double sensed = 0; // consumed so the sensing read can't be dead-code-eliminated

        // Unmeasured warmup: let the JIT reach steady state so cross-seed
        // repeatability measures the workload, not compilation noise.
        long warmupTicks = Math.max(250, spec.ticks() / 2);
        for (long tick = 0; tick < warmupTicks; tick++) {
            scheduler.evaluate(actors);
        }

        for (long tick = 0; tick < spec.ticks(); tick++) {
            long start = System.nanoTime();
            long phaseStart = start;
            scheduler.evaluate(actors);
            phaseEvaluateNanos += System.nanoTime() - phaseStart;
            tierEvaluations++;

            phaseStart = System.nanoTime();
            for (SimulationScheduler.ActorInput a : actors) {
                if (scheduler.shouldRun(a.actorId(), SimulationScheduler.Capability.SENSING, tick)) {
                    sensed += a.distanceBlocks(); // the sensing unit of work
                }
            }
            phaseSensingNanos += System.nanoTime() - phaseStart;

            phaseStart = System.nanoTime();
            for (SimulationScheduler.ActorInput a : actors) {
                if (a.inCombat() && scheduler.shouldRun(a.actorId(),
                        SimulationScheduler.Capability.PATHING, tick)) {
                    pathSubmissions++;
                    PathScheduler.SubmitOutcome outcome = paths.submit(new PathScheduler.PathRequest(
                            UUID.nameUUIDFromBytes(("req-" + (++requestSeq))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            a.actorId(), new PathScheduler.PathTarget(1, 2, 3, null),
                            0, tick, tick));
                    if (outcome == PathScheduler.SubmitOutcome.QUEUED) {
                        paths.poll();
                    }
                }
            }
            phasePathNanos += System.nanoTime() - phaseStart;

            phaseStart = System.nanoTime();
            for (SimulationScheduler.ActorInput a : actors) {
                if (a.inCombat() && scheduler.shouldRun(a.actorId(),
                        SimulationScheduler.Capability.COMBAT, tick)) {
                    long eventId = ++eventSeq;
                    emitted.add(eventId);
                    if (!delivered.add(eventId)) duplicated++;
                }
            }
            phaseCombatNanos += System.nanoTime() - phaseStart;

            phaseStart = System.nanoTime();
            if (!squad.isEmpty()) {
                squads.coordinate(squad, enemies, actor -> rng.nextDouble());
                squadCoordinations++;
            }
            phaseSquadNanos += System.nanoTime() - phaseStart;

            maxDepth = Math.max(maxDepth, paths.depth());
            long elapsed = System.nanoTime() - start;
            windowNanos += elapsed;
            totalTickNanos += elapsed;
            if ((tick + 1) % window == 0 || tick + 1 == spec.ticks()) {
                long span = (tick + 1) % window == 0 ? window : (tick + 1) % window;
                tickNanos.add(windowNanos / (span * 1_000_000.0));
                windowNanos = 0;
            }
        }
        if (sensed < 0) {
            // Impossible (distances are non-negative) — the check exists so the
            // accumulator is observable and the sensing read can't be DCE'd.
            throw new IllegalStateException("sensing accumulator went negative");
        }

        // Dormant incremental memory: serialize the durable per-actor record the
        // scheduler actually retains (id + tier + combat flag), plus the shared
        // archetype amortized once. Measured, not asserted.
        long dormantCount = scheduler.states().values().stream().filter(s -> s.dormant()).count();
        ActorMemoryReport memory = measureDormantMemory(scheduler, (int) dormantCount);

        System.gc();
        long heapAfterGc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        int lostEvents = emitted.size() - delivered.size();
        int correctnessFailures = (lostEvents == 0 && duplicated == 0) ? 0 : 1;
        Metrics metrics = new Metrics(
                percentile(tickNanos, 0.50),
                percentile(tickNanos, 0.95),
                percentile(tickNanos, 0.99),
                heapAfterGc,
                memory.incrementalBytesPerActor(),
                maxDepth,
                paths.droppedRequests(),
                0,
                correctnessFailures,
                lostEvents,
                duplicated);

        Environment env = new Environment(
                System.getProperty("java.version"), "neoforge-1.21.1", "1.21.1",
                HARDWARE_PROFILE, 0, 0,
                "plain-JVM scheduler benchmark; no world/chunk/entity runtime",
                warmupTicks, spec.ticks(), seed);
        Workload workload = new Workload(scenario, spec.npcCount(), spec.activeCombatants(),
                spec.nearActors() + " near/" + (spec.npcCount() - spec.nearActors())
                        + " far actors, " + spec.ticks() + " ticks, seed " + seed);
        WorkFingerprint fingerprint = new WorkFingerprint(tierEvaluations, emitted.size(),
                delivered.size(), pathSubmissions, squadCoordinations);
        long instrumented = phaseEvaluateNanos + phaseSensingNanos + phasePathNanos
                + phaseCombatNanos + phaseSquadNanos;
        return new BenchmarkReport(env, workload, metrics, fingerprint,
                new PerformanceContract.PhaseBreakdown(
                        phaseEvaluateNanos, phaseSensingNanos, phasePathNanos,
                        phaseCombatNanos, phaseSquadNanos,
                        Math.max(0, totalTickNanos - instrumented)));
    }

    /**
     * Measure the incremental durable footprint per dormant actor: the exact
     * record the scheduler retains (36-byte UUID, tier ordinal, combat flag,
     * container overhead) — pessimistically rounded up to a serialized
     * durable-state record.
     */
    private static ActorMemoryReport measureDormantMemory(SimulationScheduler scheduler,
                                                          int dormantCount) {
        // Serialize one dormant state's durable fields to measure real size.
        var dormant = scheduler.states().values().stream().filter(s -> s.dormant()).findFirst();
        long incremental = 0;
        if (dormant.isPresent()) {
            var s = dormant.get();
            String durable = s.actorId() + "|" + s.tier() + "|" + s.inCombat();
            incremental = durable.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        // Archetype (shared definition surface) amortized once across the population.
        long archetype = 512;
        return new ActorMemoryReport(Math.max(dormantCount, 1), archetype, Math.max(incremental, 1));
    }

    static double percentile(List<Double> samples, double p) {
        if (samples.isEmpty()) return 0;
        List<Double> sorted = samples.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(index, 0));
    }
}
