package com.storynpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoryNpcsCommandsTest {

    @Test
    @DisplayName("Command tree registers /storynpcs and /sn alias with all subcommands")
    void testCommandRegistration() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StoryNpcsCommands.register(dispatcher);

        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        CommandNode<CommandSourceStack> storynpcs = root.getChild("storynpcs");
        assertNotNull(storynpcs, "Root /storynpcs command node must exist");

        CommandNode<CommandSourceStack> sn = root.getChild("sn");
        assertNotNull(sn, "Alias /sn command node must exist");
        assertSame(storynpcs, sn.getRedirect(), "/sn should redirect to /storynpcs");

        // Subcommands under /storynpcs
        assertNotNull(storynpcs.getChild("reload"), "Subcommand 'reload' must exist");
        assertNotNull(storynpcs.getChild("help"), "Subcommand 'help' must exist");
        assertNotNull(storynpcs.getChild("me"), "Subcommand 'me' must exist");
        assertNotNull(storynpcs.getChild("quickstart"), "Subcommand 'quickstart' must exist");

        // NPC subcommands
        CommandNode<CommandSourceStack> npc = storynpcs.getChild("npc");
        assertNotNull(npc, "Subcommand 'npc' must exist");
        assertNotNull(npc.getChild("create"), "npc create must exist");
        assertNotNull(npc.getChild("list"), "npc list must exist");
        assertNotNull(npc.getChild("info"), "npc info must exist");
        assertNotNull(npc.getChild("spawn"), "npc spawn must exist");
        assertNotNull(npc.getChild("despawn"), "npc despawn must exist");
        assertNotNull(npc.getChild("delete"), "npc delete must exist");

        // npc create takes npc_id then an optional greedy name
        CommandNode<CommandSourceStack> create = npc.getChild("create");
        CommandNode<CommandSourceStack> createId = create.getChild("npc_id");
        assertNotNull(createId, "npc create npc_id argument must exist");
        assertNotNull(createId.getChild("name"), "npc create optional name argument must exist");

        // npc set subcommands
        CommandNode<CommandSourceStack> set = npc.getChild("set");
        assertNotNull(set, "npc set must exist");
        assertNotNull(set.getChild("name"), "npc set name must exist");
        assertNotNull(set.getChild("title"), "npc set title must exist");
        assertNotNull(set.getChild("skin"), "npc set skin must exist");
        assertNotNull(set.getChild("health"), "npc set health must exist");
        assertNotNull(set.getChild("damage"), "npc set damage must exist");
        assertNotNull(set.getChild("speed"), "npc set speed must exist");
        assertNotNull(set.getChild("range"), "npc set range must exist");
        assertNotNull(set.getChild("movement"), "npc set movement must exist");
        assertNotNull(set.getChild("stance"), "npc set stance must exist");
        assertNotNull(set.getChild("dialogue"), "npc set dialogue must exist");
        assertNotNull(set.getChild("faction"), "npc set faction must exist");

        // Dialogue subcommands
        CommandNode<CommandSourceStack> dialogue = storynpcs.getChild("dialogue");
        assertNotNull(dialogue, "Subcommand 'dialogue' must exist");
        assertNotNull(dialogue.getChild("list"), "dialogue list must exist");
        assertNotNull(dialogue.getChild("info"), "dialogue info must exist");
        assertNotNull(dialogue.getChild("edit"), "dialogue edit must exist");
        assertNotNull(dialogue.getChild("start"), "dialogue start must exist");

        // Quest subcommands
        CommandNode<CommandSourceStack> quest = storynpcs.getChild("quest");
        assertNotNull(quest, "Subcommand 'quest' must exist");
        assertNotNull(quest.getChild("list"), "quest list must exist");
        assertNotNull(quest.getChild("start"), "quest start must exist");
        assertNotNull(quest.getChild("complete"), "quest complete must exist");

        // Quest authoring subcommands (issue #17)
        assertNotNull(quest.getChild("create").getChild("quest_id"), "quest create quest_id arg");
        assertNotNull(quest.getChild("create").getChild("quest_id").getChild("title"),
                "quest create optional title arg");

        CommandNode<CommandSourceStack> qset = quest.getChild("set");
        assertNotNull(qset, "quest set must exist");
        CommandNode<CommandSourceStack> qsetId = qset.getChild("quest_id");
        assertNotNull(qsetId, "quest set quest_id arg");
        assertNotNull(qsetId.getChild("description"), "quest set description");
        assertNotNull(qsetId.getChild("category"), "quest set category");
        assertNotNull(qsetId.getChild("repeatType"), "quest set repeatType");

        CommandNode<CommandSourceStack> objective = quest.getChild("objective");
        assertNotNull(objective, "quest objective must exist");
        CommandNode<CommandSourceStack> objAdd = objective.getChild("add");
        assertNotNull(objAdd, "quest objective add");
        CommandNode<CommandSourceStack> objAddId = objAdd.getChild("quest_id");
        assertNotNull(objAddId, "objective add quest_id");
        CommandNode<CommandSourceStack> objType = objAddId.getChild("type");
        assertNotNull(objType, "objective add type arg");
        CommandNode<CommandSourceStack> objTarget = objType.getChild("target");
        assertNotNull(objTarget, "objective add target arg");
        assertNotNull(objTarget.getChild("requiredCount"), "objective add requiredCount arg");
        CommandNode<CommandSourceStack> objRemove = objective.getChild("remove");
        assertNotNull(objRemove, "quest objective remove");
        assertNotNull(objRemove.getChild("quest_id"), "objective remove quest_id");
        assertNotNull(objRemove.getChild("quest_id").getChild("objective_id"), "objective remove objective_id");

        CommandNode<CommandSourceStack> reward = quest.getChild("reward");
        assertNotNull(reward, "quest reward must exist");
        CommandNode<CommandSourceStack> rewAdd = reward.getChild("add");
        assertNotNull(rewAdd, "quest reward add");
        CommandNode<CommandSourceStack> rewType = rewAdd.getChild("quest_id").getChild("type");
        assertNotNull(rewType, "reward add type arg");
        CommandNode<CommandSourceStack> rewTarget = rewType.getChild("target");
        assertNotNull(rewTarget, "reward add target arg");
        assertNotNull(rewTarget.getChild("amount"), "reward add amount arg");
        CommandNode<CommandSourceStack> rewRemove = reward.getChild("remove");
        assertNotNull(rewRemove, "quest reward remove");
        assertNotNull(rewRemove.getChild("quest_id"), "reward remove quest_id");
        assertNotNull(rewRemove.getChild("quest_id").getChild("index"), "reward remove index arg");

        // Faction subcommands
        CommandNode<CommandSourceStack> faction = storynpcs.getChild("faction");
        assertNotNull(faction, "Subcommand 'faction' must exist");
        assertNotNull(faction.getChild("list"), "faction list must exist");
        assertNotNull(faction.getChild("set"), "faction set must exist");
        assertNotNull(faction.getChild("adjust"), "faction adjust must exist");
        assertNotNull(faction.getChild("create"), "faction create must exist");
        assertNotNull(faction.getChild("create").getChild("faction_id"), "faction create faction_id");
        CommandNode<CommandSourceStack> configure = faction.getChild("configure");
        assertNotNull(configure, "faction configure must exist");
        CommandNode<CommandSourceStack> cfgId = configure.getChild("faction_id");
        assertNotNull(cfgId, "faction configure faction_id");
        for (String f : new String[]{"defaultPoints", "hostileThreshold", "friendlyThreshold"}) {
            CommandNode<CommandSourceStack> field = cfgId.getChild(f);
            assertNotNull(field, "faction configure " + f);
            assertNotNull(field.getChild("value"), "faction configure " + f + " value arg");
        }

        // Follower subcommands
        CommandNode<CommandSourceStack> follower = storynpcs.getChild("follower");
        assertNotNull(follower, "Subcommand 'follower' must exist");
        assertNotNull(follower.getChild("recall"), "follower recall must exist");
        assertNotNull(follower.getChild("formation"), "follower formation must exist");
        assertNotNull(follower.getChild("state"), "follower state must exist");
    }

    @Test
    @DisplayName("Definition ID arguments expose tab-completion suggestion providers")
    void testIdArgumentSuggestions() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StoryNpcsCommands.register(dispatcher);
        CommandNode<CommandSourceStack> storynpcs = dispatcher.getRoot().getChild("storynpcs");

        assertSuggestions(storynpcs.getChild("npc").getChild("info"), "npc_id");
        assertSuggestions(storynpcs.getChild("npc").getChild("spawn"), "npc_id");
        assertSuggestions(storynpcs.getChild("npc").getChild("despawn"), "npc_id");
        assertSuggestions(storynpcs.getChild("npc").getChild("delete"), "npc_id");
        assertSuggestions(storynpcs.getChild("dialogue").getChild("info"), "dialogue_id");
        assertSuggestions(storynpcs.getChild("dialogue").getChild("edit"), "dialogue_id");
        assertSuggestions(storynpcs.getChild("dialogue").getChild("start"), "dialogue_id");
        assertSuggestions(storynpcs.getChild("quest").getChild("start"), "quest_id");
        assertSuggestions(storynpcs.getChild("quest").getChild("complete"), "quest_id");
        assertSuggestions(storynpcs.getChild("faction").getChild("set"), "faction_id");
        assertSuggestions(storynpcs.getChild("faction").getChild("adjust"), "faction_id");
    }

    @Test
    @DisplayName("quest objective/reward add commands parse to the final count argument")
    void testQuestAddCommandsParse() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StoryNpcsCommands.register(dispatcher);

        CommandSourceStack source = opSource();

        var objParse = dispatcher.parse(
                "storynpcs quest objective add storynpcs:m3test KILL_ENTITY minecraft:zombie 3",
                source);
        assertTrue(objParse.getExceptions().isEmpty(),
                "objective add parse failed: " + objParse.getExceptions());
        assertFalse(objParse.getReader().canRead(),
                "unconsumed input after objective add: " + objParse.getReader().getRemaining());

        var rewParse = dispatcher.parse(
                "storynpcs quest reward add storynpcs:m3test ITEM minecraft:diamond 2",
                source);
        assertTrue(rewParse.getExceptions().isEmpty(),
                "reward add parse failed: " + rewParse.getExceptions());
        assertFalse(rewParse.getReader().canRead(),
                "unconsumed input after reward add: " + rewParse.getReader().getRemaining());
    }

    private static CommandSourceStack opSource() {
        return new CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
                net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO,
                null, 4, "test", null, null, null);
    }

    private static void assertSuggestions(CommandNode<CommandSourceStack> parent, String argName) {
        assertNotNull(parent, "Parent node must exist for arg " + argName);
        CommandNode<CommandSourceStack> arg = parent.getChild(argName);
        assertNotNull(arg, "Argument '" + argName + "' must exist under " + parent.getName());
        assertTrue(arg instanceof com.mojang.brigadier.tree.ArgumentCommandNode,
                argName + " must be an argument node");
        assertNotNull(((com.mojang.brigadier.tree.ArgumentCommandNode<?, ?>) arg).getCustomSuggestions(),
                argName + " must provide tab-completion suggestions");
    }
}