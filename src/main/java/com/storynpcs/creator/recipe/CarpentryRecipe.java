package com.storynpcs.creator.recipe;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * A carpentry-bench recipe (P8-4): shaped 3x3 grid of namespaced item ids plus
 * an output stack. Validation diagnostics point to the offending slot.
 */
public class CarpentryRecipe {

    public static final int GRID = 9; // 3x3

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private NamespacedId groupId;

    /** Exactly 9 entries; null/empty = empty slot. */
    @JsonProperty
    private List<String> grid = new java.util.ArrayList<>(java.util.Collections.nCopies(GRID, ""));

    @JsonProperty(required = true)
    private String outputItemId;

    @JsonProperty
    private int outputCount = 1;

    /** Whether the recipe ignores grid position (shapeless). */
    @JsonProperty
    private boolean shapeless = false;

    public CarpentryRecipe() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public NamespacedId getGroupId() { return groupId; }
    public void setGroupId(NamespacedId groupId) { this.groupId = groupId; }

    public List<String> getGrid() { return List.copyOf(grid); }
    public void setGrid(List<String> grid) {
        if (grid != null && grid.size() != GRID) {
            throw new IllegalArgumentException("grid must have exactly " + GRID + " slots");
        }
        this.grid = grid == null ? new java.util.ArrayList<>(java.util.Collections.nCopies(GRID, ""))
                : new java.util.ArrayList<>(grid);
    }

    public String getOutputItemId() { return outputItemId; }
    public void setOutputItemId(String outputItemId) { this.outputItemId = outputItemId; }

    public int getOutputCount() { return outputCount; }
    public void setOutputCount(int outputCount) {
        if (outputCount < 1 || outputCount > 64) {
            throw new IllegalArgumentException("outputCount must be in [1,64]");
        }
        this.outputCount = outputCount;
    }

    public boolean isShapeless() { return shapeless; }
    public void setShapeless(boolean shapeless) { this.shapeless = shapeless; }

    /** Load-time diagnostics — slot-accurate. */
    public com.storynpcs.domain.common.ValidationResult validate() {
        var result = new com.storynpcs.domain.common.ValidationResult();
        if (grid.size() != GRID) {
            result.addError("RECIPE_GRID_SIZE", "grid must have exactly " + GRID + " slots, has " + grid.size());
        }
        for (int i = 0; i < grid.size(); i++) {
            String slot = grid.get(i);
            if (slot != null && !slot.isBlank() && !slot.contains(":")) {
                result.addError("RECIPE_BAD_SLOT", "slot " + i + " is not a namespaced item id: '" + slot + "'");
            }
        }
        if (outputItemId == null || outputItemId.isBlank() || !outputItemId.contains(":")) {
            result.addError("RECIPE_BAD_OUTPUT", "output must be a namespaced item id");
        }
        if (grid.stream().allMatch(s -> s == null || s.isBlank())) {
            result.addError("RECIPE_EMPTY", "recipe has no inputs");
        }
        return result;
    }
}
