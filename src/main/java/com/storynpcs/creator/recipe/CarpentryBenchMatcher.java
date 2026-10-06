package com.storynpcs.creator.recipe;

import java.util.ArrayList;
import java.util.List;

import com.storynpcs.domain.common.NamespacedId;

/**
 * Pure carpentry-bench matching engine (P8-4). Given a 3x3 input grid of
 * namespaced item ids, finds the recipe in a group that produces it.
 * Shaped recipes match the grid slot-for-slot; shapeless recipes match as a
 * multiset of non-empty slots. Deterministic: the first recipe in the
 * registry's sorted order wins, and overlapping matches are reported, never
 * resolved arbitrarily by iteration order.
 */
public final class CarpentryBenchMatcher {

    private CarpentryBenchMatcher() {}

    public record Match(CarpentryRecipe recipe, String outputItemId, int outputCount) {}

    public record Result(Match match, List<NamespacedId> allMatches) {
        public boolean ambiguous() {
            return allMatches.size() > 1;
        }
    }

    /**
     * Match {@code input} (exactly {@link CarpentryRecipe#GRID} slots,
     * null/blank = empty) against every recipe in {@code groupId}.
     * The deterministic winner is the first match in sorted-id order;
     * {@link Result#allMatches} lists every matching recipe so callers can
     * surface ambiguity instead of silently picking one.
     */
    public static Result match(DefinitionRegistryView registry, NamespacedId groupId, List<String> input) {
        if (input == null || input.size() != CarpentryRecipe.GRID) {
            throw new IllegalArgumentException(
                    "input grid must have exactly " + CarpentryRecipe.GRID + " slots");
        }
        List<String> normalized = normalize(input);
        List<NamespacedId> matches = new ArrayList<>();
        CarpentryRecipe winner = null;
        for (var recipe : registry.getRecipesInGroup(groupId)) {
            boolean hit = recipe.isShapeless()
                    ? matchesShapeless(recipe.getGrid(), normalized)
                    : matchesShaped(recipe.getGrid(), normalized);
            if (hit) {
                matches.add(recipe.getId());
                if (winner == null) {
                    winner = recipe;
                }
            }
        }
        Match first = winner == null ? null
                : new Match(winner, winner.getOutputItemId(), winner.getOutputCount());
        return new Result(first, List.copyOf(matches));
    }

    /** Shaped: exact slot-for-slot equality after normalization. */
    static boolean matchesShaped(List<String> recipeGrid, List<String> input) {
        List<String> normalized = normalize(recipeGrid);
        for (int i = 0; i < CarpentryRecipe.GRID; i++) {
            if (!normalized.get(i).equals(input.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Shapeless: same multiset of non-empty slots, order irrelevant. */
    static boolean matchesShapeless(List<String> recipeGrid, List<String> input) {
        List<String> need = nonEmpty(recipeGrid);
        List<String> have = new ArrayList<>(nonEmpty(input));
        for (String item : need) {
            if (!have.remove(item)) {
                return false;
            }
        }
        return have.isEmpty();
    }

    private static List<String> normalize(List<String> grid) {
        List<String> out = new ArrayList<>(CarpentryRecipe.GRID);
        for (int i = 0; i < CarpentryRecipe.GRID; i++) {
            String s = i < grid.size() ? grid.get(i) : null;
            out.add(s == null ? "" : s.trim());
        }
        return out;
    }

    private static List<String> nonEmpty(List<String> grid) {
        List<String> out = new ArrayList<>();
        for (String s : grid) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** Narrow seam so the matcher can run against a registry snapshot in tests. */
    @FunctionalInterface
    public interface DefinitionRegistryView {
        List<CarpentryRecipe> getRecipesInGroup(NamespacedId groupId);
    }
}
