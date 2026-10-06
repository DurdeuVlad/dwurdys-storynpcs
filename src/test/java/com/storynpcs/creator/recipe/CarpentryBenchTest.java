package com.storynpcs.creator.recipe;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carpentry-bench ingredient math (issue #150): the multiset requirements a
 * recipe demands and the pure inventory-satisfaction check. The live
 * inventory consume runs only after every requirement verifies — these tests
 * pin the pure side; the ServerPlayer path is a thin consume loop.
 */
class CarpentryBenchTest {

    private static CarpentryRecipe recipe(List<String> grid, String out, int count) {
        var r = new CarpentryRecipe();
        r.setId(NamespacedId.of("storynpcs:r"));
        r.setGroupId(NamespacedId.of("storynpcs:bench"));
        r.setGrid(grid);
        r.setOutputItemId(out);
        r.setOutputCount(count);
        return r;
    }

    @Test
    void requiredItemsCountsEachFilledSlot() {
        var r = recipe(List.of(
                "minecraft:oak_planks", "", "minecraft:oak_planks",
                "minecraft:iron_ingot", "minecraft:oak_planks", "",
                "", "", ""), "minecraft:shield", 1);
        assertThat(CarpentryBench.requiredItems(r))
                .containsEntry("minecraft:oak_planks", 3)
                .containsEntry("minecraft:iron_ingot", 1)
                .hasSize(2);
        assertThat(CarpentryBench.requiredItems(null)).isEmpty();
    }

    @Test
    void ingredientSummaryIsHumanReadableAndStable() {
        var r = recipe(List.of(
                "minecraft:stick", "", "",
                "", "minecraft:stick", "",
                "", "", ""), "minecraft:ladder", 2);
        assertThat(CarpentryBench.ingredientSummary(r))
                .containsExactly("minecraft:stick x2");
    }

    @Test
    void hasIngredientsRequiresEveryStackPresentInCount() {
        var r = recipe(List.of(
                "minecraft:oak_planks", "minecraft:oak_planks", "",
                "", "", "", "", "", ""), "minecraft:stick", 4);
        assertThat(CarpentryBench.hasIngredients(
                Map.of("minecraft:oak_planks", 2), r)).isTrue();
        assertThat(CarpentryBench.hasIngredients(
                Map.of("minecraft:oak_planks", 1), r)).isFalse();
        assertThat(CarpentryBench.hasIngredients(
                Map.of("minecraft:birch_planks", 5), r)).isFalse();
        // A recipe with an empty grid crafts from nothing — always satisfied.
        var empty = recipe(List.of("", "", "", "", "", "", "", "", ""),
                "minecraft:air", 1);
        assertThat(CarpentryBench.hasIngredients(Map.of(), empty)).isTrue();
    }

    @Test
    void nullAndEmptyInputsFailSafe() {
        assertThat(CarpentryBench.craft(null, null))
                .isEqualTo(CarpentryBench.CraftResult.NO_RECIPE);
        assertThat(CarpentryBench.craft(null, recipe(
                List.of("", "", "", "", "", "", "", "", ""),
                "minecraft:stick", 4)))
                .isEqualTo(CarpentryBench.CraftResult.NO_RECIPE);
    }
}
