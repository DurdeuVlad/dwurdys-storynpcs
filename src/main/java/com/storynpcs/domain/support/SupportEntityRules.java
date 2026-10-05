package com.storynpcs.domain.support;

/**
 * Lifecycle rules for the transient support entities (issue #148): the chair
 * mount and the owner-bound fake-living entity. Kept pure so the entity ticks
 * stay thin and the rules are unit-testable without a Minecraft runtime.
 */
public final class SupportEntityRules {

    /**
     * Chair mounts exist only to hold a passenger. They are transient
     * (never saved) and discard the moment nothing rides them, after a short
     * spawn grace so the mounting call can land on the tick after creation.
     */
    public static final int CHAIR_SPAWN_GRACE_TICKS = 2;

    public static boolean chairShouldDiscard(int passengerCount, int ageTicks) {
        return passengerCount <= 0 && ageTicks > CHAIR_SPAWN_GRACE_TICKS;
    }

    /**
     * Fake-living entities are display puppets bound to an owner UUID (the
     * creator session). An owner-bound puppet whose owner is no longer
     * present — logged out, unloaded, removed — must not outlive it.
     * Ownerless puppets are allowed to persist (they behave like props).
     */
    public static boolean fakeLivingShouldDiscard(boolean ownerBound, boolean ownerPresent) {
        return ownerBound && !ownerPresent;
    }

    /** Owner presence is re-checked on this cadence, not every tick. */
    public static final int OWNER_RECHECK_PERIOD_TICKS = 20;

    private SupportEntityRules() {}
}
