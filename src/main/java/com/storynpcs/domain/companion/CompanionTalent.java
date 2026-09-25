package com.storynpcs.domain.companion;

import java.util.Objects;

/**
 * A single bounded companion talent (issue #74). {@code magnitude} is a
 * percentage-style bonus clamped to {@code [MIN_MAGNITUDE, MAX_MAGNITUDE]} so
 * no individual talent can grant an unbounded effect.
 */
public final class CompanionTalent {

    public static final int MIN_MAGNITUDE = 1;
    public static final int MAX_MAGNITUDE = 25;

    private final String id;
    private final CompanionEffectType effectType;
    private final int magnitude;

    public CompanionTalent(String id, CompanionEffectType effectType, int magnitude) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        this.id = id;
        this.effectType = Objects.requireNonNull(effectType, "effectType");
        if (magnitude < MIN_MAGNITUDE || magnitude > MAX_MAGNITUDE) {
            throw new IllegalArgumentException(
                    "magnitude must be between " + MIN_MAGNITUDE + " and " + MAX_MAGNITUDE + " (was " + magnitude + ")");
        }
        this.magnitude = magnitude;
    }

    public String getId() { return id; }
    public CompanionEffectType getEffectType() { return effectType; }
    public int getMagnitude() { return magnitude; }
}
