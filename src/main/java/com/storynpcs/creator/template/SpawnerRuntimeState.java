package com.storynpcs.creator.template;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Durable per-spawner runtime state (P8-1): the owned-entity ledger, last
 * spawn tick, death accounting, and the instantiated spawn definition id.
 * Persisted per change so a restart enforces quotas against the actors the
 * spawner actually owns — without it a spawner would see zero owned entities
 * after restart and over-spawn while its previous actors still exist.
 */
public class SpawnerRuntimeState {

    @JsonProperty(required = true)
    private String spawnerId;

    /** Entity UUIDs (string form) this spawner currently owns. Bounded by quota. */
    @JsonProperty
    private Set<String> ownedUuids = new LinkedHashSet<>();

    @JsonProperty
    private long lastSpawnTick;

    /**
     * Actors confirmed dead under {@code respawnOnDeath=false} — each death
     * permanently consumes one quota slot.
     */
    @JsonProperty
    private int deaths;

    /** Registry id of the NPC definition this spawner instantiated (lazily). */
    @JsonProperty
    private String instantiatedDefinitionId;

    /**
     * UUID → game-time when an owned entity last failed lookup. Entries older
     * than the grace bound are dropped — a dead-in-unloaded-chunk actor cannot
     * pin its quota slot forever.
     */
    @JsonProperty
    private Map<String, Long> missingSince = new LinkedHashMap<>();

    public SpawnerRuntimeState() {}

    public SpawnerRuntimeState(String spawnerId) {
        this.spawnerId = spawnerId;
    }

    public String getSpawnerId() { return spawnerId; }
    public void setSpawnerId(String spawnerId) { this.spawnerId = spawnerId; }

    public Set<String> getOwnedUuids() { return ownedUuids; }
    public void setOwnedUuids(Set<String> ownedUuids) {
        this.ownedUuids = ownedUuids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ownedUuids);
    }

    public long getLastSpawnTick() { return lastSpawnTick; }
    public void setLastSpawnTick(long lastSpawnTick) { this.lastSpawnTick = lastSpawnTick; }

    public int getDeaths() { return deaths; }
    public void setDeaths(int deaths) { this.deaths = deaths; }

    public String getInstantiatedDefinitionId() { return instantiatedDefinitionId; }
    public void setInstantiatedDefinitionId(String id) { this.instantiatedDefinitionId = id; }

    public Map<String, Long> getMissingSince() { return missingSince; }
    public void setMissingSince(Map<String, Long> missingSince) {
        this.missingSince = missingSince == null ? new LinkedHashMap<>() : new LinkedHashMap<>(missingSince);
    }
}
