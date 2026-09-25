package com.storynpcs.gametest;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
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

        StoryNpcs mod = StoryNpcs.getInstance();
        helper.assertTrue(mod != null, "StoryNpcs.getInstance() must be initialized while a mod-loaded server is running");

        // Canonical mutation path per AGENTS.md: every definition write goes through
        // StoryNpcsApplicationService, never a direct registry poke.
        mod.getApplicationService().createNpc(definition);

        BlockPos spawnAt = new BlockPos(1, 1, 1);
        StoryNpcEntity npc = helper.spawn(StoryNpcRegistry.STORY_NPC.get(), spawnAt);
        npc.setDefinitionId(definitionId.toString());

        helper.assertTrue(npc.getDefinition().isPresent(),
                "Entity should resolve the definition created through StoryNpcsApplicationService.createNpc");
        helper.assertTrue(definitionId.toString().equals(npc.getDefinitionId()),
                "Entity's synced definition id should match what was created via the application service");
        helper.assertTrue("GameTest NPC".equals(npc.getName().getString()),
                "Entity display name should reflect the definition's display name, was: " + npc.getName().getString());

        helper.succeed();
    }
}
