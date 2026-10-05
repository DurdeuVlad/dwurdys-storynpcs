package com.storynpcs.domain.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Headless drop-table resolution for an authored {@link NpcInventory}
 * (P3-4). Deterministic given a seeded {@link RandomGenerator} — the entity
 * layer passes its world random, tests pass fixed seeds.
 *
 * <p>VERIFIED_TARGET_SOURCE semantics (target {@code DataInventory.getItemsRNG}
 * / {@code getExpRNG}): every occupied drop entry rolls independently —
 * {@code nextInt(100) + chance >= 100} — so each slot's authored
 * {@code chancePercent} is its own Bernoulli trial; nothing is biased by slot
 * order beyond RNG draw order. Experience is {@code minExp} plus a uniform
 * roll in {@code [0, maxExp - minExp)} when {@code maxExp > minExp}.
 *
 * <p>{@code LootMode} only affects delivery, not the roll — except
 * {@link NpcInventory.LootMode#NOTHING}, a StoryNPCs extension that suppresses
 * the authored drop table and inventory XP entirely.
 */
public final class NpcDropRoll {

    private NpcDropRoll() {}

    /** One rolled drop: the authored slot index and its stack. */
    public record RolledDrop(int index, NpcItemStack item) {}

    /** The roll outcome: every dropped stack plus the experience amount. */
    public record Result(List<RolledDrop> drops, int experience) {
        public static final Result EMPTY = new Result(List.of(), 0);
    }

    public static Result roll(NpcInventory inventory, RandomGenerator rng) {
        if (inventory == null || inventory.getLootMode() == NpcInventory.LootMode.NOTHING) {
            return Result.EMPTY;
        }
        List<RolledDrop> drops = new ArrayList<>();
        int index = 0;
        for (NpcInventory.DropEntry entry : inventory.getDrops()) {
            if (entry != null && entry.getItem() != null
                    && rng.nextInt(100) + entry.getChancePercent() >= 100) {
                drops.add(new RolledDrop(index, entry.getItem()));
            }
            index++;
        }
        return new Result(List.copyOf(drops), rollExperience(inventory, rng));
    }

    private static int rollExperience(NpcInventory inventory, RandomGenerator rng) {
        int min = inventory.getMinExp();
        int range = inventory.getMaxExp() - min;
        return range > 0 ? min + rng.nextInt(range) : min;
    }
}
