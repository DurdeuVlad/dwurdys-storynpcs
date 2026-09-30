package com.storynpcs.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.sim.SimulationScheduler.ActorInput;
import com.storynpcs.sim.SimulationScheduler.Capability;
import com.storynpcs.sim.SimulationScheduler.TierTransition;

class SimulationSchedulerTest {

    private final SimulationScheduler scheduler =
            new SimulationScheduler(SimulationTierPolicy.defaults(), TierBudgets.defaults());

    private static ActorInput actor(String seed, double distance) {
        return new ActorInput(UUID.nameUUIDFromBytes(seed.getBytes()), distance, false);
    }

    @Test
    void distanceBandsAssignTiersDeterministically() {
        SimulationTierPolicy policy = SimulationTierPolicy.defaults();
        assertThat(policy.tierFor(10)).isEqualTo(SimulationTier.ACTIVE);
        assertThat(policy.tierFor(64)).isEqualTo(SimulationTier.ACTIVE);
        assertThat(policy.tierFor(64.1)).isEqualTo(SimulationTier.NEARBY);
        assertThat(policy.tierFor(200)).isEqualTo(SimulationTier.DISTANT);
        assertThat(policy.tierFor(400)).isEqualTo(SimulationTier.DORMANT);
        assertThat(policy.tierFor(600)).isEqualTo(SimulationTier.UNLOADED);
        assertThat(policy.tierFor(-1)).isEqualTo(SimulationTier.UNLOADED);
        assertThat(policy.tierFor(Double.NaN)).isEqualTo(SimulationTier.UNLOADED);
        assertThat(policy.tierFor(600, true)).isEqualTo(SimulationTier.DISTANT); // combat clamps dormant
        assertThatThrownBy(() -> new SimulationTierPolicy(0, 1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionsAreObservableAndDeterministic() {
        UUID near = UUID.nameUUIDFromBytes("a".getBytes());
        List<TierTransition> first = scheduler.evaluate(List.of(
                new ActorInput(near, 10, false),
                actor("b", 300)));
        assertThat(first).extracting(TierTransition::reason).containsOnly("SPAWNED");

        List<TierTransition> second = scheduler.evaluate(List.of(
                new ActorInput(near, 400, false),
                actor("b", 300)));
        assertThat(second).hasSize(1);
        assertThat(second.get(0).from()).isEqualTo(SimulationTier.ACTIVE);
        assertThat(second.get(0).to()).isEqualTo(SimulationTier.DORMANT);
        assertThat(second.get(0).reason()).isEqualTo("DISTANCE");

        // Despawn: unreported actor transitions to UNLOADED observably.
        List<TierTransition> third = scheduler.evaluate(List.of(actor("b", 300)));
        assertThat(third).hasSize(1);
        assertThat(third.get(0).actorId()).isEqualTo(near);
        assertThat(third.get(0).reason()).isEqualTo("DESPAWNED");
    }

    @Test
    void transitionHistoryIsBoundedAcrossRepeatedTierChanges() {
        UUID actorId = UUID.nameUUIDFromBytes("history-bound".getBytes());
        for (int i = 0; i < SimulationScheduler.MAX_TRANSITION_HISTORY + 10; i++) {
            scheduler.evaluate(List.of(new ActorInput(actorId, i % 2 == 0 ? 10 : 600, false)));
        }

        assertThat(scheduler.transitions()).hasSize(SimulationScheduler.MAX_TRANSITION_HISTORY);
    }

    @Test
    void combatNeverEntersDormant() {
        UUID fighter = UUID.nameUUIDFromBytes("f".getBytes());
        scheduler.evaluate(List.of(new ActorInput(fighter, 600, true)));
        assertThat(scheduler.stateOf(fighter).tier()).isEqualTo(SimulationTier.DISTANT);
    }

    @Test
    void capabilityGatingHonorsBudgets() {
        UUID distant = UUID.nameUUIDFromBytes("d".getBytes());
        UUID dormant = UUID.nameUUIDFromBytes("e".getBytes());
        scheduler.evaluate(List.of(
                new ActorInput(distant, 200, false),
                new ActorInput(dormant, 400, false)));

        // DISTANT: sensing every 10 ticks, animation disabled.
        assertThat(scheduler.shouldRun(distant, Capability.SENSING, 20)).isTrue();
        assertThat(scheduler.shouldRun(distant, Capability.SENSING, 21)).isFalse();
        assertThat(scheduler.shouldRun(distant, Capability.ANIMATION, 20)).isFalse();
        // DORMANT: only persistence every 1200 ticks.
        assertThat(scheduler.shouldRun(dormant, Capability.COMBAT, 1200)).isFalse();
        assertThat(scheduler.shouldRun(dormant, Capability.PERSISTENCE, 1200)).isTrue();
        assertThat(scheduler.shouldRun(dormant, Capability.PERSISTENCE, 100)).isFalse();
    }

    @Test
    void memoryReportSeparatesArchetypeAndIncremental() {
        ActorMemoryReport report = new ActorMemoryReport(500, 4L * 1024 * 1024, 24L * 1024);
        assertThat(report.incrementalTotalBytes()).isEqualTo(500 * 24L * 1024);
        assertThat(report.totalBytes()).isEqualTo(4L * 1024 * 1024 + 500 * 24L * 1024);
        // 500 actors at ≤32KiB incremental stays under the certified bound.
        assertThat(report.incrementalBytesPerActor()).isLessThanOrEqualTo(32L * 1024);
    }

    @Test
    void tierCountsAggregate() {
        scheduler.evaluate(List.of(actor("a", 10), actor("b", 100), actor("c", 300)));
        assertThat(scheduler.tierCounts())
                .containsEntry(SimulationTier.ACTIVE, 1)
                .containsEntry(SimulationTier.NEARBY, 1)
                .containsEntry(SimulationTier.DORMANT, 1);
    }
}
