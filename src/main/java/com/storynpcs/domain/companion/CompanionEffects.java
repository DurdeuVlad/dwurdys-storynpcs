package com.storynpcs.domain.companion;

import java.util.List;

/**
 * Bounded effect projection for a companion (P6-5): resolves an authored
 * {@link CompanionProfile}'s stage + talents into the concrete attribute
 * deltas the entity applies. Every knob is bounded upstream — stage
 * multipliers clamp to [0.1,10], talent ranks to ≤10, active talents to the
 * stage's slot cap — so no combination produces an unbounded stat change.
 * This is a pure domain function: headless fixtures exercise identical
 * semantics to the entity's live application.
 */
public final class CompanionEffects {

    private CompanionEffects() {}

    /**
     * The resolved effect set. {@code damageBonus}/{@code armorBonus} are
     * flat attribute points; {@code speedBonusFraction} is a fraction of base
     * movement speed (rank × {@link #SPEED_FRACTION_PER_RANK});
     * {@code carrySlotBonus} widens the companion's carried-item capacity —
     * the interactive inventory is wave-2 UI scope, so the value is exposed
     * through {@code effectiveCarryCapacity} rather than consumed here.
     */
    public record Projection(double stageMultiplier,
                             int damageBonus,
                             int armorBonus,
                             double speedBonusFraction,
                             int carrySlotBonus) {

        public static final Projection NONE = new Projection(1.0, 0, 0, 0.0, 0);
    }

    /** Each MOVEMENT_SPEED_BONUS rank adds this fraction of base speed — small, bounded, stackable. */
    public static final double SPEED_FRACTION_PER_RANK = 0.01;
    /** Each CARRY_CAPACITY_BONUS rank grants one extra carried-item slot. */
    public static final int CARRY_SLOTS_PER_RANK = 1;
    /** Companion carried-item capacity is never wider than the P3-4 drop-table container. */
    public static final int BASE_CARRY_CAPACITY = 4;
    public static final int MAX_CARRY_CAPACITY = com.storynpcs.domain.npc.NpcInventory.DROP_SLOTS;

    /**
     * Summarize the active effects for a profile at a companion age. Only the
     * first {@code talentSlots} talents <em>with an effect</em> contribute —
     * talents past the cap are inert, never silently stacked. A {@code null}
     * profile or stage yields no effects and multiplier 1.
     */
    public static Projection summarize(CompanionProfile profile, long ageTicks) {
        if (profile == null) {
            return Projection.NONE;
        }
        var stage = profile.activeStage(ageTicks);
        double multiplier = stage != null ? stage.getStatMultiplier() : 1.0;
        int slots = stage != null ? stage.getTalentSlots() : 0;
        int damage = 0, armor = 0, carry = 0;
        double speed = 0.0;
        int used = 0;
        List<CompanionProfile.Talent> talents = profile.getTalents();
        for (var talent : talents) {
            if (used >= slots) {
                break;
            }
            var effect = talent.getEffect();
            if (effect == null) {
                continue; // flavor talent — does not consume a slot
            }
            used++;
            int rank = talent.getRank();
            switch (effect) {
                case DAMAGE_BONUS -> damage += rank;
                case DEFENSE_BONUS -> armor += rank;
                case MOVEMENT_SPEED_BONUS -> speed += rank * SPEED_FRACTION_PER_RANK;
                case CARRY_CAPACITY_BONUS -> carry += rank * CARRY_SLOTS_PER_RANK;
            }
        }
        return new Projection(multiplier, damage, armor, speed, carry);
    }

    /** Effective carried-item capacity under a projection — bounded by the P3-4 container. */
    public static int effectiveCarryCapacity(Projection projection) {
        if (projection == null) {
            return BASE_CARRY_CAPACITY;
        }
        return Math.min(MAX_CARRY_CAPACITY, BASE_CARRY_CAPACITY + projection.carrySlotBonus());
    }
}
