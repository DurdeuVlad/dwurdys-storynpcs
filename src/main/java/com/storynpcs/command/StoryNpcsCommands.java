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
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::infoNpc)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteNpc))))
                // Dialogue commands
                .then(Commands.literal("dialogue")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listDialogues))
                        .then(Commands.literal("info")
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::infoDialogue)))
                        .then(Commands.literal("edit")
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::editDialogue)))
                        .then(Commands.literal("start")
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .executes(ctx -> startDialogue(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startDialogue(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                // Quest commands
                .then(Commands.literal("quest")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listQuests))
                        .then(Commands.literal("start")
                                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                                        .executes(ctx -> startQuest(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startQuest(ctx, EntityArgument.getPlayer(ctx, "player"))))))
                        .then(Commands.literal("complete")
                                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                                        .executes(ctx -> completeQuest(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> completeQuest(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                // Faction commands
                .then(Commands.literal("faction")
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listFactions))
                        .then(Commands.literal("set")
                                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("points", IntegerArgumentType.integer())
                                                .executes(ctx -> setFaction(ctx, null))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .executes(ctx -> setFaction(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                        .then(Commands.literal("adjust")
                                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                                .executes(ctx -> adjustFaction(ctx, null))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .executes(ctx -> adjustFaction(ctx, EntityArgument.getPlayer(ctx, "player"))))))))
                // Follower commands
                .then(Commands.literal("follower")
                        .then(Commands.literal("formation")
                                .then(Commands.argument("formation", StringArgumentType.word())
                                        .executes(ctx -> setFollowerFormationCmd(ctx, -1, 2.5))
                                        .then(Commands.argument("slot", IntegerArgumentType.integer())
                                                .executes(ctx -> setFollowerFormationCmd(ctx, IntegerArgumentType.getInteger(ctx, "slot"), 2.5))
                                                .then(Commands.argument("spacing", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.5, 10.0))
                                                        .executes(ctx -> setFollowerFormationCmd(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "slot"),
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "spacing")))))))
                        .then(Commands.literal("state")
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .executes(StoryNpcsCommands::setFollowerStateCmd))));

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

    private static NamespacedId getNamespacedId(CommandContext<CommandSourceStack> ctx, String argName) {
        ResourceLocation loc = ResourceLocationArgument.getId(ctx, argName);
        return NamespacedId.of(loc.getNamespace(), loc.getPath());
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
        NamespacedId id = getNamespacedId(ctx, "npc_id");
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
        NamespacedId id = getNamespacedId(ctx, "npc_id");
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
        NamespacedId id = getNamespacedId(ctx, "dialogue_id");
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
        NamespacedId id = getNamespacedId(ctx, "dialogue_id");
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
        NamespacedId id = getNamespacedId(ctx, "dialogue_id");
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
        NamespacedId id = getNamespacedId(ctx, "quest_id");
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
        NamespacedId id = getNamespacedId(ctx, "quest_id");
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
        NamespacedId id = getNamespacedId(ctx, "faction_id");
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
        NamespacedId id = getNamespacedId(ctx, "faction_id");
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

    private static int setFollowerFormationCmd(CommandContext<CommandSourceStack> ctx, int slot, double spacing) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[StoryNPCs] Command must be executed by a player."));
            return 0;
        }

        String formationStr = StringArgumentType.getString(ctx, "formation");
        FormationType type = FormationType.fromString(formationStr);

        int updated = 0;
        var entities = player.serverLevel().getEntitiesOfClass(
                StoryNpcEntity.class,
                player.getBoundingBox().inflate(64.0),
                npc -> npc.getFollowerRole() != null && npc.getFollowerRole().isOwnedBy(player.getUUID())
        );

        for (StoryNpcEntity npc : entities) {
            FollowerRole role = npc.getFollowerRole();
            role.setFormation(type);
            role.setFormationSlot(slot);
            role.setFormationSpacing(spacing);
            updated++;
        }

        final int count = updated;
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Updated " + count + " followers to " + type.name() + " formation (slot: " + (slot < 0 ? "auto" : slot) + ", spacing: " + spacing + "m)."), true);
        return updated;
    }

    private static int setFollowerStateCmd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[StoryNPCs] Command must be executed by a player."));
            return 0;
        }

        String stateStr = StringArgumentType.getString(ctx, "state").toUpperCase();
        FollowerRole.State state;
        try {
            state = FollowerRole.State.valueOf(stateStr);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("[StoryNPCs] Invalid state: " + stateStr + ". Valid states: FOLLOWING, STAYING, GUARDING"));
            return 0;
        }

        int updated = 0;
        var entities = player.serverLevel().getEntitiesOfClass(
                StoryNpcEntity.class,
                player.getBoundingBox().inflate(64.0),
                npc -> npc.getFollowerRole() != null && npc.getFollowerRole().isOwnedBy(player.getUUID())
        );

        for (StoryNpcEntity npc : entities) {
            FollowerRole role = npc.getFollowerRole();
            role.setState(state);
            updated++;
        }

        final int count = updated;
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Updated " + count + " followers to state " + state.name() + "."), true);
        return updated;
    }
}
