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

        // NPC subcommands
        CommandNode<CommandSourceStack> npc = storynpcs.getChild("npc");
        assertNotNull(npc, "Subcommand 'npc' must exist");
        assertNotNull(npc.getChild("list"), "npc list must exist");
        assertNotNull(npc.getChild("info"), "npc info must exist");
        assertNotNull(npc.getChild("delete"), "npc delete must exist");

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
    }
}