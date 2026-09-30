package com.storynpcs.domain.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Typed per-job configuration (P6-4). Every job shares the common lifecycle
 * surface — enabled, tick budget, pause-on-unload — plus its own bounded
 * knobs. Invalid configs fail at load via bounded setters.
 */
public class JobConfig {

    @JsonProperty(required = true)
    private JobType type;

    @JsonProperty
    private boolean enabled = true;

    /** Ticks between job handler runs — the observable per-job budget. */
    @JsonProperty
    private int tickPeriod = 20;

    /** Pause the job when the actor is unloaded rather than resuming blindly. */
    @JsonProperty
    private boolean pauseOnUnload = true;

    // --- per-job bounded knobs (unused fields ignored per type) -------------

    /** SPAWNER: entities spawned per cycle. */
    @JsonProperty
    private int spawnCount = 1;
    /** SPAWNER: max living spawned entities owned by this job. */
    @JsonProperty
    private int maxSpawnedAlive = 8;
    /** SPAWNER: entity definition to spawn. */
    @JsonProperty
    private NamespacedId spawnDefinitionId;

    /** FARMER/BUILDER: work radius in blocks. */
    @JsonProperty
    private double workRadiusBlocks = 8.0;

    /** ITEM_GIVER: item handed out per interaction (required for ITEM_GIVER). */
    @JsonProperty
    private NamespacedId itemId;
    /** ITEM_GIVER: items per interaction. */
    @JsonProperty
    private int itemCount = 1;
    /** ITEM_GIVER: cooldown between interactions per player. */
    @JsonProperty
    private int interactionCooldownTicks = 6_000;

    /** GUARD/HEALER/BARD: effect radius or patrol radius in blocks. */
    @JsonProperty
    private double effectRadiusBlocks = 12.0;

    /** CHUNK_LOADER: chunks kept loaded (bounded — never unbounded world load). */
    @JsonProperty
    private int chunkRadius = 1;

    /** CONVERSATION/PUPPET: spoken line or animation cycle id. */
    @JsonProperty
    private NamespacedId scriptId;

    public JobConfig() {}

    public JobConfig(JobType type) {
        this.type = type;
    }

    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTickPeriod() { return tickPeriod; }
    public void setTickPeriod(int tickPeriod) {
        if (tickPeriod < 1 || tickPeriod > 72_000) {
            throw new IllegalArgumentException("tickPeriod must be in [1,72000]");
        }
        this.tickPeriod = tickPeriod;
    }

    public boolean isPauseOnUnload() { return pauseOnUnload; }
    public void setPauseOnUnload(boolean pauseOnUnload) { this.pauseOnUnload = pauseOnUnload; }

    public int getSpawnCount() { return spawnCount; }
    public void setSpawnCount(int spawnCount) {
        if (spawnCount < 1 || spawnCount > 32) {
            throw new IllegalArgumentException("spawnCount must be in [1,32]");
        }
        this.spawnCount = spawnCount;
    }

    public int getMaxSpawnedAlive() { return maxSpawnedAlive; }
    public void setMaxSpawnedAlive(int maxSpawnedAlive) {
        if (maxSpawnedAlive < 1 || maxSpawnedAlive > 64) {
            throw new IllegalArgumentException("maxSpawnedAlive must be in [1,64]");
        }
        this.maxSpawnedAlive = maxSpawnedAlive;
    }

    public NamespacedId getSpawnDefinitionId() { return spawnDefinitionId; }
    public void setSpawnDefinitionId(NamespacedId spawnDefinitionId) { this.spawnDefinitionId = spawnDefinitionId; }

    public double getWorkRadiusBlocks() { return workRadiusBlocks; }
    public void setWorkRadiusBlocks(double workRadiusBlocks) {
        if (workRadiusBlocks <= 0 || workRadiusBlocks > 64) {
            throw new IllegalArgumentException("workRadiusBlocks must be in (0,64]");
        }
        this.workRadiusBlocks = workRadiusBlocks;
    }

    public NamespacedId getItemId() { return itemId; }
    public void setItemId(NamespacedId itemId) { this.itemId = itemId; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) {
        if (itemCount < 1 || itemCount > 64) {
            throw new IllegalArgumentException("itemCount must be in [1,64]");
        }
        this.itemCount = itemCount;
    }

    public int getInteractionCooldownTicks() { return interactionCooldownTicks; }
    public void setInteractionCooldownTicks(int interactionCooldownTicks) {
        if (interactionCooldownTicks < 0) {
            throw new IllegalArgumentException("interactionCooldownTicks must be >= 0");
        }
        this.interactionCooldownTicks = interactionCooldownTicks;
    }

    public double getEffectRadiusBlocks() { return effectRadiusBlocks; }
    public void setEffectRadiusBlocks(double effectRadiusBlocks) {
        if (effectRadiusBlocks <= 0 || effectRadiusBlocks > 64) {
            throw new IllegalArgumentException("effectRadiusBlocks must be in (0,64]");
        }
        this.effectRadiusBlocks = effectRadiusBlocks;
    }

    public int getChunkRadius() { return chunkRadius; }
    public void setChunkRadius(int chunkRadius) {
        if (chunkRadius < 0 || chunkRadius > 4) {
            throw new IllegalArgumentException("chunkRadius must be in [0,4]");
        }
        this.chunkRadius = chunkRadius;
    }

    public NamespacedId getScriptId() { return scriptId; }
    public void setScriptId(NamespacedId scriptId) { this.scriptId = scriptId; }

    /** Per-type load-time validation beyond field bounds. */
    public void validate() {
        if (type == null) {
            throw new IllegalStateException("job type is required");
        }
        if (type == JobType.SPAWNER && spawnDefinitionId == null) {
            throw new IllegalStateException("SPAWNER job requires spawnDefinitionId");
        }
        if (type == JobType.ITEM_GIVER && itemId == null) {
            throw new IllegalStateException("ITEM_GIVER job requires itemId");
        }
        if (type == JobType.BUILDER || type == JobType.FOLLOWER) {
            throw new IllegalStateException(
                    "job type " + type + " has no implemented handler — definition rejected at load");
        }
    }
}
