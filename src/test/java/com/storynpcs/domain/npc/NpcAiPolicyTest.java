package com.storynpcs.domain.npc;

import com.storynpcs.domain.ai.FactionRelationshipProvider;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.ai.combat.ThreatManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpcAiPolicyTest {

    @Test
    void sixAnimationStancesRoundTrip() {
        assertThat(NpcAi.AnimationStance.values()).hasSize(6);
        NpcAi ai = new NpcAi();
        ai.setAnimationStance(NpcAi.AnimationStance.SITTING);
        assertThat(ai.getAnimationStance()).isEqualTo(NpcAi.AnimationStance.SITTING);
        ai.setAnimationStance(null);
        assertThat(ai.getAnimationStance()).isEqualTo(NpcAi.AnimationStance.NORMAL);
    }

    @Test
    void targetingPolicyIsDataDrivenAndBounded() {
        NpcAi ai = new NpcAi();
        assertThat(ai.isAttackOnSight()).isFalse();
        assertThat(ai.getTargetPriority()).isEqualTo(NpcAi.TargetPriority.NEAREST);
        assertThat(ai.getTacticalBehavior()).isEqualTo(NpcAi.TacticalBehavior.NONE);

        ai.setAttackOnSight(true);
        ai.setTargetFactionIds(Set.of(
                NamespacedId.of("storynpcs:goblins"), NamespacedId.of("storynpcs:bandits")));
        ai.setTargetPriority(NpcAi.TargetPriority.WEAKEST);
        ai.setTacticalBehavior(NpcAi.TacticalBehavior.HIT_AND_RUN);
        ai.setTacticalRadius(12);
        ai.setLeapAtTarget(true);
        ai.setAllyDefenseRadius(24);

        assertThat(ai.isAttackOnSight()).isTrue();
        assertThat(ai.getTargetFactionIds()).containsExactlyInAnyOrder(
                NamespacedId.of("storynpcs:goblins"), NamespacedId.of("storynpcs:bandits"));
        assertThat(ai.getTargetPriority()).isEqualTo(NpcAi.TargetPriority.WEAKEST);
        assertThat(ai.getTacticalBehavior()).isEqualTo(NpcAi.TacticalBehavior.HIT_AND_RUN);
        assertThat(ai.getTacticalRadius()).isEqualTo(12);
        assertThat(ai.isLeapAtTarget()).isTrue();
        assertThat(ai.getAllyDefenseRadius()).isEqualTo(24);

        // Ally-defense and tactical radii are hard-bounded — no unbounded world scans.
        ai.setAllyDefenseRadius(999);
        assertThat(ai.getAllyDefenseRadius()).isEqualTo(64);
        ai.setTacticalRadius(0);
        assertThat(ai.getTacticalRadius()).isEqualTo(1);
    }

    @Test
    void targetFactionIdsRejectsOversizedOrNullSets() {
        NpcAi ai = new NpcAi();
        java.util.Set<NamespacedId> oversized = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 65; i++) oversized.add(NamespacedId.of("storynpcs:f" + i));
        assertThatThrownBy(() -> ai.setTargetFactionIds(oversized));
        assertThatThrownBy(() -> ai.setTargetFactionIds(
                java.util.Set.of(NamespacedId.of("storynpcs:a"), null)));

        ai.setTargetFactionIds(Set.of(NamespacedId.of("storynpcs:goblins")));
        // Defensive copy: caller mutation cannot corrupt the definition.
        assertThatThrownBy(() -> ai.getTargetFactionIds().add(NamespacedId.of("storynpcs:x")));
    }

    @Test
    void relationshipProviderResolvesDeterministicallyWithNeutralFallback() {
        NamespacedId guards = NamespacedId.of("storynpcs:town_guard");
        NamespacedId goblins = NamespacedId.of("storynpcs:goblins");
        NamespacedId elves = NamespacedId.of("storynpcs:elves");

        FactionRelationshipProvider provider = FactionRelationshipProvider.of(Map.of(
                FactionRelationshipProvider.PairKey.of(guards, goblins),
                FactionRelationshipProvider.Relationship.HOSTILE));

        // Symmetric: pair order does not matter.
        assertThat(provider.relationship(guards, goblins))
                .isEqualTo(FactionRelationshipProvider.Relationship.HOSTILE);
        assertThat(provider.relationship(goblins, guards))
                .isEqualTo(FactionRelationshipProvider.Relationship.HOSTILE);
        // Unknown pair -> explicit NEUTRAL fallback, never hostile by accident.
        assertThat(provider.relationship(guards, elves))
                .isEqualTo(FactionRelationshipProvider.Relationship.NEUTRAL);
        assertThat(provider.relationship(elves, guards))
                .isEqualTo(FactionRelationshipProvider.Relationship.NEUTRAL);
        // Self relationship is friendly; null inputs resolve NEUTRAL.
        assertThat(provider.relationship(guards, guards))
                .isEqualTo(FactionRelationshipProvider.Relationship.FRIENDLY);
        assertThat(provider.relationship(guards, null))
                .isEqualTo(FactionRelationshipProvider.Relationship.NEUTRAL);
        assertThat(FactionRelationshipProvider.neutral().relationship(goblins, elves))
                .isEqualTo(FactionRelationshipProvider.Relationship.NEUTRAL);
    }

    @Test
    void threatTargetChangesEmitReasonsThroughTheSink() {
        ThreatManager threat = new ThreatManager();
        List<String> events = new ArrayList<>();
        threat.setAggroEventSink((target, aggro, reason) ->
                events.add((aggro ? "+" : "-") + target + ":" + reason));

        UUID attacker = UUID.randomUUID();
        threat.addThreat(attacker, 100);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).startsWith("+" + attacker + ":");

        // Re-adding threat for the same target does not re-emit.
        threat.addThreat(attacker, 50);
        assertThat(events).hasSize(1);

        // Forgiving the engaged target emits a disengage with its reason.
        threat.forgive(attacker);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).startsWith("-" + attacker + ":TARGET_FORGIVEN");

        // Decay to empty emits the cleared reason exactly once, not every tick.
        // The aggro timer (400 ticks) must expire before decay drains the table.
        threat.addThreat(attacker, 100);
        int before = events.size();
        for (int i = 0; i < 500; i++) threat.tick(10, 1);
        assertThat(threat.getThreatTable()).isEmpty();
        assertThat(events.size()).isEqualTo(before + 1);
        assertThat(events.get(events.size() - 1)).startsWith("-");
    }

    @Test
    void aiPolicySurvivesDefinitionJsonRoundTrip() {
        NpcDefinition original = new NpcDefinition(
                NamespacedId.of("storynpcs", "skirmisher"), "Skirmisher");
        NpcAi ai = original.getAi();
        ai.setMovementType(NpcAi.MovementType.PATHING);
        ai.setAnimationStance(NpcAi.AnimationStance.SNEAKING);
        ai.setAttackOnSight(true);
        ai.setTargetFactionIds(Set.of(NamespacedId.of("storynpcs:goblins")));
        ai.setTargetPriority(NpcAi.TargetPriority.STRONGEST);
        ai.setTacticalBehavior(NpcAi.TacticalBehavior.STALK);
        ai.setTacticalRadius(10);
        ai.setLeapAtTarget(true);
        ai.setAllyDefenseRadius(20);

        NpcDefinition restored = NpcDefinitionSerde
                .fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();
        NpcAi restoredAi = restored.getAi();

        assertThat(restoredAi.getAnimationStance()).isEqualTo(NpcAi.AnimationStance.SNEAKING);
        assertThat(restoredAi.isAttackOnSight()).isTrue();
        assertThat(restoredAi.getTargetFactionIds())
                .containsExactly(NamespacedId.of("storynpcs:goblins"));
        assertThat(restoredAi.getTargetPriority()).isEqualTo(NpcAi.TargetPriority.STRONGEST);
        assertThat(restoredAi.getTacticalBehavior()).isEqualTo(NpcAi.TacticalBehavior.STALK);
        assertThat(restoredAi.getTacticalRadius()).isEqualTo(10);
        assertThat(restoredAi.isLeapAtTarget()).isTrue();
        assertThat(restoredAi.getAllyDefenseRadius()).isEqualTo(20);
    }
}
