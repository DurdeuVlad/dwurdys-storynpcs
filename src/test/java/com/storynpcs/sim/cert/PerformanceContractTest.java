package com.storynpcs.sim.cert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.storynpcs.sim.cert.PerformanceContract.BenchmarkReport;
import com.storynpcs.sim.cert.PerformanceContract.CertificationResult;
import com.storynpcs.sim.cert.PerformanceContract.Environment;
import com.storynpcs.sim.cert.PerformanceContract.Metrics;
import com.storynpcs.sim.cert.PerformanceContract.WorkFingerprint;
import com.storynpcs.sim.cert.PerformanceContract.Workload;

class PerformanceContractTest {

    private static final Environment ENV = new Environment(
            "21", "21.1.x", "1.21.1", "dedicated-4c8g", 8, 10, "storynpcs-only", 1_200, 12_000, 42L);

    private static Metrics passing() {
        return new Metrics(20.0, 30.0, 40.0, 512L << 20, 24L * 1024, 128, 0, 64, 0, 0, 0);
    }

    private static BenchmarkReport report(String scenario, Metrics m) {
        return new BenchmarkReport(ENV, new Workload(scenario, 500, 0, "test"), m,
                new WorkFingerprint(0, 0, 0, 0, 0),
                new PerformanceContract.PhaseBreakdown(0, 0, 0, 0));
    }

    @Test
    void authorizedThresholdsMatchCertificationDoc() {
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.get("population").msptP95Max()).isEqualTo(35.0);
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.get("population").msptP99Max()).isEqualTo(45.0);
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.get("population").dormantIncrementalMaxBytes())
                .isEqualTo(32L * 1024);
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.get("stress").msptP95Max()).isEqualTo(45.0);
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.get("stress").msptP99Max()).isEqualTo(50.0);
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.get("siege").pathQueueMaxDepth()).isEqualTo(1_024);
        assertThat(PerformanceContract.SCENARIO_THRESHOLDS.keySet())
                .containsExactlyInAnyOrder("population", "siege", "stress");
        assertThatThrownBy(() -> PerformanceContract.validate("unknown", passing()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passingReportCertifies() {
        CertificationResult result = PerformanceContract.validate("population", passing());
        assertThat(result.pass()).isTrue();
        assertThat(result.checks()).allMatch(c -> c.pass());
    }

    @Test
    void eachThresholdFailsIndependently() {
        Metrics slowP95 = new Metrics(20.0, 36.0, 40.0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(PerformanceContract.validate("population", slowP95).pass()).isFalse();
        Metrics slowP99 = new Metrics(20.0, 30.0, 46.0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(PerformanceContract.validate("population", slowP99).pass()).isFalse();
        Metrics fatDormant = new Metrics(20.0, 30.0, 40.0, 0, 33L * 1024, 0, 0, 0, 0, 0, 0);
        assertThat(PerformanceContract.validate("population", fatDormant).pass()).isFalse();
        Metrics lostEvent = new Metrics(20.0, 30.0, 40.0, 0, 0, 0, 0, 0, 0, 1, 0);
        assertThat(PerformanceContract.validate("population", lostEvent).pass()).isFalse();
        Metrics queueOverflow = new Metrics(20.0, 30.0, 40.0, 0, 0, 1_025, 0, 0, 0, 0, 0);
        assertThat(PerformanceContract.validate("siege", queueOverflow).pass()).isFalse();
    }

    @Test
    void failingReportNamesBreachedMetrics() {
        Metrics bad = new Metrics(20.0, 50.0, 60.0, 0, 0, 0, 0, 0, 3, 0, 0);
        CertificationResult result = PerformanceContract.validate("population", bad);
        assertThat(result.pass()).isFalse();
        assertThat(result.checks().stream().filter(c -> !c.pass()).map(c -> c.metric()))
                .containsExactlyInAnyOrder("msptP95", "msptP99", "correctnessFailures");
    }

    @Test
    void repeatabilityRequiresThreeRunsWithinTenPercent() {
        assertThat(PerformanceContract.repeatable(List.of(report("population", passing()),
                report("population", passing())))).isFalse();

        Metrics m30 = new Metrics(0, 30, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        Metrics m32 = new Metrics(0, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        Metrics m31 = new Metrics(0, 31, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(PerformanceContract.repeatable(List.of(
                report("population", m30), report("population", m32), report("population", m31))))
                .isTrue();

        Metrics m50 = new Metrics(0, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(PerformanceContract.repeatable(List.of(
                report("population", m30), report("population", m32), report("population", m50))))
                .isFalse();
    }
}
