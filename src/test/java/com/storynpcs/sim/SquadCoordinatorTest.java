package com.storynpcs.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.sim.SquadCoordinator.Assignment;

class SquadCoordinatorTest {

    private static UUID id(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes());
    }

    @Test
    void coordinateAssignsUniqueTargetsDeterministically() {
        SquadCoordinator coordinator = new SquadCoordinator();
        Set<UUID> squad = Set.of(id("a"), id("b"), id("c"));
        Set<UUID> candidates = Set.of(id("x"), id("y"));
        Map<UUID, Double> threat = Map.of(
                id("x"), 9.0, id("y"), 4.0,
                id("a"), 5.0, id("b"), 7.0, id("c"), 1.0);

        List<Assignment> first = coordinator.coordinate(squad, candidates, threat::get);
        List<Assignment> second = coordinator.coordinate(squad, candidates, threat::get);
        assertThat(first).isEqualTo(second); // deterministic replay

        Set<UUID> targets = new HashSet<>();
        for (Assignment a : first) {
            assertThat(targets.add(a.targetId())).as("no duplicate targets").isTrue();
        }
        // Only 2 candidates for 3 members — exactly 2 assignments.
        assertThat(first).hasSize(2);
        // Highest-threat candidate (x) claimed first.
        assertThat(first.get(0).targetId()).isEqualTo(id("x"));
    }

    @Test
    void releaseFreesClaimForReassignment() {
        SquadCoordinator coordinator = new SquadCoordinator();
        UUID actor = id("a");
        coordinator.coordinate(Set.of(actor), Set.of(id("x")), u -> 1.0);
        assertThat(coordinator.assignmentOf(actor)).isPresent();
        assertThat(coordinator.release(actor)).isPresent();
        assertThat(coordinator.assignmentOf(actor)).isEmpty();
    }

    @Test
    void squadAndCandidateListsAreBounded() {
        SquadCoordinator coordinator = new SquadCoordinator(4, 8);
        Set<UUID> squad = new HashSet<>();
        Set<UUID> candidates = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            squad.add(id("s" + i));
            candidates.add(id("c" + i));
        }
        List<Assignment> assigned = coordinator.coordinate(squad, candidates, u -> 1.0);
        assertThat(assigned.size()).isLessThanOrEqualTo(4);
    }

    @Test
    void unassignedDetectsStaleTargets() {
        SquadCoordinator coordinator = new SquadCoordinator();
        UUID actor = id("a");
        coordinator.coordinate(Set.of(actor), Set.of(id("x")), u -> 1.0);
        assertThat(coordinator.unassigned(Set.of(id("x")))).isEmpty();
        assertThat(coordinator.unassigned(Set.of(id("other")))).containsExactly(actor);
    }
}
