package com.storynpcs.creator.recipe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carpentry-bench craft evaluation (issue #150, GuiNpcCarpentryBench parity).
 * The ingredient math is pure and unit-tested; the player-inventory consume
 * step runs last and only after every required stack is verified present, so
 * a failed craft never leaves a partially-consumed inventory.
 */
public final class CarpentryBench {

    private CarpentryBench() {}

    public enum CraftResult { CRAFTED, NO_RECIPE, MISSING_INGREDIENTS, INVENTORY_FULL }

    /** Multiset of required ingredients — empty slots contribute nothing. */
    public static Map<String, Integer> requiredItems(CarpentryRecipe recipe) {
        Map<String, Integer> required = new LinkedHashMap<>();
        if (recipe == null) {
            return required;
        }
        for (String slot : recipe.getGrid()) {
            if (slot != null && !slot.isBlank()) {
                required.merge(slot, 1, Integer::sum);
            }
        }
        return required;
    }

    /** Display lines like "minecraft:oak_planks x3" for the bench view. */
    public static List<String> ingredientSummary(CarpentryRecipe recipe) {
        return requiredItems(recipe).entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .toList();
    }

    /** Whether the inventory multiset satisfies the recipe's requirements. */
    public static boolean hasIngredients(Map<String, Integer> inventoryCounts,
                                         CarpentryRecipe recipe) {
        for (var e : requiredItems(recipe).entrySet()) {
            if (inventoryCounts.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Craft once against the player's live inventory. Two phases: (1) verify
     * every ingredient is present in sufficient count; (2) consume + grant.
     * Between them nothing external runs, so the consume cannot see a state
     * the verify phase did not approve. Output overflow drops at the player's
     * feet rather than voiding items.
     */
    public static CraftResult craft(ServerPlayer player, CarpentryRecipe recipe) {
        if (player == null || recipe == null) {
            return CraftResult.NO_RECIPE;
        }
        var required = requiredItems(recipe);
        // Phase 1: verify — count matching stacks per ingredient.
        Map<String, Integer> available = new LinkedHashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            available.merge(key.toString(), stack.getCount(), Integer::sum);
        }
        if (!hasIngredients(available, recipe)) {
            return CraftResult.MISSING_INGREDIENTS;
        }
        // Phase 2: consume exactly the required counts, then grant output.
        for (var e : required.entrySet()) {
            Item item = resolveItem(e.getKey());
            if (item == null) {
                return CraftResult.NO_RECIPE;
            }
            int remaining = e.getValue();
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || stack.getItem() != item) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
            if (remaining > 0) {
                // Should be unreachable after phase 1 — abort loud rather than
                // grant output for ingredients that vanished mid-craft.
                return CraftResult.MISSING_INGREDIENTS;
            }
        }
        Item output = resolveItem(recipe.getOutputItemId());
        if (output == null) {
            return CraftResult.NO_RECIPE;
        }
        ItemStack result = new ItemStack(output, recipe.getOutputCount());
        if (!inv.add(result)) {
            player.drop(result, false);
        }
        return CraftResult.CRAFTED;
    }

    private static Item resolveItem(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId == null ? "" : itemId);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }
}
