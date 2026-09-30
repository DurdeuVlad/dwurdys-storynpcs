package com.storynpcs.sim.cert;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.storynpcs.sim.cert.PerformanceContract.BenchmarkReport;
import com.storynpcs.sim.cert.PerformanceContract.CertificationResult;

/**
 * P11-3: run the three certification scenarios headlessly, validate every
 * numeric threshold for each seed, assert deterministic work for a fixed seed,
 * and record evidence artifacts under docs/parity/reports/. Metrics are headless-JVM numbers —
 * the artifacts themselves say so; live-server MSPT remains a separate
 * target-runtime probe.
 */
class SimCertificationBenchmarkTest {

    private static final Path REPORT_DIR = Path.of("docs/parity/reports");
    private static final long[] SEEDS = {0x5EEDL, 0xCAFEL, 0xBEEFL};

    @Test
    void allThreeScenariosPassThresholdsAndRepeatWorkDeterministically() throws Exception {
        Files.createDirectories(REPORT_DIR);
        // JVM-level warmup: the first measured run must not pay JIT
        // compilation.
        HeadlessBenchmark.run("siege", 0xDEADBEEFL);
        HeadlessBenchmark.run("population", 0xDEADBEEFL);
        for (var spec : HeadlessBenchmark.SCENARIOS) {
            List<BenchmarkReport> runs = new ArrayList<>();
            for (long seed : SEEDS) {
                runs.add(medianReport(spec.scenario(), seed));
            }
            List<CertificationResult> results = runs.stream()
                    .map(r -> PerformanceContract.validate(r.workload().scenario(), r.metrics()))
                    .toList();
            for (CertificationResult result : results) {
                assertThat(result.pass())
                        .as("scenario %s failed checks: %s", spec.scenario(), result.checks())
                        .isTrue();
            }
            // Headless repeatability = identical work for identical seeds.
            // Wall-clock spread is environment-dependent and is not summarized
            // as a pass/fail claim in the artifact.
            var fingerprint = HeadlessBenchmark.run(spec.scenario(), SEEDS[0]).workFingerprint();
            assertThat(HeadlessBenchmark.run(spec.scenario(), SEEDS[0]).workFingerprint())
                    .as("identical seed produced different workload work")
                    .isEqualTo(fingerprint);
            writeArtifact(spec.scenario(), runs, results);
            var artifact = new ObjectMapper().readTree(
                    Files.readString(REPORT_DIR.resolve("benchmark-" + spec.scenario() + ".json")));
            assertThat(artifact.get("timing_repeatable_within_10pct").isNull())
                    .as("wall-clock repeatability is not asserted for %s", spec.scenario())
                    .isTrue();
            assertThat(artifact.get("timing_repeatability_note").asText())
                    .contains("not asserted");
        }
    }

    /** Three inner runs per seed; the median-p95 report represents that seed. */
    private static BenchmarkReport medianReport(String scenario, long seed) {
        return java.util.stream.Stream.of(
                        HeadlessBenchmark.run(scenario, seed),
                        HeadlessBenchmark.run(scenario, seed),
                        HeadlessBenchmark.run(scenario, seed))
                .sorted(java.util.Comparator.comparingDouble(r -> r.metrics().msptP95()))
                .toList()
                .get(1);
    }

    private void writeArtifact(String scenario, List<BenchmarkReport> runs,
                               List<CertificationResult> results) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"artifact_kind\": \"benchmark-evidence\",\n");
        json.append("  \"scenario\": \"").append(scenario).append("\",\n");
        json.append("  \"runtime_kind\": \"storynpcs-headless-jvm\",\n");
        json.append("  \"honesty_note\": \"Headless scheduler/path/squad benchmark on a plain JVM. ")
                .append("Numbers are scheduler tick times, NOT live-server MSPT. Live runtime ")
                .append("certification requires a NeoForge server probe that remains unavailable ")
                .append("(no live MC testing permitted).\"\n");
        json.append("  ,\"runs\": [\n");
        for (int i = 0; i < runs.size(); i++) {
            BenchmarkReport r = runs.get(i);
            CertificationResult c = results.get(i);
            json.append("    {\n");
            json.append("      \"seed\": ").append(r.environment().seed()).append(",\n");
            json.append("      \"hardware_profile\": \"").append(r.environment().hardwareProfile()).append("\",\n");
            json.append("      \"ticks\": ").append(r.environment().runTicks()).append(",\n");
            json.append("      \"metrics\": {\n");
            var m = r.metrics();
            json.append("        \"tick_p50_ms\": ").append(m.msptP50()).append(",\n");
            json.append("        \"tick_p95_ms\": ").append(m.msptP95()).append(",\n");
            json.append("        \"tick_p99_ms\": ").append(m.msptP99()).append(",\n");
            json.append("        \"heap_after_gc_bytes\": ").append(m.heapAfterGcBytes()).append(",\n");
            json.append("        \"dormant_incremental_bytes\": ").append(m.dormantIncrementalBytes()).append(",\n");
            json.append("        \"path_queue_max_depth\": ").append(m.pathQueueMaxDepth()).append(",\n");
            json.append("        \"path_queue_dropped\": ").append(m.pathQueueDropped()).append(",\n");
            json.append("        \"correctness_failures\": ").append(m.correctnessFailures()).append(",\n");
            json.append("        \"lost_events\": ").append(m.lostEvents()).append(",\n");
            json.append("        \"duplicated_events\": ").append(m.duplicatedEvents()).append("\n");
            json.append("      },\n");
            json.append("      \"threshold_results\": [\n");
            for (int j = 0; j < c.checks().size(); j++) {
                var check = c.checks().get(j);
                json.append("        {\"metric\": \"").append(check.metric())
                        .append("\", \"limit\": \"").append(check.limit())
                        .append("\", \"actual\": ").append(check.actual())
                        .append(", \"pass\": ").append(check.pass()).append("}");
                json.append(j + 1 < c.checks().size() ? ",\n" : "\n");
            }
            json.append("      ],\n");
            json.append("      \"scenario_pass\": ").append(c.pass()).append("\n");
            json.append("    }").append(i + 1 < runs.size() ? ",\n" : "\n");
        }
        json.append("  ],\n");
        json.append("  \"timing_repeatable_within_10pct\": null,\n");
        json.append("  \"timing_repeatability_note\": \"Wall-clock repeatability is not asserted: shared-machine timing noise makes a ±10% result environment-dependent. Null means unasserted; workload determinism (identical seeds → identical work counters) is asserted separately.\",\n");
        json.append("  \"certification_state\": \"HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED\"\n");
        json.append("}\n");
        Files.writeString(REPORT_DIR.resolve("benchmark-" + scenario + ".json"), json.toString());
    }
}
