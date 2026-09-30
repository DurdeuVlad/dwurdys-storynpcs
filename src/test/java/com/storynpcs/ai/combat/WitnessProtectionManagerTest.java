package com.storynpcs.ai.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards defend "innocent victims" — the witnessed-assault predicate must
 * reject hostile mobs and decorative entities or players are punished for
 * ordinary combat and decoration edits. Class literals are asserted so the
 * check needs no live world.
 */
class WitnessProtectionManagerTest {

    @Test
    @DisplayName("Players, villagers, golems and pets are protected victims")
    void protectedVictimCategories() {
        assertTrue(WitnessProtectionManager.isInnocentVictimClass(ServerPlayer.class),
                "PvP victims must be defended");
        assertTrue(WitnessProtectionManager.isInnocentVictimClass(Villager.class));
        assertTrue(WitnessProtectionManager.isInnocentVictimClass(WanderingTrader.class));
        assertTrue(WitnessProtectionManager.isInnocentVictimClass(IronGolem.class));
        assertTrue(WitnessProtectionManager.isInnocentVictimClass(Wolf.class));
    }

    @Test
    @DisplayName("Hostile and neutral-hostile mobs are never innocent victims")
    void hostileVictimsRejected() {
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(Zombie.class));
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(Creeper.class));
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(Slime.class));
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(Shulker.class));
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(EnderMan.class),
                "Neutral-hostile Monster subclasses are excluded too");
    }

    @Test
    @DisplayName("Decorative and null victims do not trigger guard response")
    void nonCreatureVictimsRejected() {
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(ArmorStand.class),
                "Armor stands are LivingEntity but not creatures");
        assertFalse(WitnessProtectionManager.isInnocentVictimClass(null));
        assertFalse(WitnessProtectionManager.isInnocentVictim(null));
    }
}
