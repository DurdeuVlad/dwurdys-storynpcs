package com.storynpcs.domain.ability;

import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class NpcAbilityTest {

    private static NpcAbility valid(AbilityType type, AbilityTrigger trigger) {
        return new NpcAbility(type, trigger);
    }

    @Test
    void allSixTypesAcceptTheirCanonicalTriggers() {
        assertThatCode(() -> valid(AbilityType.BLOCK, AbilityTrigger.DAMAGED).validate()).doesNotThrowAnyException();
        assertThatCode(() -> valid(AbilityType.SMASH, AbilityTrigger.ATTACK).validate()).doesNotThrowAnyException();
        for (AbilityType t : new AbilityType[]{AbilityType.PULL, AbilityType.PUSH, AbilityType.SNARE, AbilityType.TELEPORT}) {
            assertThatCode(() -> valid(t, AbilityTrigger.UPDATE).validate()).doesNotThrowAnyException();
            assertThatCode(() -> valid(t, AbilityTrigger.DAMAGED).validate()).doesNotThrowAnyException();
        }
    }

    @Test
    void missingTypeRejected() {
        assertThatThrownBy(() -> new NpcAbility(null, AbilityTrigger.UPDATE).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("type");
    }

    @Test
    void missingTriggerRejected() {
        assertThatThrownBy(() -> new NpcAbility(AbilityType.BLOCK, null).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trigger");
    }

    @Test
    void incompatibleTriggerRejected() {
        assertThatThrownBy(() -> valid(AbilityType.BLOCK, AbilityTrigger.UPDATE).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot use trigger");
        assertThatThrownBy(() -> valid(AbilityType.SMASH, AbilityTrigger.DAMAGED).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot use trigger");
        assertThatThrownBy(() -> valid(AbilityType.PULL, AbilityTrigger.ATTACK).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot use trigger");
    }

    @Test
    void unboundedParametersRejected() {
        NpcAbility a = valid(AbilityType.PULL, AbilityTrigger.UPDATE);
        a.setRange(500.0);
        assertThatThrownBy(a::validate).hasMessageContaining("range");

        NpcAbility b = valid(AbilityType.PUSH, AbilityTrigger.UPDATE);
        b.setStrength(99.0);
        assertThatThrownBy(b::validate).hasMessageContaining("strength");

        NpcAbility c = valid(AbilityType.BLOCK, AbilityTrigger.DAMAGED);
        c.setDamageMultiplier(1.5);
        assertThatThrownBy(c::validate).hasMessageContaining("damageMultiplier");

        NpcAbility d = valid(AbilityType.SMASH, AbilityTrigger.ATTACK);
        d.setBonusDamage(50.0);
        assertThatThrownBy(d::validate).hasMessageContaining("bonusDamage");

        NpcAbility e = valid(AbilityType.SNARE, AbilityTrigger.UPDATE);
        e.setDurationTicks(10_000);
        assertThatThrownBy(e::validate).hasMessageContaining("durationTicks");

        NpcAbility f = valid(AbilityType.SNARE, AbilityTrigger.UPDATE);
        f.setAmplifier(9);
        assertThatThrownBy(f::validate).hasMessageContaining("amplifier");
    }

    @Test
    void cooldownAndChanceBounded() {
        NpcAbility a = valid(AbilityType.PULL, AbilityTrigger.UPDATE);
        a.setCooldownTicks(NpcAbility.MAX_COOLDOWN_TICKS + 1);
        assertThatThrownBy(a::validate).hasMessageContaining("cooldownTicks");

        NpcAbility b = valid(AbilityType.PULL, AbilityTrigger.UPDATE);
        b.setChance(1.5);
        assertThatThrownBy(b::validate).hasMessageContaining("chance");

        NpcAbility c = valid(AbilityType.PULL, AbilityTrigger.UPDATE);
        c.setChance(Double.NaN);
        assertThatThrownBy(c::validate).hasMessageContaining("chance");
    }

    @Test
    void nullConditionRejected() {
        NpcAbility a = valid(AbilityType.PUSH, AbilityTrigger.UPDATE);
        a.getConditions().add(null);
        assertThatThrownBy(a::validate).hasMessageContaining("null condition");
    }

    @Test
    void ruleConditionsAttach() {
        NpcAbility a = valid(AbilityType.SNARE, AbilityTrigger.UPDATE);
        a.getConditions().add(new ActorIsPlayerCondition());
        assertThatCode(a::validate).doesNotThrowAnyException();
        assertThat(a.getConditions()).hasSize(1);
    }

    @Test
    void nestedNullConditionRejected() {
        NpcAbility a = valid(AbilityType.SNARE, AbilityTrigger.UPDATE);
        var composite = new com.storynpcs.domain.rule.condition.CompositeCondition();
        composite.getConditions().add(null);
        a.getConditions().add(composite);
        assertThatThrownBy(a::validate).hasMessageContaining("null condition");
    }

    @Test
    void deepConditionNestingRejected() {
        NpcAbility a = valid(AbilityType.SNARE, AbilityTrigger.UPDATE);
        var innermost = new com.storynpcs.domain.rule.condition.CompositeCondition();
        innermost.getConditions().add(new ActorIsPlayerCondition());
        var current = innermost;
        for (int i = 0; i < 10; i++) {
            var wrap = new com.storynpcs.domain.rule.condition.CompositeCondition();
            wrap.getConditions().add(current);
            current = wrap;
        }
        a.getConditions().add(current);
        assertThatThrownBy(a::validate).hasMessageContaining("nested deeper");
    }

    @Test
    void defaultsAreSane() {
        NpcAbility a = valid(AbilityType.PULL, AbilityTrigger.UPDATE);
        assertThat(a.getChance()).isEqualTo(1.0);
        assertThat(a.getCooldownTicks()).isEqualTo(0);
        assertThat(a.effectiveRange()).isEqualTo(12.0);
        assertThat(valid(AbilityType.TELEPORT, AbilityTrigger.UPDATE).effectiveRange()).isEqualTo(10.0);
        assertThat(valid(AbilityType.BLOCK, AbilityTrigger.DAMAGED).effectiveDamageMultiplier()).isEqualTo(0.5);
    }
}
