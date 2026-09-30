package com.storynpcs.domain.role.bard;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authored, static bard-role configuration (issue #71 — service and social roles).
 * Mirrors {@code TraderRole}/{@code BankerRole}/{@code HealerRole}: content, not
 * mutable runtime state — no "last performed" timestamp is stored here, for the same
 * reason documented on {@link com.storynpcs.domain.role.healer.HealerRole}.
 */
public class BardRole {

    @JsonProperty
    private BardBuffType buffType = BardBuffType.REGENERATION;

    @JsonProperty
    private double effectRadius = 10.0;

    @JsonProperty
    private long effectDurationMillis = 15_000L;

    @JsonProperty
    private long cooldownMillis = 30_000L;

    @JsonProperty
    private int buffAmplifier = 0;

    public BardRole() {}

    public BardRole(BardBuffType buffType, double effectRadius, long effectDurationMillis, long cooldownMillis, int buffAmplifier) {
        this.buffType = buffType != null ? buffType : BardBuffType.REGENERATION;
        this.effectRadius = effectRadius;
        this.effectDurationMillis = effectDurationMillis;
        this.cooldownMillis = cooldownMillis;
        this.buffAmplifier = buffAmplifier;
    }

    public BardBuffType getBuffType() { return buffType; }
    public void setBuffType(BardBuffType buffType) {
        this.buffType = buffType != null ? buffType : BardBuffType.REGENERATION;
    }

    public double getEffectRadius() { return effectRadius; }
    public void setEffectRadius(double effectRadius) { this.effectRadius = effectRadius; }

    public long getEffectDurationMillis() { return effectDurationMillis; }
    public void setEffectDurationMillis(long effectDurationMillis) { this.effectDurationMillis = effectDurationMillis; }

    public long getCooldownMillis() { return cooldownMillis; }
    public void setCooldownMillis(long cooldownMillis) { this.cooldownMillis = cooldownMillis; }

    public int getBuffAmplifier() { return buffAmplifier; }
    public void setBuffAmplifier(int buffAmplifier) { this.buffAmplifier = buffAmplifier; }

    /** Whether {@code distance} (blocks) from the performer is within {@link #getEffectRadius()}. */
    public boolean isWithinRange(double distance) {
        return distance >= 0 && distance <= effectRadius;
    }
}
