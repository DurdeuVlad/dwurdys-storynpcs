package com.storynpcs.sim;

/**
 * Simulation tiers for large NPC populations. Tier assignment is a pure function
 * of distance and combat state — no randomness, no load-order dependence — and
 * active combat can never degrade below NEARBY.
 */
public enum SimulationTier {
    ACTIVE,
    NEARBY,
    DISTANT,
    DORMANT,
    UNLOADED
}
