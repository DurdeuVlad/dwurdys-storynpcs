package com.storynpcs.domain.role.healer;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authored, static healer-role configuration (issue #71 — service and social roles).
 * Mirrors {@code TraderRole}/{@code BankerRole}: this is content, not mutable runtime
 * state. In particular it does NOT store a "last cast" timestamp — per the
 * architectural lesson from #73's {@code JobAssignment}, mutable per-cast runtime
 * state must live on the entity instance, not on this shared, read-only-at-runtime
 * definition. Runtime cast eligibility is computed by
 * {@link com.storynpcs.domain.role.RoleCooldownPolicy#canActivate} against whatever
 * per-instance timestamp store a future entity-instance integration provides.
 */
public class HealerRole {

    @JsonProperty
    private double healAmount = 4.0;

    @JsonProperty
    private double effectRadius = 6.0;

    @JsonProperty
    private long cooldownMillis = 10_000L;

    @JsonProperty
    private HealTargetMode targetMode = HealTargetMode.ALLIES_ONLY;

    public HealerRole() {}

    public HealerRole(double healAmount, double effectRadius, long cooldownMillis, HealTargetMode targetMode) {
        this.healAmount = healAmount;
        this.effectRadius = effectRadius;
        this.cooldownMillis = cooldownMillis;
        this.targetMode = targetMode != null ? targetMode : HealTargetMode.ALLIES_ONLY;
    }

    public double getHealAmount() { return healAmount; }
    public void setHealAmount(double healAmount) { this.healAmount = healAmount; }

    public double getEffectRadius() { return effectRadius; }
    public void setEffectRadius(double effectRadius) { this.effectRadius = effectRadius; }

    public long getCooldownMillis() { return cooldownMillis; }
    public void setCooldownMillis(long cooldownMillis) { this.cooldownMillis = cooldownMillis; }

    public HealTargetMode getTargetMode() { return targetMode; }
    public void setTargetMode(HealTargetMode targetMode) {
        this.targetMode = targetMode != null ? targetMode : HealTargetMode.ALLIES_ONLY;
    }

    /**
     * Whether a target of the given nature is a legal heal target under
     * {@link #getTargetMode()}. {@code isSelf} and {@code isAlly} are mutually
     * exclusive from the caster's perspective (the caster is never its own ally).
     */
    public boolean isValidTarget(boolean isSelf, boolean isAlly) {
        return switch (targetMode) {
            case SELF_ONLY -> isSelf;
            case ALLIES_ONLY -> isSelf || isAlly;
            case ANY -> true;
        };
    }

    /** Whether {@code distance} (blocks) from the caster is within {@link #getEffectRadius()}. */
    public boolean isWithinRange(double distance) {
        return distance >= 0 && distance <= effectRadius;
    }
}
