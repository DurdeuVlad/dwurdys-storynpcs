package com.storynpcs.domain.schematic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-planning contract for issue #149: rotation maps footprints correctly,
 * air and structure-void cells are placement no-ops, and block-entity offsets
 * rotate with the plan.
 */
class BuildPlanTest {

    private static Schematic schematic(int w, int h, int l, List<String> palette, int[] blocks) {
        return new Schematic("test", w, h, l, palette, blocks, List.of(), List.of());
    }

    private static int index(int w, int l, int x, int y, int z) {
        return (y * l + z) * w + x;
    }

    @Test
    void noRotationKeepsLocalOffsets() {
        int[] blocks = new int[4];
        blocks[index(2, 2, 0, 0, 0)] = 1;
        blocks[index(2, 2, 1, 0, 1)] = 1;
        BuildPlan plan = BuildPlan.of(schematic(2, 1, 2,
                List.of("minecraft:air", "minecraft:stone"), blocks), 0);
        assertThat(plan.placements()).containsExactly(
                new BuildPlan.Placement(0, 0, 0, "minecraft:stone"),
                new BuildPlan.Placement(1, 0, 1, "minecraft:stone"));
    }

    @Test
    void quarterTurnRotatesWithinFootprint() {
        // Block at (0,0) of a 2x3 footprint; 90° CW maps to (l-1-0, 0) = (2,0).
        int[] blocks = new int[6];
        blocks[index(2, 3, 0, 0, 0)] = 1;
        BuildPlan plan = BuildPlan.of(schematic(2, 1, 3,
                List.of("minecraft:air", "minecraft:stone"), blocks), 1);
        assertThat(plan.placements()).containsExactly(
                new BuildPlan.Placement(2, 0, 0, "minecraft:stone"));
        assertThat(BuildPlan.rotatedWidth(schematic(2, 1, 3,
                List.of("minecraft:air"), new int[6]), 1)).isEqualTo(3);
    }

    @Test
    void halfTurnMirrorsBothAxes() {
        int[] blocks = new int[4];
        blocks[index(2, 2, 0, 0, 0)] = 1;
        BuildPlan plan = BuildPlan.of(schematic(2, 1, 2,
                List.of("minecraft:air", "minecraft:stone"), blocks), 2);
        assertThat(plan.placements()).containsExactly(
                new BuildPlan.Placement(1, 0, 1, "minecraft:stone"));
    }

    @Test
    void airAndStructureVoidAreNoOps() {
        int[] blocks = new int[3];
        blocks[0] = 0; // air
        blocks[1] = 1; // structure_void
        blocks[2] = 2; // stone
        BuildPlan plan = BuildPlan.of(schematic(3, 1, 1,
                List.of("minecraft:air", "minecraft:structure_void", "minecraft:stone"), blocks), 0);
        assertThat(plan.placements()).containsExactly(
                new BuildPlan.Placement(2, 0, 0, "minecraft:stone"));
    }

    @Test
    void blockEntitiesRotateWithThePlan() {
        Schematic s = new Schematic("be", 2, 1, 2,
                List.of("minecraft:air", "minecraft:chest"), new int[]{1, 0, 0, 0},
                List.of(new Schematic.BlockEntityRecord(0, 0, 0, "minecraft:chest",
                        new net.minecraft.nbt.CompoundTag())),
                List.of());
        BuildPlan plan = BuildPlan.of(s, 1);
        assertThat(plan.blockEntities()).hasSize(1);
        assertThat(plan.blockEntities().get(0).x()).isEqualTo(1); // (0,0) -> (l-1,0) = (1,0)
        assertThat(plan.blockEntities().get(0).z()).isEqualTo(0);
    }
}
