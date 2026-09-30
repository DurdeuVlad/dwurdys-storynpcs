package com.storynpcs.domain.role.social;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Healer role: periodic healing of validated targets within range. */
public class HealerRole {

    public enum TargetPolicy { PLAYERS_ONLY, ALLIES, ANY_LIVING }

    @JsonProperty
    private float healAmount = 4.0f;

    @JsonProperty
    private int cooldownTicks = 100;

    @JsonProperty
    private double rangeBlocks = 8.0;

    @JsonProperty
    private TargetPolicy targetPolicy = TargetPolicy.PLAYERS_ONLY;

    /** Tick period between target scans — the role's observable budget. */
    @JsonProperty
    private int scanPeriodTicks = 20;

    public HealerRole() {}

    public float getHealAmount() { return healAmount; }
    public void setHealAmount(float healAmount) {
        if (healAmount <= 0 || healAmount > 100) {
            throw new IllegalArgumentException("healAmount must be in (0,100]");
        }
        this.healAmount = healAmount;
    }

    public int getCooldownTicks() { return cooldownTicks; }
    public void setCooldownTicks(int cooldownTicks) {
        if (cooldownTicks < 0) {
            throw new IllegalArgumentException("cooldownTicks must be >= 0");
        }
        this.cooldownTicks = cooldownTicks;
    }

    public double getRangeBlocks() { return rangeBlocks; }
    public void setRangeBlocks(double rangeBlocks) {
        if (rangeBlocks <= 0 || rangeBlocks > 64) {
            throw new IllegalArgumentException("rangeBlocks must be in (0,64]");
        }
        this.rangeBlocks = rangeBlocks;
    }

    public TargetPolicy getTargetPolicy() { return targetPolicy; }
    public void setTargetPolicy(TargetPolicy targetPolicy) {
        this.targetPolicy = targetPolicy == null ? TargetPolicy.PLAYERS_ONLY : targetPolicy;
    }

    public int getScanPeriodTicks() { return scanPeriodTicks; }
    public void setScanPeriodTicks(int scanPeriodTicks) {
        if (scanPeriodTicks < 1 || scanPeriodTicks > 1_200) {
            throw new IllegalArgumentException("scanPeriodTicks must be in [1,1200]");
        }
        this.scanPeriodTicks = scanPeriodTicks;
    }
}
