package com.storynpcs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.companion.CompanionProfile;
import com.storynpcs.domain.companion.WageLedger;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.faction.FactionDeletionPlanner;
import com.storynpcs.domain.job.JobConfig;
import com.storynpcs.domain.job.JobInstance;
import com.storynpcs.domain.job.JobType;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.role.social.BardRole;
import com.storynpcs.domain.role.social.HealerRole;
import com.storynpcs.domain.role.social.PostmanRole;
import com.storynpcs.domain.transport.TransportEvaluator;
import com.storynpcs.domain.transport.TransportLocation;

class P6DomainTest {

    private static NamespacedId id(String name) {
        return NamespacedId.of("storynpcs", name);
    }

    // --- P6-1 faction matrix + deletion ------------------------------------

    @Test
    void factionMatrixFieldsAndBounds() {
        Faction f = new Faction(id("guards"), "Guards", 1000, 500, 1500);
        f.setColor(0xFF8800);
        f.setPassive(true);
        f.setRelationship(id("bandits"), "hostile");
        assertThat(f.getRelationships()).containsEntry("storynpcs:bandits", "HOSTILE");
        assertThatThrownBy(() -> f.setColor(0x1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.setRelationship(id("x"), "FRIENEMY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.getRelationships().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bulkRelationshipsCannotBypassStandingValidation() {
        Faction f = new Faction(id("guards"), "Guards", 1000, 500, 1500);
        f.setRelationships(java.util.Map.of("storynpcs:bandits", "hostile", "custom:allies", "Friendly"));
        assertThat(f.getRelationships())
                .containsEntry("storynpcs:bandits", "HOSTILE")
                .containsEntry("custom:allies", "FRIENDLY");

        assertThatThrownBy(() -> f.setRelationships(java.util.Map.of("storynpcs:x", "FRIENEMY")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.setRelationships(java.util.Map.of("", "HOSTILE")))
                .isInstanceOf(IllegalArgumentException.class);
        // A failed bulk assignment leaves no partially-applied entries behind.
        assertThat(f.getRelationships())
                .containsEntry("storynpcs:bandits", "HOSTILE")
                .doesNotContainKey("storynpcs:x");
        f.setRelationships(null);
        assertThat(f.getRelationships()).isEmpty();
    }

    @Test
    void deletionPlanRepairsOrFailsExplicitly() {
        FactionDeletionPlanner planner = new FactionDeletionPlanner();
        NpcDefinition npc = new NpcDefinition();
        npc.setId(id("guard-1"));
        npc.setFactionId(id("guards"));
        Faction allied = new Faction(id("villagers"), "Villagers", 1000, 500, 1500);
        allied.setRelationship(id("guards"), "FRIENDLY");
        List<Faction> factions = List.of(allied);
        List<NpcDefinition> npcs = List.of(npc);

        // No fallback → NPC reference blocks deletion.
        var blocked = planner.plan(id("guards"), npcs, List.of(), factions,
                List.of(), List.of(), List.of(), null);
        assertThat(blocked.viable()).isFalse();
        assertThat(blocked.diagnostics().get(0)).contains("guard-1");
        assertThat(blocked.references()).hasSize(2);

        // Fallback → viable; apply re-points NPC and strips matrix entry.
        var viable = planner.plan(id("guards"), npcs, List.of(), factions,
                List.of(), List.of(), List.of(), id("villagers"));
        assertThat(viable.viable()).isTrue();
        var repaired = planner.apply(viable, npcs, factions);
        assertThat(repaired).hasSize(2);
        assertThat(npc.getFactionId()).isEqualTo(id("villagers"));
        assertThat(allied.getRelationships()).doesNotContainKey("storynpcs:guards");
        assertThatThrownBy(() -> planner.apply(blocked, npcs, factions))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- P6-2 social roles ---------------------------------------------------

    @Test
    void socialRolesCarryBoundedConfig() {
        PostmanRole postman = new PostmanRole();
        postman.setMailboxCapacity(48);
        assertThatThrownBy(() -> postman.setMailboxCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> postman.setMailboxCapacity(65))
                .isInstanceOf(IllegalArgumentException.class);
        HealerRole healer = new HealerRole();
        healer.setTargetPolicy(HealerRole.TargetPolicy.ALLIES);
        assertThat(healer.getScanPeriodTicks()).isEqualTo(20);
        assertThatThrownBy(() -> healer.setHealAmount(0))
                .isInstanceOf(IllegalArgumentException.class);
        BardRole bard = new BardRole();
        bard.setSongId(id("song_ballad"));
        assertThatThrownBy(() -> bard.setEffectRadiusBlocks(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- P6-3 transport -----------------------------------------------------

    @Test
    void transportEvaluationFailsBeforeFee() {
        TransportEvaluator evaluator = new TransportEvaluator();
        TransportLocation loc = new TransportLocation();
        loc.setId(id("haven"));
        loc.setName("Haven");
        loc.setDimensionId(id("overworld"));
        loc.setFee(50);

        var loaded = new TransportEvaluator.DestinationFacts(true, true, true);
        assertThat(evaluator.evaluate(loc, Set.of(id("haven")), true, 100, loaded))
                .isInstanceOf(TransportEvaluator.Evaluation.Approved.class);

        // Locked without unlock — a gated destination unmet+unlisted rejects.
        loc.getUnlockConditions(); // read-only view
        loc.setUnlockConditions(List.of(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.QUEST_STATUS,
                "storynpcs:intro", "EQUALS", "COMPLETED")));
        assertThat(evaluator.evaluate(loc, Set.of(), false, 100, loaded))
                .isInstanceOf(TransportEvaluator.Evaluation.Rejected.class);
        // Unlocked but unsafe → rejected, no charge.
        var unsafe = new TransportEvaluator.DestinationFacts(true, false, true);
        var rejected = (TransportEvaluator.Evaluation.Rejected)
                evaluator.evaluate(loc, Set.of(id("haven")), true, 100, unsafe);
        assertThat(rejected.reason()).isEqualTo(TransportEvaluator.RejectReason.DESTINATION_UNSAFE);
        // Safe but poor.
        var poor = (TransportEvaluator.Evaluation.Rejected)
                evaluator.evaluate(loc, Set.of(id("haven")), true, 10, loaded);
        assertThat(poor.reason()).isEqualTo(TransportEvaluator.RejectReason.FEE_UNMET);
        // Unloaded dimension.
        var gone = new TransportEvaluator.DestinationFacts(false, true, true);
        assertThat(((TransportEvaluator.Evaluation.Rejected)
                evaluator.evaluate(loc, Set.of(id("haven")), true, 100, gone)).reason())
                .isEqualTo(TransportEvaluator.RejectReason.DESTINATION_UNLOADED);

        // UI listing hides locked destinations unless visibleWhenLocked.
        assertThat(evaluator.visibleFor(List.of(loc), Set.of())).isEmpty();
        loc.setVisibleWhenLocked(true);
        assertThat(evaluator.visibleFor(List.of(loc), Set.of())).containsExactly(loc);
    }

    // --- P6-4 jobs ----------------------------------------------------------

    @Test
    void allElevenJobsHaveTypedConfigsAndLifecycle() {
        assertThat(JobType.values()).hasSize(11);
        JobConfig spawner = new JobConfig(JobType.SPAWNER);
        assertThatThrownBy(spawner::validate).isInstanceOf(IllegalStateException.class);
        spawner.setSpawnDefinitionId(id("zombie_squad"));
        spawner.validate();
        assertThatThrownBy(() -> spawner.setSpawnCount(100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobConfig(JobType.FARMER).setWorkRadiusBlocks(0))
                .isInstanceOf(IllegalArgumentException.class);

        JobInstance job = new JobInstance(UUID.randomUUID(), spawner);
        spawner.setTickPeriod(10);
        assertThat(job.shouldRun(10)).isTrue();
        job.markRan(10);
        assertThat(job.shouldRun(15)).isFalse();
        assertThat(job.shouldRun(21)).isTrue();
        job.onActorUnload();
        assertThat(job.getState()).isEqualTo(JobInstance.State.PAUSED); // pauseOnUnload default
        assertThat(job.shouldRun(100)).isFalse();
        job.resume();
        assertThat(job.getState()).isEqualTo(JobInstance.State.RUNNING);
        job.stop();
        assertThatThrownBy(() -> job.markRan(200)).isInstanceOf(IllegalStateException.class);
    }

    // --- P6-5 companion -----------------------------------------------------

    @Test
    void wageChargesExactlyOncePerPeriod() {
        WageLedger ledger = new WageLedger();
        UUID companion = UUID.randomUUID();
        int[] paid = {0};
        WageLedger.Payer payer = (c, amount) -> { paid[0] += amount; return true; };

        assertThat(WageLedger.periodFor(0, 24_000, 24_000)).isEqualTo(1);
        assertThat(ledger.charge(1, 50, payer, companion))
                .isEqualTo(WageLedger.ChargeOutcome.CHARGED);
        assertThat(paid[0]).isEqualTo(50);
        // Same period cannot charge twice — crash-safe exactly-once.
        assertThat(ledger.charge(1, 50, payer, companion))
                .isEqualTo(WageLedger.ChargeOutcome.ALREADY_CHARGED);
        assertThat(paid[0]).isEqualTo(50);
        // Insufficient funds → explicit outcome, period NOT consumed.
        WageLedger.Payer broke = (c, a) -> false;
        assertThat(ledger.charge(2, 50, broke, companion))
                .isEqualTo(WageLedger.ChargeOutcome.INSUFFICIENT_FUNDS);
        assertThat(ledger.charge(2, 50, payer, companion))
                .isEqualTo(WageLedger.ChargeOutcome.CHARGED);
    }

    @Test
    void companionProfileBoundsAndStageSelection() {
        CompanionProfile profile = new CompanionProfile();
        profile.setWageAmount(25);
        profile.setUnloadPolicy(CompanionProfile.UnloadPolicy.DESPAWN);
        profile.setInsufficientFundsPolicy(CompanionProfile.InsufficientFundsPolicy.DISMISS);
        assertThatThrownBy(() -> profile.setWageAmount(-1))
                .isInstanceOf(IllegalArgumentException.class);

        profile.setStages(List.of(
                new CompanionProfile.CompanionStage(id("squire"), 0, 1.0),
                new CompanionProfile.CompanionStage(id("knight"), 100_000, 1.5)));
        assertThat(profile.activeStage(50).getId()).isEqualTo(id("squire"));
        assertThat(profile.activeStage(200_000).getId()).isEqualTo(id("knight"));
        assertThat(profile.activeStage(200_000).getStatMultiplier()).isEqualTo(1.5);
        assertThatThrownBy(() -> new CompanionProfile.Talent(id("t"), 5, 3))
                .isInstanceOf(IllegalArgumentException.class);
        // Inventory is the P3-4 container contract.
        assertThat(profile.getInventory()).isNotNull();
    }
}
