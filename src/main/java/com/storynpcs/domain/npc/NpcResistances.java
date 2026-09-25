package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The target's four damage-resistance channels (issue #59 — P3-2 stats/combat).
 * Each channel is a multiplier clamped to {@code [0, 2]}: 0 = fully immune to
 * that damage type, 1 = normal damage, 2 = double damage. Clamping (rather than
 * rejecting) matches the acceptance criterion's own wording: "the target's four
 * resistance channels ... clamp to [0, 2]."
 */
public class NpcResistances {

    public static final double MIN = 0.0;
    public static final double MAX = 2.0;

    @JsonProperty
    private double knockback = 1.0;

    @JsonProperty
    private double arrow = 1.0;

    @JsonProperty
    private double melee = 1.0;

    @JsonProperty
    private double explosion = 1.0;

    public NpcResistances() {}

    public double getKnockback() { return knockback; }
    public void setKnockback(double knockback) { this.knockback = clamp(knockback); }

    public double getArrow() { return arrow; }
    public void setArrow(double arrow) { this.arrow = clamp(arrow); }

    public double getMelee() { return melee; }
    public void setMelee(double melee) { this.melee = clamp(melee); }

    public double getExplosion() { return explosion; }
    public void setExplosion(double explosion) { this.explosion = clamp(explosion); }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 1.0;
        return Math.max(MIN, Math.min(MAX, value));
    }
}
