package com.storynpcs.domain.companion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanionTalentTest {

    @Test
    void magnitudeMustBeWithinBounds() {
        assertThatThrownBy(() -> new CompanionTalent("t1", CompanionEffectType.DAMAGE_BONUS, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompanionTalent("t1", CompanionEffectType.DAMAGE_BONUS, 26))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundaryMagnitudesAreAccepted() {
        CompanionTalent min = new CompanionTalent("t1", CompanionEffectType.DAMAGE_BONUS, CompanionTalent.MIN_MAGNITUDE);
        CompanionTalent max = new CompanionTalent("t2", CompanionEffectType.DAMAGE_BONUS, CompanionTalent.MAX_MAGNITUDE);
        assertThat(min.getMagnitude()).isEqualTo(1);
        assertThat(max.getMagnitude()).isEqualTo(25);
    }

    @Test
    void blankIdIsRejected() {
        assertThatThrownBy(() -> new CompanionTalent("", CompanionEffectType.DAMAGE_BONUS, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompanionTalent(null, CompanionEffectType.DAMAGE_BONUS, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldsAreExposed() {
        CompanionTalent talent = new CompanionTalent("swift_strikes", CompanionEffectType.MOVEMENT_SPEED_BONUS, 15);
        assertThat(talent.getId()).isEqualTo("swift_strikes");
        assertThat(talent.getEffectType()).isEqualTo(CompanionEffectType.MOVEMENT_SPEED_BONUS);
        assertThat(talent.getMagnitude()).isEqualTo(15);
    }
}
