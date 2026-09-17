package com.storynpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.util.Collection;

public final class StoryNpcsCommands {

    private StoryNpcsCommands() {}

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("storynpcs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(StoryNpcsCommands::reload))
                // NPC commands
                .then(Commands.literal("npc")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listNpcs))
                        .then(Commands.literal("info")
                                .then(Commands.argument("npc_id", StringArgumentType.string())
                                        .executes(StoryNpcsCommands::infoNpc)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("npc_id", StringArgumentType.string())
                                        .executes(StoryNpcsCommands::deleteNpc))))
                // Dialogue commands
                .then(Commands.literal("dialogue")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listDialogues))
                        .then(Commands.literal("info")
                                .then(Commands.argument("dialogue_id", StringArgumentType.string())
                                        .executes(StoryNpcsCommands::infoDialogue)))
                        .then(Commands.literal("edit")
                                .then(Commands.argument("dialogue_id", StringArgumentType.string())
                                        .executes(StoryNpcsCommands::editDialogue)))
                        .then(Commands.literal("start")
                                .then(Commands.argument("dialogue_id", StringArgumentType.string())
                                        .executes(ctx -> startDialogue(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startDialogue(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                // Quest commands
                .then(Commands.literal("quest")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listQuests))
                        .then(Commands.literal("start")
                                .then(Commands.argument("quest_id", StringArgumentType.string())
                                        .executes(ctx -> startQuest(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startQuest(ctx, EntityArgument.getPlayer(ctx, "player"))))))
                        .then(Commands.literal("complete")
                                .then(Commands.argument("quest_id", StringArgumentType.string())
                                        .executes(ctx -> completeQuest(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> completeQuest(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                // Faction commands
                .then(Commands.literal("faction")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listFactions))
                        .then(Commands.literal("set")
                                .then(Commands.argument("faction_id", StringArgumentType.string())
                                        .then(Commands.argument("points", IntegerArgumentType.integer())
                                                .executes(ctx -> setFaction(ctx, null))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .executes(ctx -> setFaction(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                        .then(Commands.literal("adjust")
                                .then(Commands.argument("faction_id", StringArgumentType.string())
                                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                                .executes(ctx -> adjustFaction(ctx, null))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .executes(ctx -> adjustFaction(ctx, EntityArgument.getPlayer(ctx, "player"))))))));

        dispatcher.register(root);
        // Register alias /sn
        dispatcher.register(Commands.literal("sn").requires(source -> source.hasPermission(2)).redirect(dispatcher.getRoot().getChild("storynpcs")));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Reloading YAML definitions..."), true);

        StoryNpcs mod = StoryNpcs.getInstance();
        if (mod == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Mod instance not initialized"));
            return 0;
        }

        mod.getRegistry().clear();
        try {
            Path worldDir = source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            Path definitionsDir = worldDir.resolve("storynpcs").resolve("definitions");
            var result = mod.getLoader().loadDirectory(definitionsDir);

            if (result.isValid()) {
                source.sendSuccess(() -> Component.literal("[StoryNPCs] Definitions reloaded successfully: " + result.formatReport()), true);
                return 1;
            } else {
                source.sendFailure(Component.literal("[StoryNPCs] Validation errors during reload:\n" + result.formatReport()));
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("[StoryNPCs] Error during reload: " + e.getMessage()));
            return 0;
        }
    }

    // NPC Handlers
    private static int listNpcs(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        Collection<NpcDefinition> npcs = reg.getAllNpcs();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- StoryNPCs (%d loaded) ---", npcs.size())), false);
        for (NpcDefinition npc : npcs) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" - %s ('%s') [Faction: %s, Dialogue: %s]",
                    npc.getId(), npc.getDisplay().getName(), npc.getFactionId(), npc.getDialogueId())), false);
        }
        return npcs.size();
    }

    private static int infoNpc(CommandContext<CommandSourceStack> ctx) {
        String idStr = StringArgumentType.getString(ctx, "npc_id");
        NamespacedId id = NamespacedId.of(idStr);
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        var npcOpt = reg.getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("NPC not found: " + id));
            return 0;
        }
        NpcDefinition npc = npcOpt.get();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("=== NPC: %s ===", npc.getId())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Display: '%s' (Title: '%s', Skin: %s)",
                npc.getDisplay().getName(), npc.getDisplay().getTitle(), npc.getDisplay().getSkinTexture())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Stats: Health=%.1f, Damage=%.1f, Speed=%.2f",
                npc.getStats().getMaxHealth(), npc.getStats().getAttackDamage(), npc.getStats().getMovementSpeed())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" AI: %s (Range=%d, DoorInteract=%s)",
                npc.getAi().getMovementType(), npc.getAi().getWalkingRange(), npc.getAi().isDoorInteract())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Dialogue: %s | Faction: %s",
                npc.getDialogueId(), npc.getFactionId())), false);
        return 1;
    }

    private static int deleteNpc(CommandContext<CommandSourceStack> ctx) {
        String idStr = StringArgumentType.getString(ctx, "npc_id");
        NamespacedId id = NamespacedId.of(idStr);
        boolean deleted = StoryNpcs.getInstance().getApplicationService().deleteNpc(id);
        if (deleted) {
            ctx.getSource().sendSuccess(() -> Component.literal("Deleted NPC: " + id), true);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("NPC not found: " + id));
            return 0;
        }
    }

    // Dialogue Handlers
    private static int listDialogues(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        Collection<DialogueGraph> dialogues = reg.getAllDialogues();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- Dialogues (%d loaded) ---", dialogues.size())), false);
        for (DialogueGraph d : dialogues) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" - %s: '%s' (Entry: %s, Nodes: %d)",
                    d.getId(), d.getTitle(), d.getEntryNodeId(), d.getNodes().size())), false);
        }
        return dialogues.size();
    }

    private static int infoDialogue(CommandContext<CommandSourceStack> ctx) {
        String idStr = StringArgumentType.getString(ctx, "dialogue_id");
        NamespacedId id = NamespacedId.of(idStr);
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        var dOpt = reg.getDialogue(id);
        if (dOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Dialogue not found: " + id));
            return 0;
        }
        DialogueGraph d = dOpt.get();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("=== Dialogue: %s ('%s') ===", d.getId(), d.getTitle())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Entry Node: %s", d.getEntryNodeId())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Total Nodes: %d", d.getNodes().size())), false);
        return 1;
    }

    private static int startDialogue(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = targetPlayer;
        if (player == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                ctx.getSource().sendFailure(Component.literal("Player must be specified when executed from console"));
                return 0;
            }
        }
        String idStr = StringArgumentType.getString(ctx, "dialogue_id");
        NamespacedId id = NamespacedId.of(idStr);
        try {
            DialogueView view = StoryNpcs.getInstance().getApplicationService().startDialogue(player.getUUID(), id);
            StoryNpcsNetwork.sendOpenDialogue(player, view);
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Started dialogue '%s' for %s", id, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to start dialogue: " + e.getMessage()));
            return 0;
        }
    }

    // Quest Handlers
    private static int editDialogue(CommandContext<CommandSourceStack> ctx) {
        String idStr = StringArgumentType.getString(ctx, "dialogue_id");
        NamespacedId id = NamespacedId.of(idStr);
        var dialogueOpt = StoryNpcs.getInstance().getRegistry().getDialogue(id);
        if (dialogueOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Dialogue not found: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Opening visual dialogue editor for " + id), false);
        return 1;
    }

    private static int listQuests(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        Collection<Quest> quests = reg.getAllQuests();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- Quests (%d loaded) ---", quests.size())), false);
        for (Quest q : quests) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" - %s: '%s' (%s, Objectives: %d)",
                    q.getId(), q.getTitle(), q.getCategory(), q.getObjectives().size())), false);
        }
        return quests.size();
    }

    private static int startQuest(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = targetPlayer;
        if (player == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                ctx.getSource().sendFailure(Component.literal("Player must be specified when executed from console"));
                return 0;
            }
        }
        String idStr = StringArgumentType.getString(ctx, "quest_id");
        NamespacedId id = NamespacedId.of(idStr);
        try {
            StoryNpcs.getInstance().getApplicationService().startQuest(player.getUUID(), id);
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Started quest '%s' for %s", id, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to start quest: " + e.getMessage()));
            return 0;
        }
    }

    private static int completeQuest(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = targetPlayer;
        if (player == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                ctx.getSource().sendFailure(Component.literal("Player must be specified when executed from console"));
                return 0;
            }
        }
        String idStr = StringArgumentType.getString(ctx, "quest_id");
        NamespacedId id = NamespacedId.of(idStr);
        try {
            StoryNpcs.getInstance().getApplicationService().completeQuest(player.getUUID(), id);
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Completed quest '%s' for %s", id, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to complete quest: " + e.getMessage()));
            return 0;
        }
    }

    // Faction Handlers
    private static int listFactions(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        Collection<Faction> factions = reg.getAllFactions();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- Factions (%d loaded) ---", factions.size())), false);
        for (Faction f : factions) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" - %s: '%s' (Default: %d, Hostile: <%d, Friendly: >=%d)",
                    f.getId(), f.getName(), f.getDefaultPoints(), f.getHostileThreshold(), f.getFriendlyThreshold())), false);
        }
        return factions.size();
    }

    private static int setFaction(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = targetPlayer;
        if (player == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                ctx.getSource().sendFailure(Component.literal("Player must be specified when executed from console"));
                return 0;
            }
        }
        String idStr = StringArgumentType.getString(ctx, "faction_id");
        NamespacedId id = NamespacedId.of(idStr);
        int points = IntegerArgumentType.getInteger(ctx, "points");
        try {
            StoryNpcs.getInstance().getApplicationService().setFactionPoints(player.getUUID(), id, points);
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Set faction '%s' points to %d for %s", id, points, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to set faction points: " + e.getMessage()));
            return 0;
        }
    }

    private static int adjustFaction(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = targetPlayer;
        if (player == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                ctx.getSource().sendFailure(Component.literal("Player must be specified when executed from console"));
                return 0;
            }
        }
        String idStr = StringArgumentType.getString(ctx, "faction_id");
        NamespacedId id = NamespacedId.of(idStr);
        int delta = IntegerArgumentType.getInteger(ctx, "delta");
        try {
            StoryNpcs.getInstance().getApplicationService().adjustFactionPoints(player.getUUID(), id, delta);
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Adjusted faction '%s' points by %+d for %s", id, delta, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to adjust faction points: " + e.getMessage()));
            return 0;
        }
    }
}
