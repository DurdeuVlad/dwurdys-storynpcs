package com.storynpcs.creator.template;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Deterministic spawner rule (P8-1): quota, interval, placement bounds, and
 * unload cleanup are data-driven — no spawner may exceed its quota or spawn
 * outside its placement box.
 */
public class SpawnerRule {

    public static final int SCHEMA_VERSION = 1;

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty(required = true)
    private NamespacedId templateId;

    /** Max living spawned actors owned by this spawner. */
    @JsonProperty
    private int quota = 4;

    @JsonProperty
    private int spawnIntervalTicks = 200;

    /** Placement box around the spawner's anchor. */
    @JsonProperty
    private double placementRadiusBlocks = 8.0;

    /** Spawned actors are despawned when the spawner's chunk unloads. */
    @JsonProperty
    private boolean cleanupOnChunkUnload = true;

    /** Whether spawned actors respawn after dying. */
    @JsonProperty
    private boolean respawnOnDeath = true;

    /**
     * World anchor: dimension id (e.g. {@code minecraft:overworld}) plus the
     * anchor block position. A rule without an anchor is an inert preset —
     * spawner evaluation skips it. All-or-none: partial anchors fail load.
     */
    @JsonProperty
    private String dimension;

    @JsonProperty
    private Integer anchorX;

    @JsonProperty
    private Integer anchorY;

    @JsonProperty
    private Integer anchorZ;

    /** Disabled spawners persist but never evaluate. */
    @JsonProperty
    private boolean enabled = true;

    public SpawnerRule() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public NamespacedId getTemplateId() { return templateId; }
    public void setTemplateId(NamespacedId templateId) { this.templateId = templateId; }

    public int getQuota() { return quota; }
    public void setQuota(int quota) {
        if (quota < 1 || quota > 64) {
            throw new IllegalArgumentException("quota must be in [1,64]");
        }
        this.quota = quota;
    }

    public int getSpawnIntervalTicks() { return spawnIntervalTicks; }
    public void setSpawnIntervalTicks(int spawnIntervalTicks) {
        if (spawnIntervalTicks < 20 || spawnIntervalTicks > 1_200_000) {
            throw new IllegalArgumentException("spawnIntervalTicks must be in [20,1200000]");
        }
        this.spawnIntervalTicks = spawnIntervalTicks;
    }

    public double getPlacementRadiusBlocks() { return placementRadiusBlocks; }
    public void setPlacementRadiusBlocks(double placementRadiusBlocks) {
        if (placementRadiusBlocks <= 0 || placementRadiusBlocks > 64) {
            throw new IllegalArgumentException("placementRadiusBlocks must be in (0,64]");
        }
        this.placementRadiusBlocks = placementRadiusBlocks;
    }

    public boolean isCleanupOnChunkUnload() { return cleanupOnChunkUnload; }
    public void setCleanupOnChunkUnload(boolean cleanupOnChunkUnload) { this.cleanupOnChunkUnload = cleanupOnChunkUnload; }

    public boolean isRespawnOnDeath() { return respawnOnDeath; }
    public void setRespawnOnDeath(boolean respawnOnDeath) { this.respawnOnDeath = respawnOnDeath; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public Integer getAnchorX() { return anchorX; }
    public void setAnchorX(Integer anchorX) { this.anchorX = anchorX; }

    public Integer getAnchorY() { return anchorY; }
    public void setAnchorY(Integer anchorY) { this.anchorY = anchorY; }

    public Integer getAnchorZ() { return anchorZ; }
    public void setAnchorZ(Integer anchorZ) { this.anchorZ = anchorZ; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** True when a full dimension + position anchor is authored. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAnchored() {
        return dimension != null && !dimension.isBlank()
                && anchorX != null && anchorY != null && anchorZ != null;
    }

    /** True when some — but not all — anchor fields are set; fails load. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasPartialAnchor() {
        int set = (dimension != null && !dimension.isBlank() ? 1 : 0)
                + (anchorX != null ? 1 : 0) + (anchorY != null ? 1 : 0)
                + (anchorZ != null ? 1 : 0);
        return set > 0 && set < 4;
    }

    /**
     * Deterministic spawn decision: quota not exceeded, interval elapsed.
     * Returns true when a spawn should occur at {@code nowTick}.
     */
    public boolean shouldSpawn(int liveSpawnedCount, long lastSpawnTick, long nowTick) {
        return liveSpawnedCount < quota && nowTick - lastSpawnTick >= spawnIntervalTicks;
    }
}
