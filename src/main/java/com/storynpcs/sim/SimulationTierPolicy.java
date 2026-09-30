package com.storynpcs.sim;

/**
 * Deterministic tier assignment: inclusive distance bands produce ACTIVE/NEARBY/
 * DISTANT/DORMANT/UNLOADED. Combat engagement clamps the result to at most
 * DISTANT — an engaged actor can never degrade into dormant bookkeeping.
 */
public record SimulationTierPolicy(
        double activeRangeBlocks,
        double nearbyRangeBlocks,
        double distantRangeBlocks,
        double dormantRangeBlocks) {

    public SimulationTierPolicy {
        if (activeRangeBlocks <= 0 || nearbyRangeBlocks < activeRangeBlocks
                || distantRangeBlocks < nearbyRangeBlocks || dormantRangeBlocks < distantRangeBlocks) {
            throw new IllegalArgumentException(
                    "tier ranges must be positive and non-decreasing: active < nearby < distant < dormant");
        }
    }

    /** Default bands matching the certification workload (64-block active, 256-block dormant). */
    public static SimulationTierPolicy defaults() {
        return new SimulationTierPolicy(64.0, 128.0, 256.0, 512.0);
    }

    public SimulationTier tierFor(double distanceBlocks) {
        return tierFor(distanceBlocks, false);
    }

    public SimulationTier tierFor(double distanceBlocks, boolean inCombat) {
        SimulationTier tier;
        if (!Double.isFinite(distanceBlocks) || distanceBlocks < 0) {
            tier = SimulationTier.UNLOADED;
        } else if (distanceBlocks <= activeRangeBlocks) {
            tier = SimulationTier.ACTIVE;
        } else if (distanceBlocks <= nearbyRangeBlocks) {
            tier = SimulationTier.NEARBY;
        } else if (distanceBlocks <= distantRangeBlocks) {
            tier = SimulationTier.DISTANT;
        } else if (distanceBlocks <= dormantRangeBlocks) {
            tier = SimulationTier.DORMANT;
        } else {
            tier = SimulationTier.UNLOADED;
        }
        if (inCombat && tier.ordinal() > SimulationTier.DISTANT.ordinal()) {
            return SimulationTier.DISTANT;
        }
        return tier;
    }
}
