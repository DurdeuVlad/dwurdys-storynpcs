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
}
