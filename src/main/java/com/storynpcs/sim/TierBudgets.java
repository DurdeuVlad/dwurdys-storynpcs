package com.storynpcs.sim;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-tier tick budgets. A period of {@code -1} disables the capability entirely
 * at that tier; positive values are tick periods ("run capability every N ticks").
 * Conservative defaults keep nearby combat identical to today.
 */
public record TierBudgets(Map<SimulationTier, TierBudgets.Budget> budgets) {

    public record Budget(
            int sensingTicks,
            int pathingTicks,
            int animationTicks,
            int combatEvalTicks,
            int persistenceTicks) {

        public Budget {
            for (int p : new int[]{sensingTicks, pathingTicks, animationTicks, combatEvalTicks, persistenceTicks}) {
                if (p == 0 || p < -1) {
                    throw new IllegalArgumentException("budget periods must be -1 (disabled) or >= 1");
                }
            }
        }
    }

    public TierBudgets {
        budgets = Map.copyOf(budgets);
        for (SimulationTier tier : SimulationTier.values()) {
            if (!budgets.containsKey(tier)) {
                throw new IllegalArgumentException("missing budget for tier " + tier);
            }
        }
    }

    public Budget forTier(SimulationTier tier) {
        return budgets.get(tier);
    }

    /** Conservative defaults: ACTIVE/Nearby near-real-time, DORMANT persistence-only. */
    public static TierBudgets defaults() {
        EnumMap<SimulationTier, Budget> map = new EnumMap<>(SimulationTier.class);
        map.put(SimulationTier.ACTIVE, new Budget(1, 1, 1, 1, 60));
        map.put(SimulationTier.NEARBY, new Budget(2, 4, 2, 2, 120));
        map.put(SimulationTier.DISTANT, new Budget(10, 20, -1, 20, 600));
        map.put(SimulationTier.DORMANT, new Budget(-1, -1, -1, -1, 1_200));
        map.put(SimulationTier.UNLOADED, new Budget(-1, -1, -1, -1, -1));
        return new TierBudgets(map);
    }
}
