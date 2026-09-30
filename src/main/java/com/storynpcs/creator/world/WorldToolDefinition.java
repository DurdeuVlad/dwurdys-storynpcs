package com.storynpcs.creator.world;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Typed world-tool definition (P8-3). Every tool family declares its
 * reversible and non-reversible operations explicitly — a failed world
 * mutation reports which legs were reversible and their rollback status.
 */
public class WorldToolDefinition {

    public enum Family { SCRIPTER, SCENE, SCRIPTED_BLOCK, SCRIPTED_DOOR, MAILBOX, REDSTONE, BANNER }

    /** An operation leg that can be undone (block replace with prior state, etc.). */
    public record ReversibleOp(String opId, String description) {}
    /** An operation leg that cannot be undone once applied. */
    public record IrreversibleOp(String opId, String description) {}

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private Family family;

    /** Dimension the tool may mutate — validated server-side. */
    @JsonProperty
    private NamespacedId dimensionId;

    /** Bounded mutation budget per activation. */
    @JsonProperty
    private int maxBlocksPerActivation = 16;

    @JsonProperty
    private List<ScriptedHookBinding> hooks = new java.util.ArrayList<>();

    public WorldToolDefinition() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public Family getFamily() { return family; }
    public void setFamily(Family family) { this.family = family; }

    public NamespacedId getDimensionId() { return dimensionId; }
    public void setDimensionId(NamespacedId dimensionId) { this.dimensionId = dimensionId; }

    public int getMaxBlocksPerActivation() { return maxBlocksPerActivation; }
    public void setMaxBlocksPerActivation(int maxBlocksPerActivation) {
        if (maxBlocksPerActivation < 1 || maxBlocksPerActivation > 1024) {
            throw new IllegalArgumentException("maxBlocksPerActivation must be in [1,1024]");
        }
        this.maxBlocksPerActivation = maxBlocksPerActivation;
    }

    public List<ScriptedHookBinding> getHooks() { return List.copyOf(hooks); }
    public void setHooks(List<ScriptedHookBinding> hooks) {
        this.hooks = hooks == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(hooks);
    }
}
