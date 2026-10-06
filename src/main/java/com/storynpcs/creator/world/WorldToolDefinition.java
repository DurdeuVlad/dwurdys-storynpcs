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

    public static final int SCHEMA_VERSION = 1;
    /** Bounded hook bindings per tool — inert data, resolved by the P9-2 script host. */
    public static final int MAX_HOOKS = 16;

    public enum Family {
        SCRIPTER, SCENE, SCRIPTED_BLOCK, SCRIPTED_DOOR, MAILBOX, REDSTONE, BANNER;

        /** Authored family names are case/underscore-insensitive (`scripted_block`). */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Family fromString(String value) {
            if (value == null) {
                return null;
            }
            return Family.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** An operation leg that can be undone (block replace with prior state, etc.). */
    public record ReversibleOp(String opId, String description) {}
    /** An operation leg that cannot be undone once applied. */
    public record IrreversibleOp(String opId, String description) {}

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private Family family;

    /** Dimension the tool may mutate — validated server-side. */
    @JsonProperty
    private NamespacedId dimensionId;

    /** Block placed by block-family tools — required for block-placing families. */
    @JsonProperty
    private NamespacedId blockId;

    /** Bounded mutation budget per activation. */
    @JsonProperty
    private int maxBlocksPerActivation = 16;

    @JsonProperty
    private List<ScriptedHookBinding> hooks = new java.util.ArrayList<>();

    public WorldToolDefinition() {}

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public Family getFamily() { return family; }
    public void setFamily(Family family) { this.family = family; }

    public NamespacedId getDimensionId() { return dimensionId; }
    public void setDimensionId(NamespacedId dimensionId) { this.dimensionId = dimensionId; }

    public NamespacedId getBlockId() { return blockId; }
    public void setBlockId(NamespacedId blockId) { this.blockId = blockId; }

    /** Families that place a real block and therefore require {@code blockId}. */
    public static boolean placesBlock(Family family) {
        return family == Family.SCRIPTED_BLOCK || family == Family.SCRIPTED_DOOR
                || family == Family.MAILBOX || family == Family.REDSTONE
                || family == Family.BANNER;
    }

    public int getMaxBlocksPerActivation() { return maxBlocksPerActivation; }
    public void setMaxBlocksPerActivation(int maxBlocksPerActivation) {
        if (maxBlocksPerActivation < 1 || maxBlocksPerActivation > 1024) {
            throw new IllegalArgumentException("maxBlocksPerActivation must be in [1,1024]");
        }
        this.maxBlocksPerActivation = maxBlocksPerActivation;
    }

    public List<ScriptedHookBinding> getHooks() { return List.copyOf(hooks); }
    public void setHooks(List<ScriptedHookBinding> hooks) {
        var next = hooks == null ? new java.util.ArrayList<ScriptedHookBinding>()
                : new java.util.ArrayList<>(hooks);
        if (next.size() > MAX_HOOKS) {
            throw new IllegalArgumentException("hooks cannot exceed " + MAX_HOOKS);
        }
        this.hooks = next;
    }
}
