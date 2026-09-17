package com.storynpcs.domain.npc;

/**
 * Combat stance defining an NPC's aggression, defensive reactions, and rules of engagement.
 */
public enum TacticalStance {
    /**
     * Passive NPC: never attacks, ignores hostile actions or flees.
     */
    PASSIVE,

    /**
     * Retaliates only when personally attacked past the accidental strike tolerance threshold.
     */
    RETALIATE_ONLY,

    /**
     * Defends itself and actively protects party members / owner.
     */
    DEFENSIVE,

    /**
     * Law enforcement / town guard: patrols area, tolerates accidental hits,
     * but retaliates on repeated strikes and actively defends innocent players/citizens against assault.
     */
    GUARD,

    /**
     * Aggressive: attacks hostile factions and designated enemies on sight.
     */
    AGGRESSIVE;

    public static TacticalStance fromString(String name) {
        if (name == null) return GUARD;
        try {
            return TacticalStance.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GUARD;
        }
    }
}
