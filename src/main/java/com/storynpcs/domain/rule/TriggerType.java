package com.storynpcs.domain.rule;

/**
 * Event triggers that activate behavioral rule evaluation.
 */
public enum TriggerType {
    ON_DAMAGED,
    ON_WITNESS_ASSAULT,
    ON_HEALTH_PERCENT_DROP,
    ON_INTERACT,
    ON_TARGET_LOST,
    ON_TICK,
    ON_YIELD;

    public static TriggerType fromString(String name) {
        if (name == null) return ON_DAMAGED;
        try {
            return TriggerType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ON_DAMAGED;
        }
    }
}
