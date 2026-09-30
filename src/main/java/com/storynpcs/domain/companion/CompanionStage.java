package com.storynpcs.domain.companion;

/**
 * A companion's progression stage (issue #74 — P6-5 companion lifecycle,
 * wages, stages, talents, and inventory). Each stage bounds how many talents
 * a companion may have equipped at once — the acceptance criterion
 * "stages/talents apply bounded effects" is enforced by this slot cap plus
 * {@link CompanionTalent}'s own magnitude bound, not by any unbounded
 * additive stacking.
 */
public enum CompanionStage {
    RECRUIT(1),
    TRAINED(2),
    VETERAN(3),
    ELITE(4);

    private final int maxTalentSlots;

    CompanionStage(int maxTalentSlots) {
        this.maxTalentSlots = maxTalentSlots;
    }

    public int getMaxTalentSlots() {
        return maxTalentSlots;
    }

    /** The next stage, or this stage itself if already at {@link #ELITE} (terminal). */
    public CompanionStage next() {
        int nextOrdinal = ordinal() + 1;
        CompanionStage[] all = values();
        return nextOrdinal < all.length ? all[nextOrdinal] : this;
    }
}
