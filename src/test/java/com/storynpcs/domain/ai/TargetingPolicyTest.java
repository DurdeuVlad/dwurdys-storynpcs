package com.storynpcs.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.ai.FactionRelationshipProvider.PairKey;
import com.storynpcs.domain.ai.FactionRelationshipProvider.Relationship;
import com.storynpcs.domain.ai.TargetingPolicy.Candidate;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;

/**
 * Attack-on-sight eligibility and target-priority selection — the pure policy
 * that {@code NpcAttackOnSightGoal} delegates world scans to.
 */
class TargetingPolicyTest {

    private static final NamespacedId OWN = NamespacedId.of("storynpcs:town_guard");
    private static final NamespacedId GOBLINS = NamespacedId.of("storynpcs:goblins");
    private static final NamespacedId ELVES = NamespacedId.of("storynpcs:elves");
    private static final NamespacedId SQUIRES = NamespacedId.of("storynpcs:squires");

    private static NpcAi aggressive() {
        NpcAi ai = new NpcAi();
        ai.setAttackOnSight(true);
        return ai;
    }

    private static Candidate candidate(UUID id, NamespacedId faction, double distanceSq) {
        return new Candidate(id, faction, distanceSq, 20.0, 20.0, 0, false);
    }

    @Test
    void sightlessAndPassiveNpcsEngageNothing() {
        NpcAi passive = new NpcAi(); // attackOnSight defaults false
        var goblin = candidate(UUID.randomUUID(), GOBLINS, 4.0);
        assertThat(TargetingPolicy.isEligible(passive, OWN, false,
                FactionRelationshipProvider.neutral(), goblin)).isFalse();

        // Even attack-on-sight NPCs in a PASSIVE faction never engage.
        assertThat(TargetingPolicy.isEligible(aggressive(), OWN, true,
                FactionRelationshipProvider.neutral(), goblin)).isFalse();

        // Null inputs fail closed.
        assertThat(TargetingPolicy.isEligible(null, OWN, false,
                FactionRelationshipProvider.neutral(), goblin)).isFalse();
        assertThat(TargetingPolicy.isEligible(aggressive(), OWN, false,
                FactionRelationshipProvider.neutral(), null)).isFalse();
    }

    @Test
    void sameFactionIsNeverEligibleEvenWhenAuthored() {
        NpcAi ai = aggressive();
        // Authored lists can never produce friendly fire.
        ai.setTargetFactionIds(Set.of(OWN));
        var sameFaction = candidate(UUID.randomUUID(), OWN, 1.0);
        assertThat(TargetingPolicy.isEligible(ai, OWN, false,
                FactionRelationshipProvider.neutral(), sameFaction)).isFalse();
    }

    @Test
    void authoredTargetListEngagesListedFactionsOnly() {
        NpcAi ai = aggressive();
        ai.setTargetFactionIds(Set.of(GOBLINS));
        assertThat(TargetingPolicy.isEligible(ai, OWN, false,
                FactionRelationshipProvider.neutral(), candidate(UUID.randomUUID(), GOBLINS, 9.0)))
                .isTrue();
        // An unlisted faction stays unengaged — the authored list is exhaustive
        // unless the relationship matrix independently marks it hostile.
        assertThat(TargetingPolicy.isEligible(ai, OWN, false,
                FactionRelationshipProvider.neutral(), candidate(UUID.randomUUID(), ELVES, 9.0)))
                .isFalse();
    }

    @Test
    void hostileRelationshipEngagesNeutralAndFriendlyDoNot() {
        var provider = FactionRelationshipProvider.of(java.util.Map.of(
                PairKey.of(OWN, GOBLINS), Relationship.HOSTILE,
                PairKey.of(OWN, SQUIRES), Relationship.FRIENDLY));
        NpcAi ai = aggressive();

        assertThat(TargetingPolicy.isEligible(ai, OWN, false, provider,
                candidate(UUID.randomUUID(), GOBLINS, 9.0))).isTrue();
        assertThat(TargetingPolicy.isEligible(ai, OWN, false, provider,
                candidate(UUID.randomUUID(), SQUIRES, 9.0))).isFalse();
        assertThat(TargetingPolicy.isEligible(ai, OWN, false, provider,
                candidate(UUID.randomUUID(), ELVES, 9.0))).isFalse();
    }

    @Test
    void factionlessCandidatesRequireResolvedHostileStanding() {
        NpcAi ai = aggressive();
        var hostilePlayer = new Candidate(UUID.randomUUID(), null, 4.0, 20.0, 20.0, 0, true);
        var neutralPlayer = new Candidate(UUID.randomUUID(), null, 4.0, 20.0, 20.0, 0, false);
        assertThat(TargetingPolicy.isEligible(ai, OWN, false,
                FactionRelationshipProvider.neutral(), hostilePlayer)).isTrue();
        assertThat(TargetingPolicy.isEligible(ai, OWN, false,
                FactionRelationshipProvider.neutral(), neutralPlayer)).isFalse();
    }

    @Test
    void nearestPriorityPicksClosestWithDeterministicTieBreak() {
        NpcAi ai = aggressive();
        ai.setTargetFactionIds(Set.of(GOBLINS));
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID tieA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tieB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        var choice = TargetingPolicy.chooseTarget(ai, OWN, false,
                FactionRelationshipProvider.neutral(), List.of(
                        candidate(far, GOBLINS, 81.0),
                        candidate(tieB, GOBLINS, 16.0),
                        candidate(tieA, GOBLINS, 16.0),
                        candidate(near, ELVES, 1.0))); // ELVES not listed — ineligible

        // tieA wins the equal-distance pair on UUID ordering; the closer ELVES
        // candidate is ignored because its faction is not a valid target.
        assertThat(choice).contains(tieA);
    }

    @Test
    void weakestAndStrongestPrioritiesUseHealthAxes() {
        NpcAi weakest = aggressive();
        weakest.setTargetFactionIds(Set.of(GOBLINS));
        weakest.setTargetPriority(NpcAi.TargetPriority.WEAKEST);
        UUID strong = UUID.randomUUID();
        UUID weak = UUID.randomUUID();
        var choice = TargetingPolicy.chooseTarget(weakest, OWN, false,
                FactionRelationshipProvider.neutral(), List.of(
                        new Candidate(strong, GOBLINS, 1.0, 40.0, 40.0, 0, false),
                        new Candidate(weak, GOBLINS, 9.0, 4.0, 20.0, 0, false)));
        assertThat(choice).contains(weak);

        NpcAi strongest = aggressive();
        strongest.setTargetFactionIds(Set.of(GOBLINS));
        strongest.setTargetPriority(NpcAi.TargetPriority.STRONGEST);
        choice = TargetingPolicy.chooseTarget(strongest, OWN, false,
                FactionRelationshipProvider.neutral(), List.of(
                        new Candidate(weak, GOBLINS, 9.0, 4.0, 20.0, 0, false),
                        new Candidate(strong, GOBLINS, 1.0, 40.0, 80.0, 0, false)));
        assertThat(choice).contains(strong);
    }

    @Test
    void firstThreatPrefersProvokersAndFallsBackToNearest() {
        NpcAi ai = aggressive();
        ai.setTargetFactionIds(Set.of(GOBLINS));
        ai.setTargetPriority(NpcAi.TargetPriority.FIRST_THREAT);
        UUID provoker = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        var choice = TargetingPolicy.chooseTarget(ai, OWN, false,
                FactionRelationshipProvider.neutral(), List.of(
                        new Candidate(bystander, GOBLINS, 1.0, 20.0, 20.0, 0, false),
                        new Candidate(provoker, GOBLINS, 25.0, 20.0, 20.0, 90, false)));
        // The distant provoker outranks the nearer unthreatening candidate.
        assertThat(choice).contains(provoker);

        // With no threat on the table the nearest hostile wins — policy stays total.
        UUID near = UUID.randomUUID();
        choice = TargetingPolicy.chooseTarget(ai, OWN, false,
                FactionRelationshipProvider.neutral(), List.of(
                        candidate(UUID.randomUUID(), GOBLINS, 25.0),
                        candidate(near, GOBLINS, 4.0)));
        assertThat(choice).contains(near);
    }

    @Test
    void emptyAndIneligibleCandidateSetsYieldNoTarget() {
        NpcAi ai = aggressive();
        ai.setTargetFactionIds(Set.of(GOBLINS));
        assertThat(TargetingPolicy.chooseTarget(ai, OWN, false,
                FactionRelationshipProvider.neutral(), List.of())).isEmpty();
        assertThat(TargetingPolicy.chooseTarget(ai, OWN, false,
                FactionRelationshipProvider.neutral(), null)).isEmpty();
        assertThat(TargetingPolicy.chooseTarget(ai, OWN, false,
                FactionRelationshipProvider.neutral(), List.of(
                        candidate(UUID.randomUUID(), ELVES, 1.0)))).isEmpty();
    }

    @Test
    void providerFromFactionsResolvesMostHostileDeclaration() {
        var guards = new com.storynpcs.domain.faction.Faction(OWN, "Town Guard", 0, -100, 100);
        var goblins = new com.storynpcs.domain.faction.Faction(GOBLINS, "Goblins", 0, -100, 100);
        guards.setRelationshipTo(GOBLINS, com.storynpcs.domain.faction.FactionStanding.HOSTILE);
        goblins.setRelationshipTo(OWN, com.storynpcs.domain.faction.FactionStanding.FRIENDLY);

        var provider = FactionRelationshipProvider.fromFactions(List.of(guards, goblins));
        // Conflicting declarations resolve to the more hostile standing.
        assertThat(provider.relationship(OWN, GOBLINS)).isEqualTo(Relationship.HOSTILE);
        assertThat(provider.relationship(GOBLINS, OWN)).isEqualTo(Relationship.HOSTILE);
    }
}
