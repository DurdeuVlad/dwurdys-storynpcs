package com.storynpcs.domain.ai;

import com.storynpcs.domain.npc.NpcAi;

/**
 * Pure per-tick combat-maneuver policy (P3-3). The melee goal owns navigation
 * and attack timing; this type answers, given authored tactics plus current
 * engagement state, WHAT the NPC should do this tick so behavior stays
 * deterministic and unit-testable.
 *
 * <p>State that spans ticks (a latched low-health retreat, an open
 * hit-and-run or stalk window) is supplied as explicit inputs — the caller
 * stores the actual windows in tick fields.
 */
public final class TacticalManeuver {

    private TacticalManeuver() {}

    /** What the melee goal should do with movement/attack this tick. */
    public enum Move {
        /** Default melee pursuit: path to the target and strike in reach. */
        ENGAGE,
        /** Approach the target but stop advancing once inside the tactical radius. */
        APPROACH_TO_RADIUS,
        /** Do not move; keep looking at the target. Never strikes. */
        HOLD,
        /** Path directly away from the target to the tactical radius. Never strikes. */
        RETREAT,
        /** Orbit the target inside the tactical radius; may strike in reach. */
        ORBIT
    }

    /**
     * @param behavior        authored tactical behavior (NONE → always ENGAGE)
     * @param healthFraction  npc health / max health in [0,1]
     * @param distSq          squared distance to the target
     * @param reachSq         squared melee reach — inside this the NPC strikes
     * @param tacticalRadius  authored radius for the behavior (blocks, >=1)
     * @param retreatLatched  caller's latched low-health retreat state
     *                        (RETREAT only)
     * @param hitRunActive    true while the post-strike backoff window is open
     *                        (HIT_AND_RUN only)
     * @param stalkActive     true while the caller's stalk window is open
     *                        (STALK only)
     */
    public static Decision decide(NpcAi.TacticalBehavior behavior,
                                  double healthFraction,
                                  double distSq,
                                  double reachSq,
                                  int tacticalRadius,
                                  boolean retreatLatched,
                                  boolean hitRunActive,
                                  boolean stalkActive) {
        if (behavior == null || behavior == NpcAi.TacticalBehavior.NONE) {
            return new Decision(Move.ENGAGE, true, retreatLatched);
        }
        double radiusSq = (double) tacticalRadius * tacticalRadius;
        switch (behavior) {
            case RETREAT -> {
                // Flee when badly hurt; hysteresis prevents oscillation at the
                // trigger boundary — once latched, keep retreating until the
                // NPC recovers above the resume threshold.
                if (healthFraction < 0.30 || (retreatLatched && healthFraction < 0.50)) {
                    return new Decision(Move.RETREAT, false, true);
                }
                return new Decision(Move.ENGAGE, true, false);
            }
            case HIT_AND_RUN -> {
                // Back off for the whole window after landing a strike.
                if (hitRunActive) {
                    return new Decision(Move.RETREAT, false, retreatLatched);
                }
                return new Decision(Move.ENGAGE, true, retreatLatched);
            }
            case STALK -> {
                if (!stalkActive) {
                    return new Decision(Move.ENGAGE, true, retreatLatched);
                }
                if (distSq > radiusSq) {
                    // Close on the target only until the stalk radius is reached.
                    return new Decision(Move.APPROACH_TO_RADIUS, false, retreatLatched);
                }
                if (distSq > reachSq) {
                    // Inside the stalk radius but out of reach: creep-hold and
                    // wait out the stalk window rather than committing early.
                    return new Decision(Move.HOLD, false, retreatLatched);
                }
                return new Decision(Move.ENGAGE, true, retreatLatched);
            }
            case AMBUSH -> {
                // An ambusher waits for prey inside its radius — it never
                // chases beyond it.
                if (distSq > radiusSq) {
                    return new Decision(Move.HOLD, false, retreatLatched);
                }
                return new Decision(Move.ENGAGE, true, retreatLatched);
            }
            case CIRCLE -> {
                if (distSq <= radiusSq && distSq > reachSq) {
                    return new Decision(Move.ORBIT, true, retreatLatched);
                }
                return new Decision(Move.ENGAGE, true, retreatLatched);
            }
            default -> {
                return new Decision(Move.ENGAGE, true, retreatLatched);
            }
        }
    }

    /**
     * @param move          the maneuver for this tick
     * @param attackAllowed whether the melee strike may fire when in reach
     * @param retreatLatched the retreat latch state to persist for next tick
     */
    public record Decision(Move move, boolean attackAllowed, boolean retreatLatched) {}

    /** Ticks a hit-and-run backoff window stays open after a landed strike. */
    public static int hitAndRunBackoffTicks(int tacticalRadius) {
        int radius = Math.max(1, tacticalRadius);
        return Math.min(128, Math.max(10, radius * 2));
    }

    /** Ticks a stalk window stays open before the NPC commits to full melee. */
    public static int stalkWindowTicks(int tacticalRadius) {
        return Math.min(400, Math.max(60, Math.max(1, tacticalRadius) * 10));
    }

    /** Ticks between orbit direction swaps for CIRCLE behavior. */
    public static int orbitSwapTicks() {
        return 40;
    }
}
