package com.storynpcs.creator.spawn;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Data-driven natural spawn rule (P8-5): where and how often a template may
 * spawn without a placed spawner. All weights and counts are bounded and the
 * decision is deterministic given a seed.
 */
public class NaturalSpawnRule {

    @JsonProperty(required = true)
    private NamespacedId templateId;

    @JsonProperty
    private NamespacedId dimensionId;

    /** Optional biome filter (null = any). */
    @JsonProperty
    private NamespacedId biomeId;

    /** Relative spawn weight within the rule set. */
    @JsonProperty
    private int weight = 10;

    /** Max living spawned actors per dimension owned by this rule. */
    @JsonProperty
    private int maxPerDimension = 8;

    /** Minimum distance from players for a candidate position. */
    @JsonProperty
    private double minPlayerDistanceBlocks = 24.0;

    @JsonProperty
    private boolean enabled = true;

    public NaturalSpawnRule() {}

    public NamespacedId getTemplateId() { return templateId; }
    public void setTemplateId(NamespacedId templateId) { this.templateId = templateId; }

    public NamespacedId getDimensionId() { return dimensionId; }
    public void setDimensionId(NamespacedId dimensionId) { this.dimensionId = dimensionId; }

    public NamespacedId getBiomeId() { return biomeId; }
    public void setBiomeId(NamespacedId biomeId) { this.biomeId = biomeId; }

    public int getWeight() { return weight; }
    public void setWeight(int weight) {
        if (weight < 0 || weight > 1000) {
            throw new IllegalArgumentException("weight must be in [0,1000]");
        }
        this.weight = weight;
    }

    public int getMaxPerDimension() { return maxPerDimension; }
    public void setMaxPerDimension(int maxPerDimension) {
        if (maxPerDimension < 1 || maxPerDimension > 128) {
            throw new IllegalArgumentException("maxPerDimension must be in [1,128]");
        }
        this.maxPerDimension = maxPerDimension;
    }

    public double getMinPlayerDistanceBlocks() { return minPlayerDistanceBlocks; }
    public void setMinPlayerDistanceBlocks(double minPlayerDistanceBlocks) {
        if (minPlayerDistanceBlocks < 0 || minPlayerDistanceBlocks > 256) {
            throw new IllegalArgumentException("minPlayerDistanceBlocks must be in [0,256]");
        }
        this.minPlayerDistanceBlocks = minPlayerDistanceBlocks;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Deterministic candidacy: enabled, weight>0, under the dimension cap. */
    public boolean eligible(int liveInDimension) {
        return enabled && weight > 0 && liveInDimension < maxPerDimension;
    }
}
