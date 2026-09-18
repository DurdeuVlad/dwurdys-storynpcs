package com.storynpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public final class StoryNpcsCommands {

    private StoryNpcsCommands() {}

    /**
     * Registry-backed tab-completion for definition IDs — admins should never have to
     * memorize or retype 'storynpcs:guard_captain'-style identifiers. Degrades to no
     * suggestions when the registry isn't available (unit tests, early boot).
     */
    private static SuggestionProvider<CommandSourceStack> idSuggestions(
            java.util.function.Function<DefinitionRegistry, java.util.stream.Stream<String>> extractor) {
        return (ctx, builder) -> {
            StoryNpcs mod = StoryNpcs.getInstance();
            if (mod == null || mod.getRegistry() == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(
                    extractor.apply(mod.getRegistry()).sorted().toList(), builder);
        };
    }

    private static final SuggestionProvider<CommandSourceStack> NPC_IDS =
            idSuggestions(reg -> reg.getAllNpcs().stream().map(n -> n.getId().toString()));
    private static final SuggestionProvider<CommandSourceStack> DIALOGUE_IDS =
            idSuggestions(reg -> reg.getAllDialogues().stream().map(d -> d.getId().toString()));
    private static final SuggestionProvider<CommandSourceStack> QUEST_IDS =
            idSuggestions(reg -> reg.getAllQuests().stream().map(q -> q.getId().toString()));
    private static final SuggestionProvider<CommandSourceStack> FACTION_IDS =
            idSuggestions(reg -> reg.getAllFactions().stream().map(f -> f.getId().toString()));

    /** Builds a chat component that runs or suggests a command on click, with a hover hint. */
    private static MutableComponent clickable(String text, String command, ClickEvent.Action action, String hover) {
        return Component.literal(text).withStyle(style -> style
                .withClickEvent(new ClickEvent(action, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("storynpcs")
                .executes(StoryNpcsCommands::sendHelp)
                .then(Commands.literal("help").executes(StoryNpcsCommands::sendHelp))
                .then(Commands.literal("me")
                        .executes(ctx -> showStatus(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> showStatus(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(StoryNpcsCommands::reload))
                // NPC commands
                .then(Commands.literal("npc")
                        .executes(StoryNpcsCommands::sendNpcHelp)
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listNpcs))
                        .then(Commands.literal("info")
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(StoryNpcsCommands::infoNpc)))
                        .then(Commands.literal("spawn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(ctx -> spawnNpc(ctx, null))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> spawnNpc(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("despawn")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> despawnNpc(ctx, null, 128.0))
                                .then(Commands.literal("all")
                                        .executes(ctx -> despawnNpc(ctx, null, 256.0))
                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 512.0))
                                                .executes(ctx -> despawnNpc(ctx, null, com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "radius")))))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(ctx -> despawnNpc(ctx, ResourceLocationArgument.getId(ctx, "npc_id"), 128.0))
                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 512.0))
                                                .executes(ctx -> despawnNpc(ctx,
                                                        ResourceLocationArgument.getId(ctx, "npc_id"),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "radius"))))))
                        .then(Commands.literal("delete")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(StoryNpcsCommands::deleteNpc))))
                // Dialogue commands
                .then(Commands.literal("dialogue")
                        .executes(StoryNpcsCommands::sendDialogueHelp)
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listDialogues))
                        .then(Commands.literal("info")
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(StoryNpcsCommands::infoDialogue)))
                        .then(Commands.literal("edit")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(StoryNpcsCommands::editDialogue)))
                        .then(Commands.literal("start")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(ctx -> startDialogue(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startDialogue(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                // Quest commands
                .then(Commands.literal("quest")
                        .executes(StoryNpcsCommands::sendQuestHelp)
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listQuests))
                        .then(Commands.literal("start")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                                        .suggests(QUEST_IDS)
                                        .executes(ctx -> startQuest(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startQuest(ctx, EntityArgument.getPlayer(ctx, "player"))))))
                        .then(Commands.literal("complete")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                                        .suggests(QUEST_IDS)
                                        .executes(ctx -> completeQuest(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> completeQuest(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                // Faction commands
                .then(Commands.literal("faction")
                        .executes(StoryNpcsCommands::sendFactionHelp)
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listFactions))
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                                        .suggests(FACTION_IDS)
                                        .then(Commands.argument("points", IntegerArgumentType.integer())
                                                .executes(ctx -> setFaction(ctx, null))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .executes(ctx -> setFaction(ctx, EntityArgument.getPlayer(ctx, "player")))))))
                        .then(Commands.literal("adjust")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                                        .suggests(FACTION_IDS)
                                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                                .executes(ctx -> adjustFaction(ctx, null))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .executes(ctx -> adjustFaction(ctx, EntityArgument.getPlayer(ctx, "player"))))))))
                // Follower commands (permission 0: available to players commanding their own hired followers)
                .then(Commands.literal("follower")
                        .executes(StoryNpcsCommands::sendFollowerHelp)
                        .then(Commands.literal("recall")
                                .executes(StoryNpcsCommands::recallFollowers))
                        .then(Commands.literal("formation")
                                .then(Commands.argument("formation", StringArgumentType.word())
                                        .executes(ctx -> setFollowerFormationCmd(ctx, -1, 2.5))
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(-1, 64))
                                                .executes(ctx -> setFollowerFormationCmd(ctx, IntegerArgumentType.getInteger(ctx, "slot"), 2.5))
                                                .then(Commands.argument("spacing", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.5, 16.0))
                                                        .executes(ctx -> setFollowerFormationCmd(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "slot"),
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "spacing")))))))
                        .then(Commands.literal("state")
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .executes(StoryNpcsCommands::setFollowerStateCmd))));

        dispatcher.register(root);
        // Register alias /sn (inherits subcommand permissions from root and executes help when called alone)
        dispatcher.register(Commands.literal("sn").executes(StoryNpcsCommands::sendHelp).redirect(dispatcher.getRoot().getChild("storynpcs")));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Reloading YAML definitions..."), true);

        StoryNpcs mod = StoryNpcs.getInstance();
        if (mod == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Mod instance not initialized"));
            return 0;
        }

        try {
            Path worldDir = source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            Path definitionsDir = worldDir.resolve("storynpcs").resolve("definitions");

            // Atomic reload: Load into staging registry to ensure zero-data-loss on syntax/validation errors
            DefinitionRegistry staging = new DefinitionRegistry();
            com.storynpcs.yaml.YamlDefinitionLoader stagingLoader = new com.storynpcs.yaml.YamlDefinitionLoader(staging);
            var result = stagingLoader.loadDirectory(definitionsDir);

            if (result.isValid()) {
                mod.getRegistry().copyFrom(staging);
                source.sendSuccess(() -> Component.literal("[StoryNPCs] Definitions reloaded successfully: " + result.formatReport()), true);
                return 1;
            } else {
                source.sendFailure(Component.literal("[StoryNPCs] Validation errors during reload (previous definitions retained):\n" + result.formatReport()));
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("[StoryNPCs] Error during reload (previous definitions retained): " + e.getMessage()));
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
            // One click on an entry opens its info + action buttons — no retyping the id
            MutableComponent line = clickable(
                    String.format(" - %s ('%s') [Faction: %s, Dialogue: %s]",
                            npc.getId(), npc.getDisplay().getName(), npc.getFactionId(), npc.getDialogueId()),
                    "/storynpcs npc info " + npc.getId(), ClickEvent.Action.RUN_COMMAND,
                    "Click to view " + npc.getId() + " details and actions");
            ctx.getSource().sendSuccess(() -> line, false);
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
        // Action row — one click instead of retyping the id into spawn/despawn/delete
        if (ctx.getSource().hasPermission(2)) {
            MutableComponent actions = Component.literal(" §7[ ")
                    .append(clickable("§aSpawn Here", "/storynpcs npc spawn " + id,
                            ClickEvent.Action.RUN_COMMAND, "Spawn '" + id + "' at your position"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§eDespawn", "/storynpcs npc despawn " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Despawn in-world '" + id + "' entities (choose radius)"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§cDelete", "/storynpcs npc delete " + id,
                            ClickEvent.Action.SUGGEST_COMMAND, "Delete '" + id + "' definition (confirm with Enter)"))
                    .append(Component.literal(" §7]"));
            ctx.getSource().sendSuccess(() -> actions, false);
        }
        return 1;
    }

    private static int spawnNpc(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        CommandSourceStack source = ctx.getSource();
        NamespacedId id = getNamespacedId(ctx, "npc_id");

        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        if (reg.getNpc(id).isEmpty()) {
            source.sendFailure(Component.literal("[StoryNPCs] NPC definition not found: " + id));
            return 0;
        }

        Vec3 spawnPos = pos;
        if (spawnPos == null) {
            if (source.getEntity() != null) {
                spawnPos = source.getPosition();
            } else {
                source.sendFailure(Component.literal("[StoryNPCs] Position must be specified when executed from console"));
                return 0;
            }
        }

        ServerLevel level = source.getLevel();
        com.storynpcs.entity.StoryNpcEntity entity = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
        if (entity == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Failed to create NPC entity"));
            return 0;
        }

        entity.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
        entity.setDefinitionId(id.toString());
        entity.setStartPosition(entity.blockPosition());
        level.addFreshEntity(entity);

        final Vec3 finalPos = spawnPos;
        source.sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Spawned '%s' at (%.1f, %.1f, %.1f)",
                id, finalPos.x, finalPos.y, finalPos.z)), true);
        return 1;
    }

    private static int deleteNpc(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        boolean deleted = StoryNpcs.getInstance().getApplicationService().deleteNpc(id);
        if (deleted) {
            ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Deleted NPC definition '" + id + "' from registry. To remove in-world entities, use '/storynpcs npc despawn " + id + "'."), true);
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
            MutableComponent line = clickable(
                    String.format(" - %s: '%s' (Entry: %s, Nodes: %d)",
                            d.getId(), d.getTitle(), d.getEntryNodeId(), d.getNodes().size()),
                    "/storynpcs dialogue info " + d.getId(), ClickEvent.Action.RUN_COMMAND,
                    "Click to view " + d.getId() + " details and actions");
            ctx.getSource().sendSuccess(() -> line, false);
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
        if (ctx.getSource().hasPermission(2)) {
            MutableComponent actions = Component.literal(" §7[ ")
                    .append(clickable("§bOpen Editor", "/storynpcs dialogue edit " + id,
                            ClickEvent.Action.RUN_COMMAND, "Open the visual editor for '" + id + "'"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§aStart", "/storynpcs dialogue start " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Start dialogue '" + id + "' (pick a player)"))
                    .append(Component.literal(" §7]"));
            ctx.getSource().sendSuccess(() -> actions, false);
        }
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
            DialogueView view = StoryNpcs.getInstance().getApplicationService().startDialogue(
                    player.getUUID(), id, null,
                    player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ()
            );
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
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] The dialogue editor can only be opened by a player, not the console."));
            return 0;
        }
        // VULN-49: send the editor-open packet so the client actually opens DialogueEditorScreen.
        // The full graph travels with the packet — the client has no definition registry
        // on a dedicated server, so sending only the id would open an empty editor.
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.storynpcs.network.ClientboundDialogueEditorOpenPayload(
                        id.toString(),
                        com.storynpcs.domain.dialogue.DialogueGraphSerde.toJson(dialogueOpt.get())));
        ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Opening visual dialogue editor for '" + id + "'."), false);
        return 1;
    }

    private static int listQuests(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = StoryNpcs.getInstance().getRegistry();
        Collection<Quest> quests = reg.getAllQuests();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- Quests (%d loaded) ---", quests.size())), false);
        for (Quest q : quests) {
            MutableComponent line = clickable(
                    String.format(" - %s: '%s' (%s, Objectives: %d)",
                            q.getId(), q.getTitle(), q.getCategory(), q.getObjectives().size()),
                    "/storynpcs quest start " + q.getId() + " ", ClickEvent.Action.SUGGEST_COMMAND,
                    "Click to start quest " + q.getId() + " (pick a player)");
            ctx.getSource().sendSuccess(() -> line, false);
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
            MutableComponent line = clickable(
                    String.format(" - %s: '%s' (Default: %d, Hostile: <%d, Friendly: >=%d)",
                            f.getId(), f.getName(), f.getDefaultPoints(), f.getHostileThreshold(), f.getFriendlyThreshold()),
                    "/storynpcs faction adjust " + f.getId() + " ", ClickEvent.Action.SUGGEST_COMMAND,
                    "Click to adjust reputation with " + f.getId());
            ctx.getSource().sendSuccess(() -> line, false);
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

        var appService = StoryNpcs.getInstance().getApplicationService();
        int entityIdx = 0;
        for (StoryNpcEntity npc : entities) {
            FollowerRole role = npc.getFollowerRole();
            int finalSlot = slot < 0 ? -1 : (slot + entityIdx);
            if (appService != null) {
                appService.setFollowerFormation(player.getUUID(), NamespacedId.of(npc.getDefinitionId()), role, type, finalSlot, spacing);
            } else {
                role.setFormation(type);
                role.setFormationSlot(finalSlot);
                role.setFormationSpacing(spacing);
            }
            updated++;
            entityIdx++;
        }

        final int count = updated;
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Updated " + count + " followers to " + type.name() + " formation (slot: " + (slot < 0 ? "auto" : slot) + ", spacing: " + spacing + "m)."), false);
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

        var appService = StoryNpcs.getInstance().getApplicationService();
        for (StoryNpcEntity npc : entities) {
            FollowerRole role = npc.getFollowerRole();
            if (appService != null) {
                appService.setFollowerState(player.getUUID(), NamespacedId.of(npc.getDefinitionId()), role, state);
            } else {
                role.setState(state);
            }
            updated++;
        }

        final int count = updated;
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Updated " + count + " followers to state " + state.name() + "."), false);
        return updated;
    }

    private static int despawnNpc(CommandContext<CommandSourceStack> ctx, ResourceLocation targetNpcId, double radius) {
        CommandSourceStack source = ctx.getSource();
        Vec3 origin = source.getPosition();
        AABB box = new AABB(
                origin.x - radius, source.getLevel().getMinBuildHeight(), origin.z - radius,
                origin.x + radius, source.getLevel().getMaxBuildHeight(), origin.z + radius
        );
        List<StoryNpcEntity> entities = source.getLevel().getEntitiesOfClass(StoryNpcEntity.class, box);
        int count = 0;
        for (StoryNpcEntity entity : entities) {
            if (targetNpcId == null || targetNpcId.toString().equals(entity.getDefinitionId())) {
                entity.discard();
                count++;
            }
        }
        final int removedCount = count;
        String targetName = targetNpcId != null ? targetNpcId.toString() : "all";
        source.sendSuccess(() -> Component.literal(String.format("§a[StoryNPCs] Despawned %d in-world '%s' entity(ies) within %.0f blocks.", removedCount, targetName, radius)), true);
        return removedCount;
    }

    private static int recallFollowers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§c[StoryNPCs] Only players can recall followers."));
            return 0;
        }
        List<StoryNpcEntity> followers = player.serverLevel().getEntitiesOfClass(
                StoryNpcEntity.class,
                player.getBoundingBox().inflate(256.0),
                npc -> npc.getFollowerRole() != null && npc.getFollowerRole().isOwnedBy(player.getUUID())
        );
        int count = 0;
        float playerYaw = player.getYRot();
        for (int i = 0; i < followers.size(); i++) {
            StoryNpcEntity follower = followers.get(i);
            FollowerRole role = follower.getFollowerRole();
            FormationType formation = (role != null && role.getFormation() != null) ? role.getFormation() : FormationType.COLUMN;
            int slot = (role != null && role.getFormationSlot() >= 0) ? role.getFormationSlot() : i;
            double spacing = (role != null && role.getFormationSpacing() > 0) ? role.getFormationSpacing() : 2.0;

            var offset = com.storynpcs.domain.role.follower.FormationCalculator.computeOffset(formation, slot, spacing);
            var targetPos = com.storynpcs.domain.role.follower.FormationCalculator.toWorldCoordinates(
                    player.getX(), player.getY(), player.getZ(), playerYaw, offset
            );

            follower.teleportTo(targetPos.x(), targetPos.y(), targetPos.z());
            follower.getNavigation().stop();
            count++;
        }
        final int recalled = count;
        if (recalled == 0) {
            player.sendSystemMessage(Component.literal("§e[StoryNPCs] Recalled 0 followers. Followers must be in loaded chunks within 256 blocks."));
        } else {
            player.sendSystemMessage(Component.literal(String.format("§a[StoryNPCs] Recalled %d follower(s) to your position.", recalled)));
        }
        return recalled;
    }

    /**
     * /storynpcs me — zero-arg self-service status: active quests with n/m objective
     * progress and faction standings. Saves players from asking an admin (or never
     * knowing their progress at all). The [player] variant lets ops inspect anyone.
     */
    private static int showStatus(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = target;
        if (player == null) {
            if (source.getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                source.sendFailure(Component.literal("[StoryNPCs] Specify a player when run from the console."));
                return 0;
            }
        }

        StoryNpcs mod = StoryNpcs.getInstance();
        var repo = mod != null ? mod.getProgressionRepository() : null;
        if (repo == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Progression store is not available."));
            return 0;
        }
        PlayerProgression prog = repo.getOrCreate(player.getUUID());
        DefinitionRegistry reg = mod.getRegistry();

        boolean self = source.getEntity() == player;
        String header = self ? "§6--- Your StoryNPCs Status ---"
                : "§6--- StoryNPCs Status: " + player.getScoreboardName() + " ---";
        source.sendSuccess(() -> Component.literal(header), false);

        // Quests — active states with per-objective n/m progress; completed summarized
        int active = 0;
        int completed = 0;
        List<String> questLines = new java.util.ArrayList<>();
        for (var entry : prog.getQuests().entrySet()) {
            QuestProgressState state = entry.getValue();
            if (state.getStatus() == QuestProgressState.Status.COMPLETED) {
                completed++;
                continue;
            }
            if (state.getStatus() != QuestProgressState.Status.IN_PROGRESS) {
                continue;
            }
            active++;
            StringBuilder sb = new StringBuilder(" §a• ");
            var questOpt = reg != null ? reg.getQuest(entry.getKey()) : java.util.Optional.<Quest>empty();
            if (questOpt.isPresent()) {
                Quest q = questOpt.get();
                sb.append(q.getTitle() != null && !q.getTitle().isBlank() ? q.getTitle() : entry.getKey().toString());
                for (QuestObjective obj : q.getObjectives()) {
                    sb.append(String.format(" §7[%s %d/%d]",
                            obj.getTarget(), state.getCount(obj.getId()), obj.getRequiredCount()));
                }
            } else {
                sb.append(entry.getKey()).append(" §7(definition removed)");
            }
            questLines.add(sb.toString());
        }
        final int fActive = active;
        final int fCompleted = completed;
        source.sendSuccess(() -> Component.literal(
                String.format("§eQuests: %d active, %d completed", fActive, fCompleted)), false);
        for (String line : questLines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        if (questLines.isEmpty()) {
            source.sendSuccess(() -> Component.literal(" §7No quests in progress — talk to an NPC."), false);
        }

        // Factions — every registered faction with the player's points + standing color
        var factions = reg != null ? reg.getAllFactions() : java.util.List.<Faction>of();
        if (!factions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eFactions:"), false);
            for (Faction f : factions) {
                int pts = prog.getFactionScore(f.getId(), f.getDefaultPoints());
                var standing = f.getStandingForPoints(pts);
                String color = switch (standing) {
                    case FRIENDLY -> "§a";
                    case HOSTILE -> "§c";
                    default -> "§e";
                };
                String line = String.format(" %s%s §7- %d pts (%s)", color, f.getName(), pts, standing.name());
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    private static int sendHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- Dwurdy's StoryNPCs Help ---§r\n" +
                "§e/storynpcs me [player] §7- Your quests & faction standing\n" +
                "§e/storynpcs reload §7- Reload YAML definitions\n" +
                "§e/storynpcs npc <list|info|spawn|despawn|delete> §7- Manage NPCs\n" +
                "§e/storynpcs dialogue <list|info|edit|start> §7- Manage Dialogues\n" +
                "§e/storynpcs quest <list|start|complete> §7- Manage Quests\n" +
                "§e/storynpcs faction <list|set|adjust> §7- Manage Factions\n" +
                "§e/storynpcs follower <recall|formation|state> §7- Command Followers\n" +
                "§7Alias: /sn · IDs tab-complete · list entries are clickable"), false);
        return 1;
    }

    private static int sendNpcHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs NPC Commands ---§r\n" +
                "§e/storynpcs npc list §7- List all registered NPC definitions\n" +
                "§e/storynpcs npc info <npc_id> §7- View details of an NPC definition\n" +
                "§e/storynpcs npc spawn <npc_id> [pos] §7- Spawn an NPC into the world\n" +
                "§e/storynpcs npc despawn [npc_id] [radius] §7- Remove spawned NPCs from the world\n" +
                "§e/storynpcs npc delete <npc_id> §7- Delete NPC definition from registry"), false);
        return 1;
    }

    private static int sendDialogueHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Dialogue Commands ---§r\n" +
                "§e/storynpcs dialogue list §7- List loaded dialogue graphs\n" +
                "§e/storynpcs dialogue info <dialogue_id> §7- View dialogue graph structure\n" +
                "§e/storynpcs dialogue edit <dialogue_id> §7- Open dialogue graph editor GUI\n" +
                "§e/storynpcs dialogue start <dialogue_id> [player] §7- Initiate dialogue session"), false);
        return 1;
    }

    private static int sendQuestHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Quest Commands ---§r\n" +
                "§e/storynpcs quest list §7- List all registered quests\n" +
                "§e/storynpcs quest start <quest_id> [player] §7- Start a quest for a player\n" +
                "§e/storynpcs quest complete <quest_id> [player] §7- Complete a quest for a player"), false);
        return 1;
    }

    private static int sendFactionHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Faction Commands ---§r\n" +
                "§e/storynpcs faction list §7- List all factions\n" +
                "§e/storynpcs faction set <faction_id> <points> [player] §7- Set player faction reputation\n" +
                "§e/storynpcs faction adjust <faction_id> <delta> [player] §7- Adjust player faction reputation"), false);
        return 1;
    }

    private static int sendFollowerHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Follower Commands ---§r\n" +
                "§e/storynpcs follower recall §7- Summon owned followers to your position\n" +
                "§e/storynpcs follower formation <COLUMN|WEDGE|ROW|CIRCLE> [slot] [spacing] §7- Change formation pattern\n" +
                "§e/storynpcs follower state <FOLLOWING|STAYING|GUARDING> §7- Change follower tactical state"), false);
        return 1;
    }
}
