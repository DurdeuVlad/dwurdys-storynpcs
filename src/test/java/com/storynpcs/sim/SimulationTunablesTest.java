package com.storynpcs.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.storynpcs.admin.RuntimeTunables;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P4-1 (#62): per-tier budgets and distance bands are live-configurable via
 * the bounded {@link RuntimeTunables} surface — committed changes take effect
 * on the next scheduler use, invalid commits leave the live config untouched.
 */
class SimulationTunablesTest {

    @Test
    @DisplayName("fresh view resolves to the conservative defaults")
    void defaultsMatch() {
        var tunables = new SimulationTunables(new RuntimeTunables().readOnlyView());
        assertEquals(SimulationTierPolicy.defaults(), tunables.policy());
        assertEquals(TierBudgets.defaults(), tunables.budgets());
    }

    @Test
    @DisplayName("committed band change takes effect on the next evaluation")
    void committedBandApplies() {
        RuntimeTunables live = new RuntimeTunables();
        var tunables = new SimulationTunables(live.readOnlyView());
        var scheduler = new SimulationScheduler(tunables::policy, tunables::budgets);

        UUID actor = UUID.randomUUID();
        var input = new SimulationScheduler.ActorInput(actor, 100.0, false);
        assertEquals(SimulationTier.NEARBY, scheduler.evaluate(List.of(input)).get(0).to(),
                "100 blocks is NEARBY under the 64/128/256/512 default bands");

        // Widen ACTIVE to 100 — stays inside the band ordering and moves the
        // 100-block actor from NEARBY to ACTIVE.
        var outcome = live.applyChanges(
                Map.of(RuntimeTunables.SIM_BAND_ACTIVE, "100"), live.revision());
        assertTrue(outcome.committed(), outcome.detail());
        assertEquals(SimulationTier.ACTIVE, scheduler.evaluate(List.of(input)).get(0).to(),
                "widened ACTIVE band takes effect on the very next evaluation");
    }

    @Test
    @DisplayName("committed period change takes effect on capability gating")
    void committedPeriodApplies() {
        RuntimeTunables live = new RuntimeTunables();
        var tunables = new SimulationTunables(live.readOnlyView());
        var scheduler = new SimulationScheduler(tunables::policy, tunables::budgets);

        UUID actor = UUID.randomUUID();
        scheduler.evaluate(List.of(new SimulationScheduler.ActorInput(actor, 100.0, false)));
        assertEquals(2, scheduler.capabilityPeriod(actor,
                SimulationScheduler.Capability.SENSING),
                "NEARBY sensing defaults to every 2 ticks");

        var outcome = live.applyChanges(
                Map.of(RuntimeTunables.simBudgetKey("nearby", "sensing"), "7"),
                live.revision());
        assertTrue(outcome.committed(), outcome.detail());
        assertEquals(7, scheduler.capabilityPeriod(actor,
                SimulationScheduler.Capability.SENSING));
        assertTrue(scheduler.shouldRun(actor, SimulationScheduler.Capability.SENSING, 14));
        assertFalse(scheduler.shouldRun(actor, SimulationScheduler.Capability.SENSING, 15));
    }

    @Test
    @DisplayName("inverted band commit rejects and leaves live config untouched")
    void invertedBandsReject() {
        RuntimeTunables live = new RuntimeTunables();
        var outcome = live.applyChanges(Map.of(
                RuntimeTunables.SIM_BAND_ACTIVE, "500",
                RuntimeTunables.SIM_BAND_NEARBY, "100"), live.revision());
        assertFalse(outcome.committed());
        assertTrue(outcome.detail().contains("SIM_BANDS_NOT_ORDERED"),
                "expected ordering diagnostic, got: " + outcome.detail());
        var tunables = new SimulationTunables(live.readOnlyView());
        assertEquals(SimulationTierPolicy.defaults(), tunables.policy());
    }

    @Test
    @DisplayName("out-of-range period value rejects at commit time")
    void periodBoundsReject() {
        RuntimeTunables live = new RuntimeTunables();
        var outcome = live.applyChanges(
                Map.of(RuntimeTunables.simBudgetKey("dormant", "persistence"), "-2"),
                live.revision());
        assertFalse(outcome.committed());
        assertTrue(outcome.detail().contains("TUNABLE_OUT_OF_RANGE"));
    }

    @Test
    @DisplayName("resolved objects cache on the view revision")
    void revisionCaching() {
        RuntimeTunables live = new RuntimeTunables();
        var tunables = new SimulationTunables(live.readOnlyView());
        TierBudgets first = tunables.budgets();
        assertSame(first, tunables.budgets(),
                "unchanged revision returns the cached instance — no per-tick re-parse");
        live.applyChanges(Map.of(RuntimeTunables.SIM_BAND_ACTIVE, "32"), live.revision());
        assertNotSame(first, tunables.budgets(), "commit rebuilds the resolved object");
    }
}
