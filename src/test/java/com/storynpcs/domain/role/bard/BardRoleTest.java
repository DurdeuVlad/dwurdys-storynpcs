package com.storynpcs.domain.role.bard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BardRoleTest {

    @Test
    void defaultsAreSensible() {
        BardRole role = new BardRole();
        assertThat(role.getBuffType()).isEqualTo(BardBuffType.REGENERATION);
        assertThat(role.getEffectRadius()).isGreaterThan(0);
        assertThat(role.getEffectDurationMillis()).isGreaterThan(0);
        assertThat(role.getCooldownMillis()).isGreaterThan(0);
        assertThat(role.getBuffAmplifier()).isEqualTo(0);
    }

    @Test
    void nullBuffTypeFallsBackToRegeneration() {
        BardRole role = new BardRole();
        role.setBuffType(null);
        assertThat(role.getBuffType()).isEqualTo(BardBuffType.REGENERATION);
    }

    @Test
    void constructorAssignsAllFields() {
        BardRole role = new BardRole(BardBuffType.SPEED, 15.0, 30_000L, 60_000L, 2);
        assertThat(role.getBuffType()).isEqualTo(BardBuffType.SPEED);
        assertThat(role.getEffectRadius()).isEqualTo(15.0);
        assertThat(role.getEffectDurationMillis()).isEqualTo(30_000L);
        assertThat(role.getCooldownMillis()).isEqualTo(60_000L);
        assertThat(role.getBuffAmplifier()).isEqualTo(2);
    }

    @Test
    void rangeCheckRespectsEffectRadius() {
        BardRole role = new BardRole(BardBuffType.RESISTANCE, 10.0, 15_000L, 30_000L, 0);
        assertThat(role.isWithinRange(0.0)).isTrue();
        assertThat(role.isWithinRange(10.0)).isTrue();
        assertThat(role.isWithinRange(10.01)).isFalse();
        assertThat(role.isWithinRange(-1.0)).isFalse();
    }

    @Test
    void allFourBuffTypesAreDeclared() {
        assertThat(BardBuffType.values()).hasSize(4);
    }
}
