package com.storynpcs.domain.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The set of talents a companion currently has equipped, bounded by its
 * {@link CompanionStage}'s talent-slot cap (issue #74). Equipping beyond the
 * stage's cap, or the same talent ID twice, is rejected rather than silently
 * truncated or overwritten.
 */
public final class CompanionTalentLoadout {

    private final List<CompanionTalent> talents = new ArrayList<>();
    private CompanionStage stage;

    public CompanionTalentLoadout(CompanionStage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    public CompanionStage getStage() { return stage; }

    public List<CompanionTalent> getTalents() {
        return Collections.unmodifiableList(talents);
    }

    public void equip(CompanionTalent talent) {
        Objects.requireNonNull(talent, "talent");
        if (talents.size() >= stage.getMaxTalentSlots()) {
            throw new IllegalStateException(
                    "Cannot equip talent '" + talent.getId() + "': " + stage
                            + " has " + stage.getMaxTalentSlots() + " talent slot(s), all in use");
        }
        for (CompanionTalent existing : talents) {
            if (existing.getId().equals(talent.getId())) {
                throw new IllegalStateException("Talent '" + talent.getId() + "' is already equipped");
            }
        }
        talents.add(talent);
    }

    public boolean unequip(String talentId) {
        return talents.removeIf(t -> t.getId().equals(talentId));
    }

    /**
     * Advances to the next stage. Existing talents remain equipped (a stage
     * advance never demotes/removes talents), even if the new stage's cap is
     * somehow lower than the current equipped count — that scenario cannot
     * happen with the current monotonically-increasing stage caps, but this
     * method does not assume it never will.
     */
    public void advanceStage() {
        this.stage = stage.next();
    }

    public int remainingSlots() {
        return Math.max(0, stage.getMaxTalentSlots() - talents.size());
    }
}
