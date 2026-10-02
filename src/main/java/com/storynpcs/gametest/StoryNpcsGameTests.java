package com.storynpcs.gametest;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcStats;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.entity.StoryNpcRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Headless in-world proof tier (issue #95): asserts real server/entity state through
 * the actual registration and {@link com.storynpcs.service.StoryNpcsApplicationService}
 * paths, complementing the JUnit suite in {@code src/test} which only covers logic that
 * can be extracted from a running Minecraft server.
 *
 * Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(StoryNpcs.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoryNpcsGameTests {

    private StoryNpcsGameTests() {}

    @GameTest(template = "gametest/empty_3x3x3")
    public static void npcEntitySpawnsWithRegisteredType(GameTestHelper helper) {
        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);

        helper.assertTrue(npc != null, "Expected StoryNpcRegistry.STORY_NPC to spawn a StoryNpcEntity");
        helper.assertTrue(npc.isAlive(), "Freshly spawned StoryNpcEntity should be alive");
        helper.assertTrue(npc.getType() == StoryNpcRegistry.STORY_NPC.get(),
                "Spawned entity's type should be the registered storynpcs:npc entity type");

        helper.succeed();
    }

    @GameTest(template = "gametest/empty_3x3x3")
    public static void definitionCreatedThroughApplicationServiceBindsToSpawnedEntity(GameTestHelper helper) {
        NamespacedId definitionId = NamespacedId.of("storynpcs:test/gametest_npc");
        NpcDefinition definition = new NpcDefinition(definitionId, "GameTest NPC");

        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        StoryNpcs mod = StoryNpcsAccess.mod(npc);
        helper.assertTrue(mod != null, "StoryNpcs must be attached to the GameTest level");

        // Canonical mutation path per AGENTS.md: every definition write goes through
        // StoryNpcsApplicationService, never a direct registry poke.
        mod.getApplicationService().createNpc(definition);
        npc.setDefinitionId(definitionId.toString());

        helper.assertTrue(npc.getDefinition().isPresent(),
                "Entity should resolve the definition created through StoryNpcsApplicationService.createNpc");
        helper.assertTrue(definitionId.toString().equals(npc.getDefinitionId()),
                "Entity's synced definition id should match what was created via the application service");
        helper.assertTrue("GameTest NPC".equals(npc.getName().getString()),
                "Entity display name should reflect the definition's display name, was: " + npc.getName().getString());

        helper.succeed();
    }

    /**
     * P3-2 defeat runtime: an authored HIDE NPC that takes lethal damage must
     * become an invisible, invulnerable statue, must not be corpse-removed,
     * must survive bypass-invulnerability hits during the countdown without
     * resetting it, and must reappear at full health once the authored
     * respawn time elapses.
     */
    @GameTest(template = "gametest/empty_3x3x3", timeoutTicks = 200)
    public static void hideDefeatEntityHidesThenReappears(GameTestHelper helper) {
        NamespacedId definitionId = NamespacedId.of("storynpcs:test/gametest_hide_npc");
        NpcDefinition definition = new NpcDefinition(definitionId, "Hide NPC");
        definition.getStats().setRespawnTimeSeconds(1); // 20 ticks
        definition.getStats().getDefeat().setMode(NpcStats.Defeat.Mode.HIDE);

        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        StoryNpcs mod = StoryNpcsAccess.mod(npc);
        helper.assertTrue(mod != null, "StoryNpcs must be attached to the GameTest level");
        mod.getApplicationService().createNpc(definition);
        npc.setDefinitionId(definitionId.toString());

        // Lethal generic damage resolves the authored defeat contract.
        npc.hurt(npc.damageSources().generic(), Float.MAX_VALUE);
        helper.assertTrue(npc.isHiddenDefeat(),
                "Lethal damage on a HIDE-mode NPC should enter hidden defeat");
        helper.assertTrue(npc.isInvisible(), "Hidden-defeat statue should be invisible");
        helper.assertFalse(npc.isCurrentlyGlowing(),
                "Hidden-defeat statue must suppress its glow outline — it renders through invisibility");
        helper.assertFalse(npc.isCustomNameVisible(),
                "Hidden-defeat statue must suppress its nameplate — it renders through invisibility");
        helper.assertFalse(npc.hasCustomName(),
                "Hidden-defeat statue must drop its synced name — crosshair-pick plates render on invisible entities");
        helper.assertFalse(npc.isRemoved(), "Hidden-defeat statue must not be removed");
        helper.assertTrue(npc.getHealth() > 0.0F,
                "Hidden-defeat statue must never sit at 0 HP (vanilla tickDeath would remove it)");

        // A bypass-invulnerability hit mid-countdown must neither remove the
        // statue nor reset the countdown — the timer still fires at ~20 ticks.
        helper.runAfterDelay(5, () -> {
            npc.hurt(helper.getLevel().damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            helper.assertTrue(npc.isHiddenDefeat(),
                    "Void damage must not re-resolve defeat while hidden");
            helper.assertFalse(npc.isRemoved(),
                    "Void damage must not remove the hidden statue");
        });

        // A mid-window definition refresh (editor save, command reload) must
        // not resurface the statue — the applyDefinition projections are
        // hidden-guarded, including the synced custom name.
        helper.runAfterDelay(10, () -> {
            npc.applyDefinition();
            helper.assertTrue(npc.isHiddenDefeat(), "Refresh must not end hidden defeat");
            helper.assertTrue(npc.isInvisible(), "Refresh must not unhide the statue");
            helper.assertFalse(npc.isCurrentlyGlowing(), "Refresh must not re-arm the glow outline");
            helper.assertFalse(npc.hasCustomName(), "Refresh must not re-arm the pick nameplate");
        });

        helper.succeedWhen(() -> {
            helper.assertFalse(npc.isHiddenDefeat(),
                    "NPC should have reappeared once the authored respawn time elapsed");
            helper.assertFalse(npc.isInvisible(),
                    "Reappeared NPC should restore authored visibility (default: visible)");
            helper.assertTrue(npc.isCurrentlyGlowing(),
                    "Reappeared NPC should restore authored overlayGlowing (default: true)");
            helper.assertTrue(npc.isCustomNameVisible(),
                    "Reappeared NPC should restore its authored nameplate");
            helper.assertTrue(npc.getHealth() == npc.getMaxHealth(),
                    "Reappeared NPC should be at full health, was: " + npc.getHealth());
        });
    }

    /**
     * P3-2 defeat runtime: an authored FLEE NPC survives the fatal hit at the
     * authored health threshold instead of dying — no corpse, no removal.
     */
    @GameTest(template = "gametest/empty_3x3x3", timeoutTicks = 100)
    public static void fleeDefeatEntitySurvivesAtThreshold(GameTestHelper helper) {
        NamespacedId definitionId = NamespacedId.of("storynpcs:test/gametest_flee_npc");
        NpcDefinition definition = new NpcDefinition(definitionId, "Flee NPC");
        definition.getStats().getDefeat().setMode(NpcStats.Defeat.Mode.FLEE);
        definition.getStats().getDefeat().setFleeHealthPercent(10);

        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        StoryNpcs mod = StoryNpcsAccess.mod(npc);
        helper.assertTrue(mod != null, "StoryNpcs must be attached to the GameTest level");
        mod.getApplicationService().createNpc(definition);
        npc.setDefinitionId(definitionId.toString());

        float maxHealth = npc.getMaxHealth();
        npc.hurt(npc.damageSources().generic(), Float.MAX_VALUE);
        helper.assertFalse(npc.isRemoved(), "FLEE-mode NPC must survive lethal damage");
        helper.assertFalse(npc.isHiddenDefeat(), "FLEE-mode NPC must not enter hidden defeat");
        helper.assertTrue(npc.isAlive(), "FLEE-mode NPC should still be alive");
        float expected = Math.max(1.0f, maxHealth * 0.10f);
        helper.assertTrue(Math.abs(npc.getHealth() - expected) < 0.01f,
                "FLEE-mode NPC should hold the authored threshold health, expected ~"
                        + expected + ", was: " + npc.getHealth());

        helper.succeed();
    }

    /**
     * P3-4 drops runtime: a DIE-mode NPC with authored drops spawns them as
     * world item entities on death, and the authored experience range becomes
     * real orbs — the authored table is the drop contract, not decoration.
     */
    @GameTest(template = "gametest/empty_3x3x3", timeoutTicks = 100)
    public static void dieNpcRollsAuthoredDropsIntoTheWorld(GameTestHelper helper) {
        NamespacedId definitionId = NamespacedId.of("storynpcs:test/gametest_drops_npc");
        NpcDefinition definition = new NpcDefinition(definitionId, "Drops NPC");
        definition.getInventory().setDrop(0,
                com.storynpcs.domain.npc.NpcItemStack.single(
                        NamespacedId.of("minecraft:diamond")), 100);
        definition.getInventory().setMinExp(5);
        definition.getInventory().setMaxExp(5);

        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        StoryNpcs mod = StoryNpcsAccess.mod(npc);
        helper.assertTrue(mod != null, "StoryNpcs must be attached to the GameTest level");
        mod.getApplicationService().createNpc(definition);
        npc.setDefinitionId(definitionId.toString());

        npc.hurt(npc.damageSources().generic(), Float.MAX_VALUE);
        helper.assertFalse(npc.isAlive(), "DIE-mode NPC should resolve fatal damage");

        helper.succeedWhen(() -> {
            var bounds = helper.getBounds().inflate(3);
            var items = helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, bounds);
            helper.assertTrue(items.stream().anyMatch(e -> e.getItem().is(
                            net.minecraft.world.item.Items.DIAMOND)),
                    "Authored 100%-chance diamond drop should exist as a world item entity");
            var orbs = helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.ExperienceOrb.class, bounds);
            int totalXp = orbs.stream().mapToInt(orb -> orb.value).sum();
            helper.assertTrue(totalXp == 5,
                    "Authored minExp=maxExp=5 should award exactly 5 XP, got: " + totalXp);
        });
    }

    /**
     * P3-4 equipment projection: authored armor/hand items land on the real
     * entity equipment slots (visible to clients) while unmentioned slots
     * stay empty — and authored equipment never feeds the drop table.
     */
    @GameTest(template = "gametest/empty_3x3x3", timeoutTicks = 100)
    public static void authoredEquipmentProjectsOntoEntitySlots(GameTestHelper helper) {
        NamespacedId definitionId = NamespacedId.of("storynpcs:test/gametest_equip_npc");
        NpcDefinition definition = new NpcDefinition(definitionId, "Equip NPC");
        definition.getInventory().equip(
                com.storynpcs.domain.npc.NpcInventory.ItemSlot.HELMET,
                com.storynpcs.domain.npc.NpcItemStack.single(
                        NamespacedId.of("minecraft:iron_helmet")));
        definition.getInventory().equip(
                com.storynpcs.domain.npc.NpcInventory.ItemSlot.RIGHT_HAND,
                com.storynpcs.domain.npc.NpcItemStack.single(
                        NamespacedId.of("minecraft:iron_sword")));

        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        StoryNpcs mod = StoryNpcsAccess.mod(npc);
        helper.assertTrue(mod != null, "StoryNpcs must be attached to the GameTest level");
        mod.getApplicationService().createNpc(definition);
        npc.setDefinitionId(definitionId.toString());

        helper.assertTrue(npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                        .is(net.minecraft.world.item.Items.IRON_HELMET),
                "Authored HELMET should project onto the entity HEAD slot");
        helper.assertTrue(npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                        .is(net.minecraft.world.item.Items.IRON_SWORD),
                "Authored RIGHT_HAND should project onto the entity MAINHAND slot");
        helper.assertTrue(npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
                        .isEmpty(),
                "Un-authored slots should stay empty");

        helper.succeed();
    }

    /**
     * P4-1 tier consumption: the live scheduler-to-entity wiring — a DORMANT
     * evaluation disables sensing/pathing/combat and leaves only persistence
     * cadence, and an ACTIVE re-evaluation restores full capability. The
     * evaluation is injected synchronously so assertions are deterministic
     * regardless of where the periodic server-tick eval lands.
     */
    @GameTest(template = "gametest/empty_3x3x3", timeoutTicks = 100)
    public static void dormantTierDisablesGoalCapabilitiesOnLiveEntity(GameTestHelper helper) {
        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        StoryNpcs mod = StoryNpcsAccess.mod(npc);
        helper.assertTrue(mod != null, "StoryNpcs must be attached to the GameTest level");

        var scheduler = mod.getSimulationScheduler();
        helper.assertTrue(scheduler != null, "SimulationScheduler must be wired to the mod");

        var dormantInput = new com.storynpcs.sim.SimulationScheduler.ActorInput(
                npc.getUUID(), 400.0, false);
        scheduler.evaluate(java.util.List.of(dormantInput));
        helper.assertTrue(
                scheduler.stateOf(npc.getUUID()).tier() == com.storynpcs.sim.SimulationTier.DORMANT,
                "Actor at 400 blocks should be DORMANT");

        helper.assertTrue(npc.simulationCapabilityPeriod(
                        com.storynpcs.sim.SimulationScheduler.Capability.SENSING) < 0,
                "DORMANT sensing must be disabled — no sight scans");
        helper.assertTrue(npc.simulationCapabilityPeriod(
                        com.storynpcs.sim.SimulationScheduler.Capability.PATHING) < 0,
                "DORMANT pathing must be disabled — dormant NPCs stand still");
        helper.assertTrue(npc.simulationCapabilityPeriod(
                        com.storynpcs.sim.SimulationScheduler.Capability.COMBAT) < 0,
                "DORMANT combat must be disabled");
        helper.assertTrue(npc.simulationCapabilityPeriod(
                        com.storynpcs.sim.SimulationScheduler.Capability.PERSISTENCE) > 0,
                "DORMANT keeps only its persistence cadence");

        scheduler.evaluate(java.util.List.of(
                new com.storynpcs.sim.SimulationScheduler.ActorInput(
                        npc.getUUID(), 10.0, false)));
        helper.assertTrue(npc.simulationCapabilityPeriod(
                        com.storynpcs.sim.SimulationScheduler.Capability.SENSING) > 0,
                "ACTIVE re-evaluation must restore sensing");

        helper.succeed();
    }
}
