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

        // Faction subcommands
        CommandNode<CommandSourceStack> faction = storynpcs.getChild("faction");
        assertNotNull(faction, "Subcommand 'faction' must exist");
        assertNotNull(faction.getChild("list"), "faction list must exist");
        assertNotNull(faction.getChild("set"), "faction set must exist");
        assertNotNull(faction.getChild("adjust"), "faction adjust must exist");

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