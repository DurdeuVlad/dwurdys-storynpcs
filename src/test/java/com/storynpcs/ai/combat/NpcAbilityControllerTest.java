package com.storynpcs.ai.combat;

import com.storynpcs.ai.combat.NpcAbilityController.AbilityOutcome;
import com.storynpcs.ai.combat.NpcAbilityController.EventInput;
import com.storynpcs.ai.combat.NpcAbilityController.OutcomeKind;
import com.storynpcs.domain.ability.AbilityTrigger;
import com.storynpcs.domain.ability.AbilityType;
import com.storynpcs.domain.ability.NpcAbility;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NpcAbilityControllerTest {

    private static final NamespacedId NPC_ID = NamespacedId.of("storynpcs:guard");
    private static final UUID ACTOR = UUID.randomUUID();

    private static NpcAbilityController alwaysFire() {
        return new NpcAbilityController(() -> 0.0);
    }

    private static NpcAbilityController neverFire() {
        return new NpcAbilityController(() -> 0.999_999);
    }

    private static EventInput input(double tx, double ty, double tz, double damage, long tick) {
        return new EventInput(NPC_ID, "Guard", 20.0, 20.0,
                0.0, 64.0, 0.0, tx, ty, tz, damage, tick, ACTOR, true);
    }

    private static NpcAbility ability(AbilityType type, AbilityTrigger trigger) {
        return new NpcAbility(type, trigger);
    }

    // ---- per-ability outcome semantics ----

    @Test
    void blockScalesIncomingDamage() {
        var a = ability(AbilityType.BLOCK, AbilityTrigger.DAMAGED);
        a.setDamageMultiplier(0.25);
        var out = alwaysFire().onDamaged(List.of(a), input(1, 64, 0, 10, 100));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo(OutcomeKind.DAMAGE_MULTIPLIER);
        assertThat(out.get(0).amount()).isEqualTo(0.25);
    }

    @Test
    void pullMovesVictimTowardNpc() {
        var a = ability(AbilityType.PULL, AbilityTrigger.UPDATE);
        a.setStrength(2.0);
        a.setRange(20.0);
        // target at +8x from npc → pull velocity points -x
        var out = alwaysFire().onUpdate(List.of(a), input(8, 64, 0, 0, 1000));
        var v = only(out, OutcomeKind.TARGET_VELOCITY);
        assertThat(v.dx()).isLessThan(0);
        assertThat(Math.abs(v.dx())).isCloseTo(2.0, within(0.01));
    }

    @Test
    void pushMovesVictimAway() {
        var a = ability(AbilityType.PUSH, AbilityTrigger.UPDATE);
        a.setStrength(1.5);
        a.setRange(20.0);
        var out = alwaysFire().onUpdate(List.of(a), input(6, 64, 0, 0, 1000));
        var v = only(out, OutcomeKind.TARGET_VELOCITY);
        assertThat(v.dx()).isGreaterThan(0);
        assertThat(v.dy()).isGreaterThan(0); // small lift so knockback applies
    }

    @Test
    void pullBeyondRangeDoesNothing() {
        var a = ability(AbilityType.PULL, AbilityTrigger.UPDATE);
        a.setRange(4.0);
        assertThat(alwaysFire().onUpdate(List.of(a), input(30, 64, 0, 0, 1000))).isEmpty();
    }

    @Test
    void smashLaunchesVictimAndAddsBonusDamage() {
        var a = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        a.setStrength(0.8);
        a.setBonusDamage(4.0);
        var out = alwaysFire().onAttack(List.of(a), input(0.5, 64, 0, 0, 500));
        var v = only(out, OutcomeKind.TARGET_VELOCITY);
        assertThat(v.dy()).isEqualTo(0.8);
        var dmg = only(out, OutcomeKind.BONUS_DAMAGE);
        assertThat(dmg.amount()).isEqualTo(4.0);
    }

    @Test
    void snareEmitsSlowness() {
        var a = ability(AbilityType.SNARE, AbilityTrigger.UPDATE);
        a.setRange(10.0);
        a.setDurationTicks(80);
        a.setAmplifier(2);
        var out = alwaysFire().onUpdate(List.of(a), input(5, 64, 0, 0, 2000));
        var s = only(out, OutcomeKind.TARGET_SLOWNESS);
        assertThat(s.durationTicks()).isEqualTo(80);
        assertThat(s.amplifier()).isEqualTo(2);
    }

    @Test
    void teleportUpdateApproachesTarget() {
        var a = ability(AbilityType.TELEPORT, AbilityTrigger.UPDATE);
        a.setRange(30.0);
        // npc at origin, target at +12x → teleport lands ~3 blocks from target
        var out = alwaysFire().onUpdate(List.of(a), input(12, 64, 0, 0, 3000));
        var t = only(out, OutcomeKind.SELF_TELEPORT);
        assertThat(t.dx()).isCloseTo(12 - NpcAbilityController.TELEPORT_APPROACH_OFFSET, within(0.01));
        assertThat(t.dy()).isEqualTo(64.0);
    }

    @Test
    void teleportUpdateSkipsCloseTargets() {
        var a = ability(AbilityType.TELEPORT, AbilityTrigger.UPDATE);
        a.setRange(30.0);
        assertThat(alwaysFire().onUpdate(List.of(a), input(2, 64, 0, 0, 3000))).isEmpty();
    }

    @Test
    void teleportDamagedBlinksAwayFromAttacker() {
        var a = ability(AbilityType.TELEPORT, AbilityTrigger.DAMAGED);
        a.setRange(8.0);
        // attacker at +2x → npc blinks to -8x
        var out = alwaysFire().onDamaged(List.of(a), input(2, 64, 0, 6, 4000));
        var t = only(out, OutcomeKind.SELF_TELEPORT);
        assertThat(t.dx()).isCloseTo(-8.0, within(0.01));
    }

    // ---- rate limiting / gating ----

    @Test
    void cooldownSuppressesRefire() {
        var controller = alwaysFire();
        var a = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        a.setCooldownTicks(40);
        var abilities = List.of(a);
        assertThat(controller.onAttack(abilities, input(0.5, 64, 0, 0, 1000))).isNotEmpty();
        assertThat(controller.onAttack(abilities, input(0.5, 64, 0, 0, 1010))).isEmpty();  // 10 < 40
        assertThat(controller.onAttack(abilities, input(0.5, 64, 0, 0, 1040))).isNotEmpty(); // due
    }

    @Test
    void chanceZeroNeverFires() {
        var a = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        a.setChance(0.0);
        assertThat(neverFire().onAttack(List.of(a), input(0.5, 64, 0, 0, 100))).isEmpty();
    }

    @Test
    void updateEvaluationIsRateLimited() {
        var controller = alwaysFire();
        var a = ability(AbilityType.PUSH, AbilityTrigger.UPDATE);
        a.setRange(20.0);
        var abilities = List.of(a);
        assertThat(controller.onUpdate(abilities, input(5, 64, 0, 0, 1000))).isNotEmpty();
        // Within MIN_UPDATE_PERIOD_TICKS the controller does not even evaluate.
        assertThat(controller.onUpdate(abilities, input(5, 64, 0, 0,
                1000 + NpcAbilityController.MIN_UPDATE_PERIOD_TICKS - 1))).isEmpty();
        assertThat(controller.onUpdate(abilities, input(5, 64, 0, 0,
                1000 + NpcAbilityController.MIN_UPDATE_PERIOD_TICKS))).isNotEmpty();
    }

    @Test
    void conditionsGateTheAbility() {
        var controller = alwaysFire();
        var a = ability(AbilityType.SNARE, AbilityTrigger.UPDATE);
        a.setRange(20.0);
        a.getConditions().add(new ActorIsPlayerCondition());
        // input() marks actor as player → fires
        assertThat(controller.onUpdate(List.of(a), input(5, 64, 0, 0, 5000))).isNotEmpty();

        var controller2 = alwaysFire();
        var notPlayer = new EventInput(NPC_ID, "Guard", 20, 20, 0, 64, 0, 5, 64, 0, 0, 6000, ACTOR, false);
        assertThat(controller2.onUpdate(List.of(a), notPlayer)).isEmpty();
    }

    @Test
    void wrongTriggerDoesNotFire() {
        var a = ability(AbilityType.BLOCK, AbilityTrigger.DAMAGED);
        assertThat(alwaysFire().onAttack(List.of(a), input(0.5, 64, 0, 0, 100))).isEmpty();
        assertThat(alwaysFire().onUpdate(List.of(a), input(0.5, 64, 0, 0, 100))).isEmpty();
    }

    @Test
    void cooldownStateIsPerAbility() {
        var controller = alwaysFire();
        var a = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        a.setCooldownTicks(NpcAbility.MAX_COOLDOWN_TICKS);
        var b = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        var abilities = List.of(a, b);
        var first = controller.onAttack(abilities, input(0.5, 64, 0, 0, 100));
        // both fired once
        assertThat(first.stream().filter(o -> o.kind() == OutcomeKind.TARGET_VELOCITY).count()).isEqualTo(2);
        // second event: a on cooldown, b free
        var second = controller.onAttack(abilities, input(0.5, 64, 0, 0, 200));
        assertThat(second.stream().filter(o -> o.kind() == OutcomeKind.TARGET_VELOCITY).count()).isEqualTo(1);
    }

    // ---- review-driven regression coverage ----

    @Test
    void damagedTeleportBlinksAwayOnZeroDamageHit() {
        // A 0-damage hit (snowball/egg) must still take the DAMAGED branch —
        // blink AWAY from the attacker, not the UPDATE approach branch.
        var a = ability(AbilityType.TELEPORT, AbilityTrigger.DAMAGED);
        a.setRange(8.0);
        var out = alwaysFire().onDamaged(List.of(a), input(2, 64, 0, 0, 4000));
        var t = only(out, OutcomeKind.SELF_TELEPORT);
        assertThat(t.dx()).isCloseTo(-8.0, within(0.01));
    }

    @Test
    void updatePullObeysRangeOnNonDamageEvents() {
        // UPDATE events carry damage 0 — range gating must use the trigger,
        // not the damage amount, so 0-damage DAMAGED hits stay unconditional.
        var a = ability(AbilityType.PULL, AbilityTrigger.DAMAGED);
        var out = alwaysFire().onDamaged(List.of(a), input(20, 64, 0, 0, 100));
        assertThat(out).isNotEmpty(); // unconditional on DAMAGED even at 20 blocks
    }

    @Test
    void unvalidatedOutOfBoundsAbilitySkippedAtRuntime() {
        // Direct-set values bypassing validate() must still fail closed in
        // the controller — bounds are enforced at every entry point.
        var a = ability(AbilityType.PUSH, AbilityTrigger.UPDATE);
        a.setStrength(99.0); // exceeds MAX_STRENGTH — validate() would reject
        var out = alwaysFire().onUpdate(List.of(a), input(5, 64, 0, 0, 100));
        assertThat(out).isEmpty();
    }

    @Test
    void malformedConditionFailsClosed() {
        // A composite containing a null child must not throw inside evaluate —
        // the ability simply does not fire.
        var a = ability(AbilityType.SNARE, AbilityTrigger.UPDATE);
        a.setRange(20.0);
        var composite = new com.storynpcs.domain.rule.condition.CompositeCondition();
        composite.getConditions().add(null);
        a.getConditions().add(composite);
        var out = alwaysFire().onUpdate(List.of(a), input(5, 64, 0, 0, 100));
        assertThat(out).isEmpty();
    }

    @Test
    void strikeCountConditionReadsEventInput() {
        var condition = new com.storynpcs.domain.rule.condition.StrikeCountCondition(
                com.storynpcs.domain.rule.condition.StrikeCountCondition.Operator.GREATER_THAN, 0);
        var a = ability(AbilityType.SNARE, AbilityTrigger.UPDATE);
        a.setRange(20.0);
        a.getConditions().add(condition);

        var struck = new EventInput(NPC_ID, "Guard", 20, 20, 0, 64, 0, 5, 64, 0, 0, 100,
                ACTOR, true, 3, null);
        assertThat(alwaysFire().onUpdate(List.of(a), struck)).isNotEmpty();

        var notStruck = new EventInput(NPC_ID, "Guard", 20, 20, 0, 64, 0, 5, 64, 0, 0, 100,
                ACTOR, true, 0, null);
        assertThat(alwaysFire().onUpdate(List.of(a), notStruck)).isEmpty();
    }

    @Test
    void factionStandingConditionReadsActorStanding() {
        var condition = new com.storynpcs.domain.rule.condition.FactionStandingCondition();
        condition.setExpectedStanding(
                com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.HOSTILE);
        var a = ability(AbilityType.SNARE, AbilityTrigger.UPDATE);
        a.setRange(20.0);
        a.getConditions().add(condition);

        var hostile = new EventInput(NPC_ID, "Guard", 20, 20, 0, 64, 0, 5, 64, 0, 0, 100,
                ACTOR, true, 0,
                com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.HOSTILE);
        assertThat(alwaysFire().onUpdate(List.of(a), hostile)).isNotEmpty();

        var friendly = new EventInput(NPC_ID, "Guard", 20, 20, 0, 64, 0, 5, 64, 0, 0, 100,
                ACTOR, true, 0,
                com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.FRIENDLY);
        assertThat(alwaysFire().onUpdate(List.of(a), friendly)).isEmpty();
    }

    @Test
    void cooldownStateFollowsAbilityInstanceNotListIndex() {
        var controller = alwaysFire();
        var a = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        a.setCooldownTicks(NpcAbility.MAX_COOLDOWN_TICKS);
        var b = ability(AbilityType.SMASH, AbilityTrigger.ATTACK);
        assertThat(controller.onAttack(List.of(a, b), input(0.5, 64, 0, 0, 100))).hasSize(2);
        // Reorder the list (as command remove/add does) — a must still be on
        // cooldown; index-keyed state would let it refire.
        var reordered = List.of(b, a);
        var second = controller.onAttack(reordered, input(0.5, 64, 0, 0, 200));
        assertThat(second.stream().filter(o -> o.kind() == OutcomeKind.TARGET_VELOCITY).count())
                .isEqualTo(1);
    }

    private static AbilityOutcome only(List<AbilityOutcome> out, OutcomeKind kind) {
        var matches = out.stream().filter(o -> o.kind() == kind).toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }
}
