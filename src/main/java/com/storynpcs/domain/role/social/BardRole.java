package com.storynpcs.domain.role.social;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/** Bard role: plays songs that apply bounded buff effects to nearby entities. */
public class BardRole {

    @JsonProperty
    private NamespacedId songId;

    /** Namespaced buff/effect identifier applied on each play cycle. */
    @JsonProperty
    private NamespacedId buffEffect;

    @JsonProperty
    private double effectRadiusBlocks = 12.0;

    @JsonProperty
    private int cooldownTicks = 600;

    @JsonProperty
    private int playDurationTicks = 200;

    public BardRole() {}

    public NamespacedId getSongId() { return songId; }
    public void setSongId(NamespacedId songId) { this.songId = songId; }

    public NamespacedId getBuffEffect() { return buffEffect; }
    public void setBuffEffect(NamespacedId buffEffect) { this.buffEffect = buffEffect; }

    public double getEffectRadiusBlocks() { return effectRadiusBlocks; }
    public void setEffectRadiusBlocks(double effectRadiusBlocks) {
        if (effectRadiusBlocks <= 0 || effectRadiusBlocks > 64) {
            throw new IllegalArgumentException("effectRadiusBlocks must be in (0,64]");
        }
        this.effectRadiusBlocks = effectRadiusBlocks;
    }

    public int getCooldownTicks() { return cooldownTicks; }
    public void setCooldownTicks(int cooldownTicks) {
        if (cooldownTicks < 0) {
            throw new IllegalArgumentException("cooldownTicks must be >= 0");
        }
        this.cooldownTicks = cooldownTicks;
    }

    public int getPlayDurationTicks() { return playDurationTicks; }
    public void setPlayDurationTicks(int playDurationTicks) {
        if (playDurationTicks < 0 || playDurationTicks > 6_000) {
            throw new IllegalArgumentException("playDurationTicks must be in [0,6000]");
        }
        this.playDurationTicks = playDurationTicks;
    }
}
