package com.storynpcs.creator.template;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Deterministic spawner rule (P8-1): quota, interval, placement bounds, and
 * unload cleanup are data-driven — no spawner may exceed its quota or spawn
 * outside its placement box.
 */
public class SpawnerRule {

    @JsonProperty(required = true)
    private NamespacedId id;

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

    public SpawnerRule() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

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

    /**
     * Deterministic spawn decision: quota not exceeded, interval elapsed.
     * Returns true when a spawn should occur at {@code nowTick}.
     */
    public boolean shouldSpawn(int liveSpawnedCount, long lastSpawnTick, long nowTick) {
        return liveSpawnedCount < quota && nowTick - lastSpawnTick >= spawnIntervalTicks;
    }
}
