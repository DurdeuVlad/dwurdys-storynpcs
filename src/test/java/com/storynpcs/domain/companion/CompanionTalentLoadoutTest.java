package com.storynpcs.domain.companion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanionTalentLoadoutTest {

    @Test
    void recruitStageHasOneTalentSlot() {
        CompanionTalentLoadout loadout = new CompanionTalentLoadout(CompanionStage.RECRUIT);
        assertThat(loadout.remainingSlots()).isEqualTo(1);

        loadout.equip(new CompanionTalent("t1", CompanionEffectType.DAMAGE_BONUS, 10));
        assertThat(loadout.remainingSlots()).isZero();

        assertThatThrownBy(() -> loadout.equip(new CompanionTalent("t2", CompanionEffectType.DEFENSE_BONUS, 5)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotEquipTheSameTalentIdTwice() {
        CompanionTalentLoadout loadout = new CompanionTalentLoadout(CompanionStage.ELITE);
        loadout.equip(new CompanionTalent("duplicate", CompanionEffectType.DAMAGE_BONUS, 10));
        assertThatThrownBy(() -> loadout.equip(new CompanionTalent("duplicate", CompanionEffectType.DEFENSE_BONUS, 5)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unequipRemovesByIdAndReportsWhetherAnythingWasRemoved() {
        CompanionTalentLoadout loadout = new CompanionTalentLoadout(CompanionStage.TRAINED);
        loadout.equip(new CompanionTalent("t1", CompanionEffectType.DAMAGE_BONUS, 10));

        assertThat(loadout.unequip("t1")).isTrue();
        assertThat(loadout.getTalents()).isEmpty();
        assertThat(loadout.unequip("not_present")).isFalse();
    }

    @Test
    void advanceStageIncreasesSlotCapacityAndKeepsExistingTalents() {
        CompanionTalentLoadout loadout = new CompanionTalentLoadout(CompanionStage.RECRUIT);
        loadout.equip(new CompanionTalent("t1", CompanionEffectType.DAMAGE_BONUS, 10));

        loadout.advanceStage();
        assertThat(loadout.getStage()).isEqualTo(CompanionStage.TRAINED);
        assertThat(loadout.getTalents()).hasSize(1);
        assertThat(loadout.remainingSlots()).isEqualTo(1);
    }

    @Test
    void eliteStageIsTerminalAndDoesNotAdvanceFurther() {
        CompanionTalentLoadout loadout = new CompanionTalentLoadout(CompanionStage.ELITE);
        loadout.advanceStage();
        assertThat(loadout.getStage()).isEqualTo(CompanionStage.ELITE);
    }

    @Test
    void eliteStageHasFourTalentSlots() {
        assertThat(CompanionStage.ELITE.getMaxTalentSlots()).isEqualTo(4);
    }
}
