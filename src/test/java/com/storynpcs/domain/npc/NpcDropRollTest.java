package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drop-table roll semantics — VERIFIED_TARGET_SOURCE against the target's
 * {@code DataInventory.getItemsRNG}/{@code getExpRNG}: each slot is an
 * independent {@code nextInt(100) + chance >= 100} trial and experience is
 * {@code minExp + nextInt(maxExp - minExp)}.
 */
class NpcDropRollTest {

    private static final NpcItemStack GEM = NpcItemStack.single(NamespacedId.of("minecraft:emerald"));

    @Test
    void fixedSeedProducesDeterministicRolls() {
        NpcInventory inv = new NpcInventory();
        for (int i = 0; i < 9; i++) inv.setDrop(i, GEM, 50);
        inv.setMinExp(10);
        inv.setMaxExp(20);

        NpcDropRoll.Result first = NpcDropRoll.roll(inv, new Random(42L));
        NpcDropRoll.Result second = NpcDropRoll.roll(inv, new Random(42L));

        assertThat(first.drops()).isEqualTo(second.drops());
        assertThat(first.experience()).isEqualTo(second.experience());
        assertThat(first.experience()).isBetween(10, 19);
    }

    @Test
    void guaranteedAndZeroChancesAreExact() {
        NpcInventory inv = new NpcInventory();
        inv.setDrop(0, GEM, 100); // always
        inv.setDrop(1, GEM, 0);   // never: nextInt(100) >= 100 is unreachable

        for (long seed = 0; seed < 50; seed++) {
            NpcDropRoll.Result roll = NpcDropRoll.roll(inv, new Random(seed));
            assertThat(roll.drops()).containsExactly(new NpcDropRoll.RolledDrop(0, GEM));
        }
    }

    @Test
    void nothingLootModeSuppressesDropsAndExperience() {
        NpcInventory inv = new NpcInventory();
        inv.setDrop(0, GEM, 100);
        inv.setMinExp(500);
        inv.setMaxExp(500);
        inv.setLootMode(NpcInventory.LootMode.NOTHING);

        assertThat(NpcDropRoll.roll(inv, new Random(1L))).isEqualTo(NpcDropRoll.Result.EMPTY);
        assertThat(NpcDropRoll.roll(null, new Random(1L))).isEqualTo(NpcDropRoll.Result.EMPTY);
    }

    @Test
    void autoPickupStillRollsTheTable() {
        // Delivery is an entity-layer concern — the roll itself is identical.
        NpcInventory inv = new NpcInventory();
        inv.setDrop(0, GEM, 100);
        inv.setLootMode(NpcInventory.LootMode.AUTO_PICKUP);

        assertThat(NpcDropRoll.roll(inv, new Random(7L)).drops())
                .containsExactly(new NpcDropRoll.RolledDrop(0, GEM));
    }

    @Test
    void experienceHonoursTheMinMaxRange() {
        NpcInventory inv = new NpcInventory();
        inv.setMinExp(30);
        inv.setMaxExp(30); // equal bounds collapse to the fixed min
        assertThat(NpcDropRoll.roll(inv, new Random(3L)).experience()).isEqualTo(30);

        inv.setMaxExp(100);
        for (long seed = 0; seed < 200; seed++) {
            int xp = NpcDropRoll.roll(inv, new Random(seed)).experience();
            assertThat(xp).isBetween(30, 99); // target rolls nextInt(maxExp - minExp) — exclusive
        }

        inv.setMinExp(50);
        inv.setMaxExp(40); // maxExp < minExp falls back to the fixed min
        assertThat(NpcDropRoll.roll(inv, new Random(5L)).experience()).isEqualTo(50);
    }
}
