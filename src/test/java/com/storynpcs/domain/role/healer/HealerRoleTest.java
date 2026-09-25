package com.storynpcs.domain.role.healer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealerRoleTest {

    @Test
    void defaultsAreSensible() {
        HealerRole role = new HealerRole();
        assertThat(role.getTargetMode()).isEqualTo(HealTargetMode.ALLIES_ONLY);
        assertThat(role.getHealAmount()).isGreaterThan(0);
        assertThat(role.getEffectRadius()).isGreaterThan(0);
        assertThat(role.getCooldownMillis()).isGreaterThan(0);
    }

    @Test
    void nullTargetModeFallsBackToAlliesOnly() {
        HealerRole role = new HealerRole();
        role.setTargetMode(null);
        assertThat(role.getTargetMode()).isEqualTo(HealTargetMode.ALLIES_ONLY);
    }

    @Test
    void selfOnlyRejectsAllies() {
        HealerRole role = new HealerRole(4.0, 6.0, 10_000L, HealTargetMode.SELF_ONLY);
        assertThat(role.isValidTarget(true, false)).isTrue();
        assertThat(role.isValidTarget(false, true)).isFalse();
        assertThat(role.isValidTarget(false, false)).isFalse();
    }

    @Test
    void alliesOnlyAcceptsSelfAndAlliesButNotStrangers() {
        HealerRole role = new HealerRole(4.0, 6.0, 10_000L, HealTargetMode.ALLIES_ONLY);
        assertThat(role.isValidTarget(true, false)).isTrue();
        assertThat(role.isValidTarget(false, true)).isTrue();
        assertThat(role.isValidTarget(false, false)).isFalse();
    }

    @Test
    void anyAcceptsEveryone() {
        HealerRole role = new HealerRole(4.0, 6.0, 10_000L, HealTargetMode.ANY);
        assertThat(role.isValidTarget(true, false)).isTrue();
        assertThat(role.isValidTarget(false, true)).isTrue();
        assertThat(role.isValidTarget(false, false)).isTrue();
    }

    @Test
    void rangeCheckRespectsEffectRadius() {
        HealerRole role = new HealerRole(4.0, 6.0, 10_000L, HealTargetMode.ANY);
        assertThat(role.isWithinRange(0.0)).isTrue();
        assertThat(role.isWithinRange(6.0)).isTrue();
        assertThat(role.isWithinRange(6.01)).isFalse();
        assertThat(role.isWithinRange(-1.0)).isFalse();
    }
}
