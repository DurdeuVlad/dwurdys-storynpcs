package com.storynpcs.sim.cert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performance certification contract (P4-3). Every performance claim must be
 * expressed as a {@link BenchmarkReport} measured under a fixed environment
 * and validated against the scenario thresholds below — no anecdotal numbers.
 */
public final class PerformanceContract {
    private PerformanceContract() {}

    /** Fixed environment descriptor. Two reports are comparable only when environments match. */
    public record Environment(
            String javaVersion,
            String neoforgeVersion,
            String minecraftVersion,
            String hardwareProfile,
            int simulationDistance,
            int viewDistance,
            String modpackAssumptions,
            long warmupTicks,
            long runTicks,
            long seed) {}

    /** Workload description — identical workload must be used across comparable runs. */
    public record Workload(String scenario, int npcCount, int activeCombatants, String description) {}

    public record Metrics(
            double msptP50,
            double msptP95,
            double msptP99,
            long heapAfterGcBytes,
            long dormantIncrementalBytes,
            int pathQueueMaxDepth,
            int pathQueueDropped,
            int packetCount,
            int correctnessFailures,
            int lostEvents,
            int duplicatedEvents) {}

    public record Thresholds(
            double msptP95Max,
            double msptP99Max,
            long dormantIncrementalMaxBytes,
            int pathQueueMaxDepth,
            int maxCorrectnessFailures) {}

    public record Check(String metric, String limit, double actual, boolean pass) {}

    public record CertificationResult(String scenario, boolean pass, List<Check> checks) {}

    /** Scenario thresholds authorized by the parity certification doc — unchanged. */
    public static final Map<String, Thresholds> SCENARIO_THRESHOLDS = Map.of(
            "population", new Thresholds(35.0, 45.0, 32L * 1024, 1_024, 0),
            "siege", new Thresholds(35.0, 45.0, Long.MAX_VALUE, 1_024, 0),
            "stress", new Thresholds(45.0, 50.0, Long.MAX_VALUE, 1_024, 0));

    public static CertificationResult validate(String scenario, Metrics m) {
        Thresholds t = SCENARIO_THRESHOLDS.get(scenario);
        if (t == null) {
            throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("msptP95", "≤" + t.msptP95Max(), m.msptP95(), m.msptP95() <= t.msptP95Max()));
        checks.add(new Check("msptP99", "≤" + t.msptP99Max(), m.msptP99(), m.msptP99() <= t.msptP99Max()));
        checks.add(new Check("dormantIncremental", "≤" + t.dormantIncrementalMaxBytes(),
                m.dormantIncrementalBytes(), m.dormantIncrementalBytes() <= t.dormantIncrementalMaxBytes()));
        checks.add(new Check("pathQueueMaxDepth", "≤" + t.pathQueueMaxDepth(),
                m.pathQueueMaxDepth(), m.pathQueueMaxDepth() <= t.pathQueueMaxDepth()));
        checks.add(new Check("correctnessFailures", "≤" + t.maxCorrectnessFailures(),
                m.correctnessFailures(), m.correctnessFailures() <= t.maxCorrectnessFailures()));
        checks.add(new Check("lostEvents", "=0", m.lostEvents(), m.lostEvents() == 0));
        checks.add(new Check("duplicatedEvents", "=0", m.duplicatedEvents(), m.duplicatedEvents() == 0));
        boolean pass = checks.stream().allMatch(Check::pass);
        return new CertificationResult(scenario, pass, List.copyOf(checks));
    }

    /**
     * Repeatability: a claim requires ≥3 runs whose p95 values are within 10%
     * of the series median. Fewer runs is inconclusive (not a pass).
     */
    public static boolean repeatable(List<BenchmarkReport> runs) {
        if (runs.size() < 3) {
            return false;
        }
        List<Double> p95s = runs.stream()
                .map(r -> r.metrics().msptP95())
                .sorted()
                .toList();
        double median = p95s.get(p95s.size() / 2);
        if (median <= 0) {
            return false;
        }
        return p95s.stream().allMatch(v -> Math.abs(v - median) / median <= 0.10);
    }

    /** Deterministic work counters — identical seeds must produce identical work. */
    public record WorkFingerprint(long tierEvaluations, long emittedEvents, long deliveredEvents,
                                  long pathSubmissions, long squadCoordinations) {}

    /**
     * Per-phase wall-time attribution (cumulative nanos across all measured
     * ticks) — the artifact's own profiler split so a regression names where
     * the time went. {@code unattributedNanos} is the measured tick time that
     * falls outside every instrumented phase (loop bookkeeping, timer gaps),
     * so the buckets are honestly non-exhaustive rather than implying the
     * named phases sum to the whole tick. Not threshold-checked; it explains
     * metrics, it does not gate them.
     */
    public record PhaseBreakdown(long evaluateNanos, long sensingNanos,
                                 long pathNanos, long combatNanos,
                                 long squadNanos, long unattributedNanos) {}

    public record BenchmarkReport(
            Environment environment,
            Workload workload,
            Metrics metrics,
            WorkFingerprint workFingerprint,
            PhaseBreakdown phases) {}
}
