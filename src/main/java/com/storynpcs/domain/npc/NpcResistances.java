package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The target's four damage-resistance channels (issue #59 — P3-2 stats/combat,
 * contract pinned by ADR-007 / issue #122). Each channel stores the target's
 * resistance value in {@code [0, 2]}: {@code 0.0} = vulnerable (damage is
 * doubled), {@code 1.0} = normal, {@code 2.0} = immune. Incoming damage is
 * scaled by {@code 2.0 - resistance} — see {@link #damageScaleKnockback()} and
 * {@link #scaleKnockback(float)}.
 *
 * <p><b>Authoring vs. read paths.</b> Setters clamp authored input to
 * {@code [0, 2]}, matching the acceptance criterion's bounds. Field-level
 * deserialization (YAML/JSON) bypasses the setters to preserve the target's
 * unclamped NBT-read passthrough: an out-of-range persisted value is carried
 * verbatim into damage math rather than silently normalized — values above
 * {@code 2.0} scale damage negative (a healing hit), matching the target
 * quirk.
 */
public class NpcResistances {

    public static final double MIN = 0.0;
    public static final double MAX = 2.0;
    private static final double NEUTRAL = 1.0;

    @JsonProperty
    private double knockback = NEUTRAL;

    @JsonProperty
    private double arrow = NEUTRAL;

    @JsonProperty
    private double melee = NEUTRAL;

    @JsonProperty
    private double explosion = NEUTRAL;

    public NpcResistances() {}

    public double getKnockback() { return knockback; }

    /** Authored setter — clamps to {@code [0, 2]}; deserialization bypasses it. */
    @JsonIgnore
    public void setKnockback(double knockback) { this.knockback = clamp(knockback); }

    /**
     * Target-faithful knockback scale: authored knockback strength multiplied
     * by {@code 2.0 - resistance} — {@code 2.0} immunity yields zero knockback,
     * {@code 0.0} vulnerability doubles it.
     */
    public float scaleKnockback(float strength) {
        return (float) (strength * (MAX - knockback));
    }

    public double getArrow() { return arrow; }

    /** Authored setter — clamps to {@code [0, 2]}; deserialization bypasses it. */
    @JsonIgnore
    public void setArrow(double arrow) { this.arrow = clamp(arrow); }

    public double getMelee() { return melee; }

    /** Authored setter — clamps to {@code [0, 2]}; deserialization bypasses it. */
    @JsonIgnore
    public void setMelee(double melee) { this.melee = clamp(melee); }

    public double getExplosion() { return explosion; }

    /** Authored setter — clamps to {@code [0, 2]}; deserialization bypasses it. */
    @JsonIgnore
    public void setExplosion(double explosion) { this.explosion = clamp(explosion); }

    /** ADR-007 damage scale: {@code 2.0 - resistance} for the arrow channel. */
    public double damageScaleArrow() { return MAX - arrow; }

    /** ADR-007 damage scale: {@code 2.0 - resistance} for the melee channel. */
    public double damageScaleMelee() { return MAX - melee; }

    /** ADR-007 damage scale: {@code 2.0 - resistance} for the explosion channel. */
    public double damageScaleExplosion() { return MAX - explosion; }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return NEUTRAL;
        return Math.max(MIN, Math.min(MAX, value));
    }
}
