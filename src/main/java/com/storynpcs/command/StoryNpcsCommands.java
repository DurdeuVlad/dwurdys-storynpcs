package com.storynpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.QuestCompletionResult;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.item.StoryNpcsItems;
import java.util.ArrayList;
import java.util.NoSuchElementException;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class StoryNpcsCommands {

    private StoryNpcsCommands() {}

    /**
     * Resolves the mod instance from the executing command's level — every
     * handler's {@link CommandContext} carries a {@link CommandSourceStack}
     * bound to a loaded {@link net.minecraft.server.level.ServerLevel}, which
     * holds the mod handle installed at level load ({@link com.storynpcs.StoryNpcsAccess}).
     */
    private static StoryNpcs mod(CommandContext<CommandSourceStack> ctx) {
        return com.storynpcs.StoryNpcsAccess.require(ctx.getSource().getLevel());
    }

    /** Nullable variant for paths that degrade gracefully when the world isn't attached yet. */
    private static StoryNpcs modOrNull(CommandContext<CommandSourceStack> ctx) {
        return com.storynpcs.StoryNpcsAccess.mod(ctx.getSource().getLevel());
    }

    /**
     * Registry-backed tab-completion for definition IDs — admins should never have to
     * memorize or retype 'storynpcs:guard_captain'-style identifiers. Degrades to no
     * suggestions when the registry isn't available (unit tests, early boot).
     */
    private static SuggestionProvider<CommandSourceStack> idSuggestions(
            java.util.function.Function<DefinitionRegistry, java.util.stream.Stream<String>> extractor) {
        return (ctx, builder) -> {
            StoryNpcs mod = modOrNull(ctx);
            if (mod == null || mod.getRegistry() == null) {
                return builder.buildFuture();
            }
            // CommandSuggestionEngine filters tab-invalid tokens before matching.
            var candidates = CommandSuggestionEngine.suggest(
                    CommandSuggestionEngine.ArgKind.NPC_ID, builder.getRemaining(),
                    () -> extractor.apply(mod.getRegistry()).toList());
            return SharedSuggestionProvider.suggest(candidates, builder);
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
                .then(Commands.literal("quickstart")
                        .requires(source -> source.hasPermission(2))
                        .executes(StoryNpcsCommands::quickstart))
                // P6-3 transport: player-facing list + server-evaluated transfer
                .then(Commands.literal("transport")
                        .executes(StoryNpcsCommands::listTransports)
                        .then(Commands.argument("location_id", ResourceLocationArgument.id())
                                .executes(StoryNpcsCommands::transport)))
                // P5-5 shared-party teams: self-service create/invite/join/leave;
                // owner transfer is self-service for the owner, admin otherwise.
                .then(Commands.literal("team")
                        .executes(StoryNpcsCommands::teamStatus)
                        .then(Commands.literal("create").executes(StoryNpcsCommands::teamCreate))
                        .then(Commands.literal("invite")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(StoryNpcsCommands::teamInvite)))
                        .then(Commands.literal("join")
                                .then(Commands.argument("owner", EntityArgument.player())
                                        .executes(StoryNpcsCommands::teamJoin)))
                        .then(Commands.literal("leave").executes(StoryNpcsCommands::teamLeave))
                        .then(Commands.literal("owner")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(StoryNpcsCommands::teamOwner)))
                        .then(Commands.literal("share")
                                .then(Commands.argument("faction_points", BoolArgumentType.bool())
                                        .executes(StoryNpcsCommands::teamShare))))
                // P8-1 templates: list + instantiate-into-NPC through saveNpc
                .then(Commands.literal("template")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listTemplates))
                        .then(Commands.literal("apply")
                                .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .executes(StoryNpcsCommands::applyTemplate))))
                        // P9-3 clone parity: capture a definition into a template,
                        // spawn a template directly, spawn a bounded grid.
                        .then(Commands.literal("capture")
                                .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .executes(StoryNpcsCommands::captureTemplate))))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                        .executes(ctx -> spawnTemplate(ctx, null, null))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> spawnTemplate(ctx,
                                                        Vec3Argument.getVec3(ctx, "pos"), null))
                                                .then(Commands.argument("display_name", StringArgumentType.greedyString())
                                                        .executes(ctx -> spawnTemplate(ctx,
                                                                Vec3Argument.getVec3(ctx, "pos"),
                                                                StringArgumentType.getString(ctx, "display_name")))))))
                        .then(Commands.literal("grid")
                                .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("length", IntegerArgumentType.integer(1, 16))
                                                .then(Commands.argument("width", IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> gridTemplate(ctx, null))
                                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                                .executes(ctx -> gridTemplate(ctx,
                                                                        Vec3Argument.getVec3(ctx, "pos"))))))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteTemplate))))
                // P8-1 spawners: list/info plus place/delete through canonical ops
                .then(Commands.literal("spawner")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listSpawners))
                        .then(Commands.literal("info")
                                .then(Commands.argument("spawner_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::spawnerInfo)))
                        .then(Commands.literal("place")
                                .then(Commands.argument("spawner_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                                .executes(StoryNpcsCommands::placeSpawner))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("spawner_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteSpawner))))
                // P8-3 world tools: typed defs, preview-then-confirm activation
                .then(Commands.literal("worldtool")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listWorldTools))
                        .then(Commands.literal("show")
                                .then(Commands.argument("tool_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::showWorldTool)))
                        .then(Commands.literal("activate")
                                .then(Commands.argument("tool_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                .executes(ctx -> activateWorldTool(ctx,
                                                        ResourceLocationArgument.getId(ctx, "tool_id"),
                                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos")))))))
                // P8-4 recipes: carpentry defs, canonical delete, matcher dry-run
                .then(Commands.literal("recipe")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listRecipes))
                        .then(Commands.literal("info")
                                .then(Commands.argument("recipe_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::recipeInfo)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("recipe_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteRecipe))))
                // P8-5 scenes/transforms/natural spawns/links/timers
                .then(Commands.literal("scene")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listScenes))
                        .then(Commands.literal("info")
                                .then(Commands.argument("scene_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::sceneInfo)))
                        .then(Commands.literal("start")
                                .then(Commands.argument("scene_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::startScene)))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("scene_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::cancelScene)))
                        .then(Commands.literal("cancelall")
                                .executes(StoryNpcsCommands::cancelAllScenes))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("scene_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteScene))))
                .then(Commands.literal("transform")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listTransforms))
                        .then(Commands.literal("apply")
                                .then(Commands.argument("rule_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .executes(StoryNpcsCommands::applyTransform))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("rule_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteTransform))))
                .then(Commands.literal("naturalspawn")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listNaturalSpawns))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("rule_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteNaturalSpawn))))
                // P9-3 config parity: runtime tunables through canonical ConfigTransaction
                .then(Commands.literal("config")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("get")
                                .executes(StoryNpcsCommands::configList)
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(TUNABLE_KEYS)
                                        .executes(StoryNpcsCommands::configGet)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(TUNABLE_KEYS)
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(StoryNpcsCommands::configSet)))))
                // P9-4 remote admin ops (target SPacketRemote*) — entity-uuid
                // targets, loaded+same-level guard, audited.
                .then(Commands.literal("remote")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list")
                                .executes(ctx -> remoteList(ctx, 64))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> remoteList(ctx,
                                                IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(Commands.literal("freeze")
                                .then(Commands.argument("entity_uuid", net.minecraft.commands.arguments.UuidArgument.uuid())
                                        .executes(StoryNpcsCommands::remoteFreeze)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("entity_uuid", net.minecraft.commands.arguments.UuidArgument.uuid())
                                        .executes(StoryNpcsCommands::remoteDelete)))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("entity_uuid", net.minecraft.commands.arguments.UuidArgument.uuid())
                                        .executes(StoryNpcsCommands::remoteReset)))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("entity_uuid", net.minecraft.commands.arguments.UuidArgument.uuid())
                                        .executes(StoryNpcsCommands::remoteTeleport))))
                // P9-4 player-data administration (target SPacketPlayerData*) —
                // SELF scope for own data, ADMIN (perm 2) for cross-player.
                .then(Commands.literal("playerdata")
                        .then(Commands.literal("read")
                                .executes(ctx -> playerDataRead(ctx, null))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> playerDataRead(ctx,
                                                EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("clear")
                                .executes(ctx -> playerDataClear(ctx, null))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> playerDataClear(ctx,
                                                EntityArgument.getPlayer(ctx, "player"))))))
                // P8-6 custom-GUI/overlay surfaces
                .then(Commands.literal("layout")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listGuiLayouts))
                        .then(Commands.literal("show")
                                .then(Commands.argument("layout_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::showGuiLayout)))
                        .then(Commands.literal("preview")
                                .then(Commands.argument("layout_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::previewGuiLayout)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("layout_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteGuiLayout))))
                .then(Commands.literal("preset")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listModelPresets))
                        .then(Commands.literal("show")
                                .then(Commands.argument("preset_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::showModelPreset)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("preset_id", ResourceLocationArgument.id())
                                        .executes(StoryNpcsCommands::deleteModelPreset))))
                .then(Commands.literal("overlay")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listOverlays))
                        .then(Commands.literal("show")
                                .then(Commands.argument("layout_id", ResourceLocationArgument.id())
                                        .then(Commands.argument("element_name", StringArgumentType.word())
                                                .then(Commands.argument("duration_ticks", IntegerArgumentType.integer(1))
                                                        .executes(StoryNpcsCommands::showOverlay))))))
                .then(Commands.literal("link")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("actor_uuid", StringArgumentType.word())
                                .then(Commands.argument("target_uuid", StringArgumentType.word())
                                        .executes(StoryNpcsCommands::linkNpc))))
                .then(Commands.literal("unlink")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("actor_uuid", StringArgumentType.word())
                                .executes(StoryNpcsCommands::unlinkNpc)))
                .then(Commands.literal("timer")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listTimers))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument("actor_uuid", StringArgumentType.word())
                                        .then(Commands.argument("delay_ticks", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("period_ticks", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("event_id", StringArgumentType.word())
                                                                .executes(StoryNpcsCommands::scheduleTimer))))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("timer_id", StringArgumentType.word())
                                        .executes(StoryNpcsCommands::cancelTimer))))
                // P11-1 import: dry-run by default; `apply` executes with rollback
                .then(Commands.literal("import")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("package", StringArgumentType.word())
                                .executes(ctx -> runImport(ctx, "skip", true))
                                .then(Commands.argument("policy", StringArgumentType.word())
                                        .suggests((ctx, sb) -> sb.suggest("skip").suggest("fail")
                                                .suggest("replace").suggest("rename").buildFuture())
                                        .executes(ctx -> runImport(ctx,
                                                StringArgumentType.getString(ctx, "policy"), true))
                                        .then(Commands.literal("apply")
                                                .executes(ctx -> runImport(ctx,
                                                        StringArgumentType.getString(ctx, "policy"), false))))))
                // P2-2 store manifest: live view of the 18-store persistence map
                .then(Commands.literal("stores")
                        .requires(source -> source.hasPermission(2))
                        .executes(StoryNpcsCommands::listStores))
                // P10-2 authoring: validate a patch plan against the live schema bundle
                .then(Commands.literal("author")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("validate")
                                .then(Commands.argument("plan", StringArgumentType.word())
                                        .executes(StoryNpcsCommands::validatePatchPlan))))
                // NPC commands
                .then(Commands.literal("npc")
                        .executes(StoryNpcsCommands::sendNpcHelp)
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listNpcs))
                        .then(Commands.literal("info")
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(StoryNpcsCommands::infoNpc)))
                        .then(Commands.literal("create")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .executes(ctx -> createNpc(ctx, null))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> createNpc(ctx, StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("spawn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(ctx -> spawnNpc(ctx, null))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> spawnNpc(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("fake")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("entity_type", ResourceLocationArgument.id())
                                        .suggests(net.minecraft.commands.synchronization.SuggestionProviders.SUMMONABLE_ENTITIES)
                                        .executes(StoryNpcsCommands::spawnFakeLiving)))
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
                        .then(Commands.literal("teleport")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                .executes(ctx -> teleportNpc(ctx,
                                                        ResourceLocationArgument.getId(ctx, "npc_id"),
                                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos"))))))
                        // P9-3: reset start/home position + respawn parity
                        .then(Commands.literal("home")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(StoryNpcsCommands::setNpcHome)))
                        .then(Commands.literal("respawn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(StoryNpcsCommands::respawnNpc)))
                        .then(Commands.literal("path")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("list")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .executes(ctx -> pathList(ctx, ResourceLocationArgument.getId(ctx, "npc_id")))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                        .executes(ctx -> pathMutate(ctx,
                                                                ResourceLocationArgument.getId(ctx, "npc_id"),
                                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos"),
                                                                PathOp.ADD, -1, null)))))
                                .then(Commands.literal("move")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                                .executes(ctx -> pathMutate(ctx,
                                                                        ResourceLocationArgument.getId(ctx, "npc_id"),
                                                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos"),
                                                                        PathOp.MOVE,
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"),
                                                                        null))))))
                                .then(Commands.literal("delete")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                        .executes(ctx -> pathMutate(ctx,
                                                                ResourceLocationArgument.getId(ctx, "npc_id"),
                                                                null, PathOp.DELETE,
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"),
                                                                null)))))
                                .then(Commands.literal("mode")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("value", StringArgumentType.string())
                                                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                                java.util.List.of("loop", "ping_pong", "once"), b))
                                                        .executes(ctx -> pathMutate(ctx,
                                                                ResourceLocationArgument.getId(ctx, "npc_id"),
                                                                null, PathOp.MODE, -1,
                                                                StringArgumentType.getString(ctx, "value"))))))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .executes(ctx -> pathMutate(ctx,
                                                        ResourceLocationArgument.getId(ctx, "npc_id"),
                                                        null, PathOp.CLEAR, -1, null)))))
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("name")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.literal("random")
                                                        .then(Commands.argument("culture", ResourceLocationArgument.id())
                                                                .suggests((ctx, builder) -> {
                                                                    var mod = mod(ctx);
                                                                    var server = ctx.getSource().getServer();
                                                                    var svc = mod != null && server != null
                                                                            ? mod.getNameGenerationService(server) : null;
                                                                    return net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                                            svc != null ? svc.cultures().stream()
                                                                                    .map(com.storynpcs.domain.common.NamespacedId::asString)
                                                                                    .toList() : java.util.List.of(),
                                                                            builder);
                                                                })
                                                                .executes(StoryNpcsCommands::randomizeNpcName)))
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(StoryNpcsCommands::setNpcName))))
                                .then(Commands.literal("title")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(StoryNpcsCommands::setNpcTitle))))
                                .then(Commands.literal("skin")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("texture", StringArgumentType.string())
                                                        .executes(StoryNpcsCommands::setNpcSkin))))
                                .then(Commands.literal("health")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 100000.0))
                                                        .executes(StoryNpcsCommands::setNpcHealth))))
                                .then(Commands.literal("damage")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 10000.0))
                                                        .executes(StoryNpcsCommands::setNpcDamage))))
                                .then(Commands.literal("speed")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.01, 5.0))
                                                        .executes(StoryNpcsCommands::setNpcSpeed))))
                                .then(Commands.literal("range")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 256))
                                                        .executes(StoryNpcsCommands::setNpcRange))))
                                .then(Commands.literal("movement")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("standing", "wandering", "pathing"), b))
                                                        .executes(StoryNpcsCommands::setNpcMovement))))
                                .then(Commands.literal("stance")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("guard", "passive", "neutral", "aggressive", "evasive"), b))
                                                        .executes(StoryNpcsCommands::setNpcStance))))
                                // P9-3 mark/visibility parity on the definition surface
                                .then(Commands.literal("marks")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.literal("clear")
                                                        .executes(ctx -> setNpcMarks(ctx, null)))
                                                .then(Commands.argument("color", StringArgumentType.word())
                                                        .executes(ctx -> setNpcMarks(ctx,
                                                                StringArgumentType.getString(ctx, "color"))))))
                                .then(Commands.literal("visibility")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("mode", IntegerArgumentType.integer(0, 2))
                                                        .executes(StoryNpcsCommands::setNpcVisibility))))
                                .then(Commands.literal("dialogue")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                                        .suggests(DIALOGUE_IDS)
                                                        .executes(StoryNpcsCommands::setNpcDialogue))))
                                .then(Commands.literal("variant")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                                List.of("humanoid", "alex", "classic_64x32",
                                                                        "golem", "flying", "dragon", "slime",
                                                                        "crystal", "pony"), b))
                                                        .executes(StoryNpcsCommands::setNpcVariant))))
                                .then(Commands.literal("part")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("part", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                                List.of("beard", "ears", "horns", "snout",
                                                                        "tail", "wings", "fin", "skirt",
                                                                        "eyes", "clear"), b))
                                                        .then(Commands.argument("type", IntegerArgumentType.integer(0, 9))
                                                                .then(Commands.argument("color", StringArgumentType.word())
                                                                        .then(Commands.argument("behavior", StringArgumentType.word())
                                                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                                                        List.of("none", "follow_head", "animated"), b))
                                                                                .executes(StoryNpcsCommands::setNpcPart))))
                                                        .executes(StoryNpcsCommands::clearNpcPart))))
                                .then(Commands.literal("faction")
                                        .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                                .suggests(NPC_IDS)
                                                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                                                        .suggests(FACTION_IDS)
                                                        .executes(StoryNpcsCommands::setNpcFaction)))))
                        .then(Commands.literal("emote")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        List.of("none", "aim", "bow", "crawl", "dance",
                                                                "hug", "no", "point", "wave", "yes"), b))
                                                .executes(StoryNpcsCommands::playNpcEmote)
                                                .then(Commands.argument("duration", IntegerArgumentType.integer(1, 1200))
                                                        .executes(StoryNpcsCommands::playNpcEmote)))))
                        .then(Commands.literal("delete")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(StoryNpcsCommands::deleteNpc)))
                        .then(npcRuleCommands())
                        .then(npcAbilityCommands())
                        .then(npcTradeCommands())
                        .then(npcBankCommands()))
                // Dialogue commands
                .then(Commands.literal("dialogue")
                        .executes(StoryNpcsCommands::sendDialogueHelp)
                        .then(Commands.literal("list").executes(StoryNpcsCommands::listDialogues))
                        .then(Commands.literal("info")
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(StoryNpcsCommands::infoDialogue)))
                        .then(Commands.literal("create")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .executes(ctx -> createDialogue(ctx, null))
                                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                                .executes(ctx -> createDialogue(ctx, StringArgumentType.getString(ctx, "title"))))))
                        .then(Commands.literal("edit")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(StoryNpcsCommands::editDialogue)))
                        .then(Commands.literal("delete")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(StoryNpcsCommands::deleteDialogue)))
                        .then(Commands.literal("start")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .executes(ctx -> startDialogue(ctx, null))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> startDialogue(ctx, EntityArgument.getPlayer(ctx, "player"))))))
                        // P9-3 dialog read-marker parity (target /noppes dialog read|unread)
                        .then(Commands.literal("markread")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> dialogueMarkRead(ctx, true)))))
                        .then(Commands.literal("unmarkread")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("dialogue_id", ResourceLocationArgument.id())
                                        .suggests(DIALOGUE_IDS)
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> dialogueMarkRead(ctx, false))))))
                // Quest commands
                .then(questCommands())
                // Faction commands
                .then(factionCommands())
                .then(mailCommands())
                .then(schemaCommands())
                .then(panelCommands())
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
                                        .executes(StoryNpcsCommands::setFollowerStateCmd)))
                        // P9-3 owner parity: report / reassign loaded follower owners
                        .then(Commands.literal("owner")
                                .executes(StoryNpcsCommands::followerOwnerReport)
                                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                                        .suggests(NPC_IDS)
                                        .executes(ctx -> followerOwnerReport(ctx))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> followerOwnerSet(ctx,
                                                        EntityArgument.getPlayer(ctx, "player")))))));

        dispatcher.register(root);
        // Register alias /sn (inherits subcommand permissions from root and executes help when called alone)
        dispatcher.register(Commands.literal("sn").executes(StoryNpcsCommands::sendHelp).redirect(dispatcher.getRoot().getChild("storynpcs")));
    }

    /**
     * The `schema` subtree (issue #149, parity for `/noppes schema`): list
     * available schematics, start a bounded build, stop, and report progress.
     * Builds run through {@link com.storynpcs.service.SchematicBuildService}
     * at a per-tick budget — the command never places blocks synchronously.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> schemaCommands() {
        return Commands.literal("schema")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(StoryNpcsCommands::schemaList))
                .then(Commands.literal("info").executes(StoryNpcsCommands::schemaInfo))
                .then(Commands.literal("stop").executes(StoryNpcsCommands::schemaStop))
                .then(Commands.literal("build")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> schemaBuild(ctx, 0))
                                        .then(Commands.argument("rotation",
                                                        IntegerArgumentType.integer(0, 3))
                                                .executes(ctx -> schemaBuild(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "rotation")))))));
    }

    private static int schemaList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var names = com.storynpcs.domain.schematic.SchematicStore.list(source.getServer());
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[StoryNPCs] No schematics available (bundled assets should ship in the mod"
                            + " jar; creator files go in config/storynpcs/schematics/)."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "[StoryNPCs] " + names.size() + " schematic(s): " + String.join(", ", names)), false);
        return names.size();
    }

    private static int schemaInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        StoryNpcs mod = modOrNull(ctx);
        var status = mod != null ? mod.getSchematicBuildService().status()
                : java.util.List.<com.storynpcs.service.SchematicBuildService.BuildStatus>of();
        var results = mod != null ? mod.getSchematicBuildService().recentResults()
                : java.util.List.<com.storynpcs.service.SchematicBuildService.BuildResult>of();
        for (var s : status) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "[StoryNPCs] %s in %s — %d/%d placed, %d skipped (unloaded), %d unresolved",
                    s.name(), s.dimension(), s.placed(), s.total(), s.skippedUnloaded(),
                    s.unresolved())), false);
        }
        for (var r : results) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "[StoryNPCs] done: %s in %s — %d/%d placed, %d skipped (unloaded),"
                            + " %d unresolved, %d block-entity payload(s) applied,"
                            + " %d block-entity payload(s) skipped",
                    r.name(), r.dimension(), r.placed(), r.total(), r.skippedUnloaded(),
                    r.unresolved(), r.blockEntitiesApplied(), r.blockEntitiesSkipped())), false);
        }
        if (status.isEmpty() && results.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[StoryNPCs] No active or recent schematic builds."), false);
            return 1;
        }
        return status.size() + results.size();
    }

    private static int schemaStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        StoryNpcs mod = modOrNull(ctx);
        int stopped = mod != null ? mod.getSchematicBuildService().stop(source.getLevel()) : 0;
        int finalStopped = stopped;
        source.sendSuccess(() -> Component.literal(
                "[StoryNPCs] Stopped " + finalStopped + " active build(s) in this dimension."), true);
        return stopped;
    }

    private static int schemaBuild(CommandContext<CommandSourceStack> ctx, int quarterTurns) {
        CommandSourceStack source = ctx.getSource();
        StoryNpcs mod = modOrNull(ctx);
        if (mod == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Mod services unavailable."));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        var result = com.storynpcs.domain.schematic.SchematicStore.load(source.getServer(), name);
        if (result.schematic().isEmpty()) {
            source.sendFailure(Component.literal("[StoryNPCs] " + result.error()));
            return 0;
        }
        var schematic = result.schematic().get();
        var origin = net.minecraft.core.BlockPos.containing(Vec3Argument.getVec3(ctx, "pos"));
        var rejection = mod.getSchematicBuildService().startBuild(
                source.getLevel(), schematic, origin, quarterTurns);
        if (rejection.isPresent()) {
            source.sendFailure(Component.literal("[StoryNPCs] " + rejection.get()));
            return 0;
        }
        for (String diagnostic : schematic.diagnostics()) {
            source.sendSuccess(() -> Component.literal("[StoryNPCs] §7" + diagnostic), false);
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Building '%s' (%dx%dx%d) at %s — budgeted per tick.",
                schematic.name(), schematic.width(), schematic.height(), schematic.length(),
                origin.toShortString())), true);
        return 1;
    }

    /**
     * The `panel` subtree (issue #150 — player-facing screens): opens the
     * server-issued panel views for quest log, factions, mail, and transport.
     * Permission 0 — a player only ever sees their own progression data, and
     * every panel commit is re-authorized by the server session.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> panelCommands() {
        return Commands.literal("panel")
                .then(Commands.literal("quests")
                        .executes(ctx -> openPanel(ctx, com.storynpcs.service.PlayerPanelViews.PANEL_QUEST_LOG)))
                .then(Commands.literal("factions")
                        .executes(ctx -> openPanel(ctx, com.storynpcs.service.PlayerPanelViews.PANEL_FACTIONS)))
                .then(Commands.literal("mail")
                        .executes(ctx -> openPanel(ctx, com.storynpcs.service.PlayerPanelViews.PANEL_MAIL)))
                .then(Commands.literal("transport")
                        .executes(ctx -> openPanel(ctx, com.storynpcs.service.PlayerPanelViews.PANEL_TRANSPORT)));
    }

    private static int openPanel(CommandContext<CommandSourceStack> ctx, String panel) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("[StoryNPCs] Panels open for players only."));
            return 0;
        }
        com.storynpcs.network.StoryNpcsNetwork.sendPlayerPanel(player, panel);
        return 1;
    }

    /**
     * The `quest` subtree, built imperatively — the nested-brace registration style
     * used above is too error-prone at this depth (an earlier draft mis-attached
     * argument nodes). Reads, lifecycle, and authoring subcommands in one place.
     */
    /**
     * Free-form quest target argument. Unlike {@link StringArgumentType#word()},
     * accepts ':' so unquoted ids like {@code minecraft:zombie} parse as one token;
     * quoted strings allow spaces for location descriptions like {@code "market square"}.
     */
    public static final class TargetArgument implements ArgumentType<String> {
        private static final TargetArgument INSTANCE = new TargetArgument();

        public static TargetArgument target() {
            return INSTANCE;
        }

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) {
                char quote = reader.read();
                return reader.readStringUntil(quote);
            }
            int start = reader.getCursor();
            while (reader.canRead() && isTargetChar(reader.peek())) {
                reader.skip();
            }
            String value = reader.getString().substring(start, reader.getCursor());
            if (value.isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                        .createWithContext(reader, "Expected a target value");
            }
            return value;
        }

        private static boolean isTargetChar(char c) {
            return c >= '0' && c <= '9'
                    || c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c == '_' || c == '-' || c == '.' || c == '+'
                    || c == ':' || c == '/' || c == ',';
        }

        @Override
        public java.util.Collection<String> getExamples() {
            return java.util.List.of("minecraft:zombie", "minecraft:diamond", "\"town square\"");
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> questCommands() {
        var quest = Commands.literal("quest")
                .executes(StoryNpcsCommands::sendQuestHelp)
                .then(Commands.literal("list").executes(StoryNpcsCommands::listQuests))
                .then(Commands.literal("info")
                        .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                                .suggests(QUEST_IDS)
                                .executes(StoryNpcsCommands::infoQuest)));

        var questIdStart = Commands.argument("quest_id", ResourceLocationArgument.id())
                .suggests(QUEST_IDS)
                .executes(ctx -> startQuest(ctx, null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> startQuest(ctx, EntityArgument.getPlayer(ctx, "player"))));
        quest.then(Commands.literal("start").requires(s -> s.hasPermission(2)).then(questIdStart));

        var questIdComplete = Commands.argument("quest_id", ResourceLocationArgument.id())
                .suggests(QUEST_IDS)
                .executes(ctx -> completeQuest(ctx, null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> completeQuest(ctx, EntityArgument.getPlayer(ctx, "player"))));
        quest.then(Commands.literal("complete").requires(s -> s.hasPermission(2)).then(questIdComplete));

        // Explicit reset — reopens a completed RESET-type quest (issue #8 parity:
        // quests whose repeat policy requires an operator reset).
        var questIdReset = Commands.argument("quest_id", ResourceLocationArgument.id())
                .suggests(QUEST_IDS)
                .executes(ctx -> resetQuest(ctx, null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> resetQuest(ctx, EntityArgument.getPlayer(ctx, "player"))));
        quest.then(Commands.literal("reset").requires(s -> s.hasPermission(2)).then(questIdReset));

        // P9-3: objective report + direct progress set (target /noppes quest)
        var questIdObjectives = Commands.argument("quest_id", ResourceLocationArgument.id())
                .suggests(QUEST_IDS)
                .executes(ctx -> reportQuestObjectives(ctx, null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> reportQuestObjectives(ctx, EntityArgument.getPlayer(ctx, "player"))));
        quest.then(Commands.literal("objectives").requires(s -> s.hasPermission(2)).then(questIdObjectives));

        var questIdProgress = Commands.argument("quest_id", ResourceLocationArgument.id())
                .suggests(QUEST_IDS)
                .then(Commands.argument("objective_id", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(ctx -> setQuestProgress(ctx, null))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> setQuestProgress(ctx, EntityArgument.getPlayer(ctx, "player"))))));
        quest.then(Commands.literal("progress").requires(s -> s.hasPermission(2)).then(questIdProgress));

        // Authoring — create / set / objective / reward (issue #17)
        var questIdCreate = Commands.argument("quest_id", ResourceLocationArgument.id())
                .executes(ctx -> createQuest(ctx, null))
                .then(Commands.argument("title", StringArgumentType.greedyString())
                        .executes(ctx -> createQuest(ctx, StringArgumentType.getString(ctx, "title"))));
        quest.then(Commands.literal("create").requires(s -> s.hasPermission(2)).then(questIdCreate));

        var questIdSet = Commands.argument("quest_id", ResourceLocationArgument.id()).suggests(QUEST_IDS);
        questIdSet.then(Commands.literal("description")
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(ctx -> setQuestField(ctx, "description"))));
        questIdSet.then(Commands.literal("category")
                .then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> setQuestField(ctx, "category"))));
        questIdSet.then(Commands.literal("repeatType")
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("ONCE", "NORMAL", "REPEATABLE", "DAILY", "WEEKLY", "RESET", "INSTANT"), b))
                        .executes(ctx -> setQuestField(ctx, "repeatType"))));
        quest.then(Commands.literal("set").requires(s -> s.hasPermission(2)).then(questIdSet));

        var objectiveAdd = Commands.literal("add")
                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                        .suggests(QUEST_IDS)
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        List.of("KILL_ENTITY", "COLLECT_ITEM", "VISIT_LOCATION", "TALK_TO_NPC", "CUSTOM"), b))
                                .then(Commands.argument("target", TargetArgument.target())
                                        .then(Commands.argument("requiredCount", IntegerArgumentType.integer(1, 100000))
                                                .executes(StoryNpcsCommands::addQuestObjective)))));
        var objectiveRemove = Commands.literal("remove")
                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                        .suggests(QUEST_IDS)
                        .then(Commands.argument("objective_id", StringArgumentType.word())
                                .executes(StoryNpcsCommands::removeQuestObjective)));
        quest.then(Commands.literal("objective").requires(s -> s.hasPermission(2))
                .then(objectiveAdd).then(objectiveRemove));

        var rewardAdd = Commands.literal("add")
                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                        .suggests(QUEST_IDS)
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        List.of("EXPERIENCE", "ITEM", "FACTION_POINTS", "COMMAND"), b))
                                .then(Commands.argument("target", TargetArgument.target())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(StoryNpcsCommands::addQuestReward)))));
        var rewardRemove = Commands.literal("remove")
                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                        .suggests(QUEST_IDS)
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(StoryNpcsCommands::removeQuestReward)));
        quest.then(Commands.literal("reward").requires(s -> s.hasPermission(2))
                .then(rewardAdd).then(rewardRemove));

        // GUI entry point — /storynpcs quest gui [quest_id]
        var questIdGui = Commands.argument("quest_id", ResourceLocationArgument.id())
                .suggests(QUEST_IDS)
                .executes(ctx -> openQuestGui(ctx, getNamespacedId(ctx, "quest_id")));
        quest.then(Commands.literal("gui").requires(s -> s.hasPermission(2))
                .executes(ctx -> openQuestGui(ctx, null))
                .then(questIdGui));

        quest.then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                .then(Commands.argument("quest_id", ResourceLocationArgument.id())
                        .suggests(QUEST_IDS)
                        .executes(StoryNpcsCommands::deleteQuest)));

        return quest;
    }

    /**
     * Player-facing mailbox commands (issue #71 — postman/mailbox role foundation).
     * 1-based indices, matching this repo's other list-then-index UI conventions
     * (e.g. NpcRulesScreen's row numbering).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> mailCommands() {
        return Commands.literal("mail")
                .executes(ctx -> listMail(ctx, null))
                .then(Commands.literal("list")
                        .executes(ctx -> listMail(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> listMail(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("read")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> readMail(ctx, IntegerArgumentType.getInteger(ctx, "index")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> deleteMail(ctx, IntegerArgumentType.getInteger(ctx, "index")))));
    }

    private static int listMail(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = targetPlayer;
        if (player == null) {
            if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
                player = sp;
            } else {
                ctx.getSource().sendFailure(Component.literal("Player must be specified when executed from console"));
                return 0;
            }
        }
        var service = mod(ctx).getApplicationService();
        var mail = service.getMailbox(player.getUUID());
        if (mail.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7Mailbox is empty."), false);
            return 1;
        }
        for (int i = 0; i < mail.size(); i++) {
            var m = mail.get(i);
            int idx = i + 1;
            String prefix = m.isRead() ? "§7" : "§f§l";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    prefix + "[" + idx + "] " + (m.isRead() ? "" : "§e(new) ") + "§bFrom " + m.getSender()
                            + "§r: " + m.getSubject()), false);
        }
        return mail.size();
    }

    private static int readMail(CommandContext<CommandSourceStack> ctx, int index1Based) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Only a player can read their own mail"));
            return 0;
        }
        var service = mod(ctx).getApplicationService();
        var mail = service.getMailbox(player.getUUID());
        if (index1Based < 1 || index1Based > mail.size()) {
            ctx.getSource().sendFailure(Component.literal("Mail index " + index1Based + " out of range (1-" + mail.size() + ")"));
            return 0;
        }
        var message = mail.get(index1Based - 1);
        var readResult = service.markMailRead(new com.storynpcs.service.PlayerProgressionActionRequest(
                "mail.read", "command", player.getUUID(), player.getUUID(),
                java.util.UUID.randomUUID(), ctx.getSource().hasPermission(2) ? 2 : -1),
                message.getId());
        if (!readResult.applied() && !readResult.decision().allowed()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] " + readResult.decision().code() + ": " + readResult.decision().message()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§bFrom " + message.getSender() + "§r — §e" + message.getSubject() + "\n§f" + message.getBody()), false);
        return 1;
    }

    private static int deleteMail(CommandContext<CommandSourceStack> ctx, int index1Based) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Only a player can delete their own mail"));
            return 0;
        }
        var service = mod(ctx).getApplicationService();
        var mail = service.getMailbox(player.getUUID());
        if (index1Based < 1 || index1Based > mail.size()) {
            ctx.getSource().sendFailure(Component.literal("Mail index " + index1Based + " out of range (1-" + mail.size() + ")"));
            return 0;
        }
        var message = mail.get(index1Based - 1);
        var deleteResult = service.deleteMail(new com.storynpcs.service.PlayerProgressionActionRequest(
                "mail.delete", "command", player.getUUID(), player.getUUID(),
                java.util.UUID.randomUUID(), ctx.getSource().hasPermission(2) ? 2 : -1),
                message.getId());
        if (!deleteResult.applied() && !deleteResult.decision().allowed()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] " + deleteResult.decision().code() + ": " + deleteResult.decision().message()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§7Deleted mail: " + message.getSubject()), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> factionCommands() {
        var faction = Commands.literal("faction")
                .executes(StoryNpcsCommands::sendFactionHelp)
                .then(Commands.literal("list").executes(StoryNpcsCommands::listFactions))
                .then(Commands.literal("info")
                        .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                                .suggests(FACTION_IDS)
                                .executes(StoryNpcsCommands::infoFaction)));

        var factionIdSet = Commands.argument("faction_id", ResourceLocationArgument.id())
                .suggests(FACTION_IDS)
                .then(Commands.argument("points", IntegerArgumentType.integer())
                        .executes(ctx -> setFaction(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> setFaction(ctx, EntityArgument.getPlayer(ctx, "player")))));
        faction.then(Commands.literal("set").requires(s -> s.hasPermission(2)).then(factionIdSet));

        var factionIdAdjust = Commands.argument("faction_id", ResourceLocationArgument.id())
                .suggests(FACTION_IDS)
                .then(Commands.argument("delta", IntegerArgumentType.integer())
                        .executes(ctx -> adjustFaction(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> adjustFaction(ctx, EntityArgument.getPlayer(ctx, "player")))));
        faction.then(Commands.literal("adjust").requires(s -> s.hasPermission(2)).then(factionIdAdjust));

        // P9-3: reset standing to the faction's authored default; remove the entry
        var factionIdReset = Commands.argument("faction_id", ResourceLocationArgument.id())
                .suggests(FACTION_IDS)
                .executes(ctx -> resetFaction(ctx, null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> resetFaction(ctx, EntityArgument.getPlayer(ctx, "player"))));
        faction.then(Commands.literal("reset").requires(s -> s.hasPermission(2)).then(factionIdReset));

        var factionIdRemove = Commands.argument("faction_id", ResourceLocationArgument.id())
                .suggests(FACTION_IDS)
                .executes(ctx -> removeFaction(ctx, null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> removeFaction(ctx, EntityArgument.getPlayer(ctx, "player"))));
        faction.then(Commands.literal("remove").requires(s -> s.hasPermission(2)).then(factionIdRemove));

        // Authoring — create / configure (issue #18)
        var factionIdCreate = Commands.argument("faction_id", ResourceLocationArgument.id())
                .executes(ctx -> createFaction(ctx, null))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> createFaction(ctx, StringArgumentType.getString(ctx, "name"))));
        faction.then(Commands.literal("create").requires(s -> s.hasPermission(2)).then(factionIdCreate));

        var factionIdConfigure = Commands.argument("faction_id", ResourceLocationArgument.id())
                .suggests(FACTION_IDS);
        factionIdConfigure.then(Commands.literal("defaultPoints")
                .then(Commands.argument("value", IntegerArgumentType.integer())
                        .executes(ctx -> configureFaction(ctx, "defaultPoints"))));
        factionIdConfigure.then(Commands.literal("hostileThreshold")
                .then(Commands.argument("value", IntegerArgumentType.integer())
                        .executes(ctx -> configureFaction(ctx, "hostileThreshold"))));
        factionIdConfigure.then(Commands.literal("friendlyThreshold")
                .then(Commands.argument("value", IntegerArgumentType.integer())
                        .executes(ctx -> configureFaction(ctx, "friendlyThreshold"))));
        faction.then(Commands.literal("configure").requires(s -> s.hasPermission(2)).then(factionIdConfigure));

        // GUI entry point — /storynpcs faction gui [faction_id]
        var factionIdGui = Commands.argument("faction_id", ResourceLocationArgument.id())
                .suggests(FACTION_IDS)
                .executes(ctx -> openFactionGui(ctx, getNamespacedId(ctx, "faction_id")));
        faction.then(Commands.literal("gui").requires(s -> s.hasPermission(2))
                .executes(ctx -> openFactionGui(ctx, null))
                .then(factionIdGui));

        faction.then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                        .suggests(FACTION_IDS)
                        .executes(StoryNpcsCommands::deleteFaction)
                        .then(Commands.argument("fallback_id", ResourceLocationArgument.id())
                                .suggests(FACTION_IDS)
                                .executes(StoryNpcsCommands::deleteFaction))));

        return faction;
    }

    /**
     * /storynpcs npc rule — behavior rule authoring (issue #19).
     * Grammar: rule list|remove|add on an NPC. `add` takes a trigger word then a
     * condition literal subtree then an action literal subtree, e.g.
     *   npc rule add storynpcs:guard on_damaged health_percent le 0.5 yield_combat
     * Condition literals: always, actor_is_player, faction_standing &lt;faction&gt;
     * &lt;standing&gt;, health_percent le|gt &lt;0..1&gt;, strike_count le|gt &lt;n&gt;.
     * Action literals: send_message &lt;text&gt;, add_threat [amount],
     * shout_alert &lt;radius&gt; &lt;msg&gt;, yield_combat [fraction] [dialogue],
     * change_stance &lt;stance&gt;, adjust_faction &lt;faction&gt; &lt;delta&gt;.
     * COMPOSITE conditions are intentionally not exposed — they need nested
     * sub-conditions that don't fit a flat command grammar (documented scope cut).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> npcRuleCommands() {
        var rule = Commands.literal("rule");

        // rule list <npc_id>
        rule.then(Commands.literal("list")
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .executes(StoryNpcsCommands::listNpcRules)));

        // rule remove <npc_id> <index>  (1-based, matching npc rule list output)
        rule.then(Commands.literal("remove")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(StoryNpcsCommands::removeNpcRule))));

        // rule add <npc_id> <trigger> <condition...> <action...>
        var triggerArg = Commands.argument("trigger", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        List.of("on_damaged", "on_witness_assault", "on_interact",
                                "on_health_percent_drop", "on_target_lost", "on_tick", "on_yield"), b));

        // Conditions without arguments attach the action subtree directly.
        var always = Commands.literal("always");
        attachRuleActions(always, "always", null);
        triggerArg.then(always);

        var actorIsPlayer = Commands.literal("actor_is_player");
        attachRuleActions(actorIsPlayer, "actor_is_player", null);
        triggerArg.then(actorIsPlayer);

        var cStanding = Commands.argument("c_standing", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        List.of("hostile", "neutral", "friendly"), b));
        attachRuleActions(cStanding, "faction_standing", null);
        triggerArg.then(Commands.literal("faction_standing")
                .then(Commands.argument("c_faction", ResourceLocationArgument.id())
                        .suggests(FACTION_IDS)
                        .then(cStanding)));

        for (String op : List.of("le", "gt")) {
            var hpThreshold = Commands.argument("c_threshold",
                    com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.0));
            attachRuleActions(hpThreshold, "health_percent", op);
            triggerArg.then(Commands.literal("health_percent")
                    .then(Commands.literal(op).then(hpThreshold)));

            var scThreshold = Commands.argument("c_threshold", IntegerArgumentType.integer(0));
            attachRuleActions(scThreshold, "strike_count", op);
            triggerArg.then(Commands.literal("strike_count")
                    .then(Commands.literal(op).then(scThreshold)));
        }

        rule.then(Commands.literal("add")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(triggerArg)));

        return rule;
    }

    /**
     * /storynpcs npc ability — authored combat abilities (#147).
     *
     *   npc ability list <npc_id>
     *   npc ability add <npc_id> <type> [trigger] [cooldown_ticks] [chance]
     *   npc ability set <npc_id> <index> <param> <value>
     *   npc ability remove <npc_id> <index>
     *
     * Params: range, strength, damage_multiplier, duration_ticks, amplifier,
     * bonus_damage — each validated per-type by NpcAbility.validate() on the
     * canonical write path.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> npcAbilityCommands() {
        var ability = Commands.literal("ability");

        ability.then(Commands.literal("list")
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .executes(StoryNpcsCommands::listNpcAbilities)));

        ability.then(Commands.literal("remove")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(StoryNpcsCommands::removeNpcAbility))));

        var chanceArg = Commands.argument("chance",
                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.0))
                .executes(StoryNpcsCommands::addNpcAbility);
        var cooldownArg = Commands.argument("cooldown_ticks", IntegerArgumentType.integer(0, 1200))
                .executes(StoryNpcsCommands::addNpcAbility)
                .then(chanceArg);
        var triggerArg = Commands.argument("trigger", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        List.of("attack", "damaged", "update"), b))
                .executes(StoryNpcsCommands::addNpcAbility)
                .then(cooldownArg);
        var typeArg = Commands.argument("type", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        java.util.Arrays.stream(com.storynpcs.domain.ability.AbilityType.values())
                                .map(t -> t.name().toLowerCase()).toList(), b))
                .executes(StoryNpcsCommands::addNpcAbility)
                .then(triggerArg);
        ability.then(Commands.literal("add")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(typeArg)));

        var valueArg = Commands.argument("value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                .executes(StoryNpcsCommands::setNpcAbilityParam);
        var paramArg = Commands.argument("param", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        List.of("range", "strength", "damage_multiplier",
                                "duration_ticks", "amplifier", "bonus_damage"), b))
                .then(valueArg);
        ability.then(Commands.literal("set")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .then(paramArg))));

        return ability;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> npcTradeCommands() {
        var trade = Commands.literal("trade");

        // trade enable <npc_id> [market_name]
        var npcEnable = Commands.argument("npc_id", ResourceLocationArgument.id())
                .suggests(NPC_IDS)
                .executes(ctx -> enableTrader(ctx, null))
                .then(Commands.argument("market_name", StringArgumentType.greedyString())
                        .executes(ctx -> enableTrader(ctx, StringArgumentType.getString(ctx, "market_name"))));
        trade.then(Commands.literal("enable")
                .requires(s -> s.hasPermission(2))
                .then(npcEnable));

        // trade disable <npc_id>
        trade.then(Commands.literal("disable")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .executes(StoryNpcsCommands::disableTrader)));

        // trade list <npc_id>
        trade.then(Commands.literal("list")
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .executes(StoryNpcsCommands::listTrades)));

        // trade remove <npc_id> <index>  (1-based, matching npc trade list output)
        trade.then(Commands.literal("remove")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(StoryNpcsCommands::removeTrade))));

        // trade add <npc_id> <offer_item> <offer_count> <price_item> <price_count> [max_uses]
        var priceCount = Commands.argument("price_count", IntegerArgumentType.integer(1))
                .executes(ctx -> addTrade(ctx, 0))
                .then(Commands.argument("max_uses", IntegerArgumentType.integer(0))
                        .executes(ctx -> addTrade(ctx, IntegerArgumentType.getInteger(ctx, "max_uses"))));
        trade.then(Commands.literal("add")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .then(Commands.argument("offer_item", ResourceLocationArgument.id())
                                .then(Commands.argument("offer_count", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("price_item", ResourceLocationArgument.id())
                                                .then(priceCount))))));

        return trade;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> npcBankCommands() {
        var bank = Commands.literal("bank");

        // bank enable <npc_id> [bank_name]
        var npcEnable = Commands.argument("npc_id", ResourceLocationArgument.id())
                .suggests(NPC_IDS)
                .executes(ctx -> enableBanker(ctx, null))
                .then(Commands.argument("bank_name", StringArgumentType.greedyString())
                        .executes(ctx -> enableBanker(ctx, StringArgumentType.getString(ctx, "bank_name"))));
        bank.then(Commands.literal("enable")
                .requires(s -> s.hasPermission(2))
                .then(npcEnable));

        // bank disable <npc_id>
        bank.then(Commands.literal("disable")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("npc_id", ResourceLocationArgument.id())
                        .suggests(NPC_IDS)
                        .executes(StoryNpcsCommands::disableBanker)));

        // bank share <owner> PRIVATE | SHARED [memberUuidCsv] — configures a
        // vault's access policy through the canonical bank-access mutation.
        var shareOwner = Commands.argument("owner", EntityArgument.player())
                .then(Commands.argument("policy", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("PRIVATE", "SHARED"), b))
                        .executes(ctx -> shareBankAccess(ctx, null))
                        .then(Commands.argument("members", StringArgumentType.greedyString())
                                .executes(ctx -> shareBankAccess(ctx,
                                        StringArgumentType.getString(ctx, "members")))));
        bank.then(Commands.literal("share")
                .requires(s -> s.hasPermission(2))
                .then(shareOwner));

        return bank;
    }

    /** Attaches every action literal subtree under a condition leaf. */
    private static void attachRuleActions(
            com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> leaf,
            String cond, String condOp) {
        if (leaf == null) return;

        var sendMessage = Commands.literal("send_message")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> addNpcRule(ctx, cond, condOp, "send_message")));
        leaf.then(sendMessage);

        var addThreat = Commands.literal("add_threat")
                .executes(ctx -> addNpcRule(ctx, cond, condOp, "add_threat"))
                .then(Commands.argument("amount",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> addNpcRule(ctx, cond, condOp, "add_threat")));
        leaf.then(addThreat);

        var shoutAlert = Commands.literal("shout_alert")
                .then(Commands.argument("radius",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 64.0))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> addNpcRule(ctx, cond, condOp, "shout_alert"))));
        leaf.then(shoutAlert);

        var yieldCombat = Commands.literal("yield_combat")
                .executes(ctx -> addNpcRule(ctx, cond, condOp, "yield_combat"))
                .then(Commands.argument("fraction",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.0))
                        .executes(ctx -> addNpcRule(ctx, cond, condOp, "yield_combat"))
                        .then(Commands.argument("dialogue", StringArgumentType.greedyString())
                                .executes(ctx -> addNpcRule(ctx, cond, condOp, "yield_combat"))));
        leaf.then(yieldCombat);

        var changeStance = Commands.literal("change_stance")
                .then(Commands.argument("stance", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                List.of("passive", "retaliate_only", "defensive", "guard", "aggressive"), b))
                        .executes(ctx -> addNpcRule(ctx, cond, condOp, "change_stance")));
        leaf.then(changeStance);

        var adjustFaction = Commands.literal("adjust_faction")
                .then(Commands.argument("faction_id", ResourceLocationArgument.id())
                        .suggests(FACTION_IDS)
                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                .executes(ctx -> addNpcRule(ctx, cond, condOp, "adjust_faction"))));
        leaf.then(adjustFaction);
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Reloading YAML definitions..."), true);

        StoryNpcs mod = mod(ctx);

        try {
            Path worldDir = source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            Path definitionsDir = worldDir.resolve("storynpcs").resolve("definitions");

            // Atomic reload: Load into staging registry to ensure zero-data-loss on syntax/validation errors
            DefinitionRegistry staging = new DefinitionRegistry();
            com.storynpcs.yaml.YamlDefinitionLoader stagingLoader = new com.storynpcs.yaml.YamlDefinitionLoader(staging);
            var result = stagingLoader.loadDirectory(definitionsDir);

            if (result.isValid()) {
                mod.getRegistry().copyFrom(staging);
                // Sessions pinned to reloaded dialogue definitions are re-evaluated:
                // vanished/broken/now-unavailable dialogues close; survivors rebind.
                mod.getApplicationService().notifyDialogueDefinitionsReloaded();
                mod.getApplicationService().notifyRecipesLoaded("reload");
                // Surface diagnostics to online ops too — a reload run by one admin shouldn't
                // leave the others blind to warnings.
                mod.setLastLoadDiagnostics(result);
                source.sendSuccess(() -> Component.literal("[StoryNPCs] Definitions reloaded successfully: " + result.formatReport(10)), true);
                return 1;
            } else {
                mod.setLastLoadDiagnostics(result);
                source.sendFailure(Component.literal("[StoryNPCs] Validation errors during reload (previous definitions retained):\n" + result.formatReport(10)));
                // Other online ops get a heads-up too — sendFailure only reaches the caller
                Component summary = Component.literal("[StoryNPCs] Reload by " + source.getTextName()
                        + " failed validation: " + result.getErrors().size()
                        + " error(s) — previous definitions retained. Check the server log.");
                for (var p : source.getServer().getPlayerList().getPlayers()) {
                    if (p.hasPermissions(2) && p != source.getEntity()) {
                        p.sendSystemMessage(summary);
                    }
                }
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("[StoryNPCs] Error during reload (previous definitions retained): " + e.getMessage()));
            return 0;
        }
    }

    private static NamespacedId getNamespacedId(CommandContext<CommandSourceStack> ctx, String argName) {
        ResourceLocation loc = ResourceLocationArgument.getId(ctx, argName);
        return CommandSuggestionEngine.parseId(loc.toString())
                .orElse(NamespacedId.of(loc.getNamespace(), loc.getPath()));
    }

    // NPC Handlers
    private static int listNpcs(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = mod(ctx).getRegistry();
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
        DefinitionRegistry reg = mod(ctx).getRegistry();
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
        // Action row — one click instead of retyping the id into spawn/set/despawn/delete
        if (ctx.getSource().hasPermission(2)) {
            MutableComponent actions = Component.literal(" §7[ ")
                    .append(clickable("§aSpawn Here", "/storynpcs npc spawn " + id,
                            ClickEvent.Action.RUN_COMMAND, "Spawn '" + id + "' at your position"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§bSet Dialogue", "/storynpcs npc set dialogue " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Assign dialogue to '" + id + "'"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§dSet Faction", "/storynpcs npc set faction " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Assign faction to '" + id + "'"))
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

    private static void refreshLoadedEntities(CommandSourceStack source, NamespacedId id) {
        if (source == null || source.getServer() == null || id == null) return;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof StoryNpcEntity npc && id.toString().equals(npc.getDefinitionId())) {
                    npc.applyDefinition();
                }
            }
        }
    }

    private static int setNpcName(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String value = StringArgumentType.getString(ctx, "value").trim();
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getDisplay().setName(value));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Set name of '" + npcId + "' to '" + value + "' (persisted to YAML)."), true);
        return 1;
    }

    /**
     * {@code npc set name <id> random <culture>} — server-side seeded Markov
     * generation applied through the canonical mutation path (#123).
     */
    private static int randomizeNpcName(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var server = ctx.getSource().getServer();
        var service = mod != null && server != null
                ? mod.getNameGenerationService(server) : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Name generation is unavailable on this server."));
            return 0;
        }
        var cultureId = NamespacedId.of(ctx.getArgument("culture", net.minecraft.resources.ResourceLocation.class).toString());
        if (!service.cultures().contains(cultureId)) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Unknown name culture '" + cultureId
                    + "' — available: " + service.cultures().stream()
                            .map(NamespacedId::asString).sorted()
                            .collect(java.util.stream.Collectors.joining(", "))));
            return 0;
        }
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var suggested = service.suggest(cultureId, ctx.getSource().getLevel().getGameTime());
        if (suggested.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Culture '" + cultureId
                    + "' produced no name — check its dictionary."));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                def -> def.getDisplay().setName(suggested.get()));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Named '" + npcId
                + "' '" + suggested.get() + "' (culture " + cultureId + ", persisted to YAML)."), true);
        return 1;
    }

    private static int setNpcTitle(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String value = StringArgumentType.getString(ctx, "value").trim();
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getDisplay().setTitle(value));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Set title of '" + npcId + "' to '" + value + "' (persisted to YAML)."), true);
        return 1;
    }

    private static int setNpcSkin(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String texture = StringArgumentType.getString(ctx, "texture").trim();
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getDisplay().setSkinTexture(texture));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Set skin of '" + npcId + "' to '" + texture + "' (persisted to YAML)."), true);
        return 1;
    }

    private static int setNpcHealth(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        double value = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "value");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getStats().setMaxHealth(value));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Set health of '%s' to %.1f (persisted to YAML).", npcId, value)), true);
        return 1;
    }

    private static int setNpcDamage(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        double value = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "value");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getStats().setAttackDamage(value));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Set attack damage of '%s' to %.1f (persisted to YAML).", npcId, value)), true);
        return 1;
    }

    private static int setNpcSpeed(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        double value = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "value");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getStats().setMovementSpeed(value));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Set speed of '%s' to %.2f (persisted to YAML).", npcId, value)), true);
        return 1;
    }

    private static int setNpcRange(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getAi().setWalkingRange(value));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Set walking range of '%s' to %d (persisted to YAML).", npcId, value)), true);
        return 1;
    }

    private static int setNpcMovement(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String typeStr = StringArgumentType.getString(ctx, "type").toUpperCase();
        com.storynpcs.domain.npc.NpcAi.MovementType type;
        try {
            type = com.storynpcs.domain.npc.NpcAi.MovementType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Invalid movement type: " + typeStr + ". Valid: STANDING, WANDERING, PATHING"));
            return 0;
        }
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getAi().setMovementType(type));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Set movement of '%s' to %s (persisted to YAML).", npcId, type)), true);
        return 1;
    }

    private static int setNpcStance(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String stanceStr = StringArgumentType.getString(ctx, "type").toUpperCase();
        com.storynpcs.domain.npc.TacticalStance stance;
        try {
            stance = com.storynpcs.domain.npc.TacticalStance.valueOf(stanceStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Invalid tactical stance: " + stanceStr + ". Valid: PASSIVE, NEUTRAL, GUARD, AGGRESSIVE, EVASIVE"));
            return 0;
        }
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId, def -> def.getAi().setTacticalStance(stance));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[StoryNPCs] Set tactical stance of '%s' to %s (persisted to YAML).", npcId, stance)), true);
        return 1;
    }

    // ── P9-3 parity handlers ─────────────────────────────────────────────────

    /** {@code npc set marks <npc_id> clear|<color>} — canonical definition marks. */
    private static int setNpcMarks(CommandContext<CommandSourceStack> ctx, String colorArg) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        if (mod.getRegistry().getNpc(npcId).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        List<com.storynpcs.domain.npc.NpcMark> marks;
        if (colorArg == null) {
            marks = List.of();
        } else {
            int color;
            try {
                color = colorArg.startsWith("#") || colorArg.startsWith("0x")
                        ? Integer.decode(colorArg) : Integer.parseInt(colorArg);
            } catch (NumberFormatException bad) {
                ctx.getSource().sendFailure(Component.literal(
                        "[StoryNPCs] Invalid mark color '" + colorArg + "' — decimal, #hex, or 0xhex."));
                return 0;
            }
            marks = List.of(new com.storynpcs.domain.npc.NpcMark(0, color & 0xFFFFFF, ""));
        }
        var finalMarks = marks;
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                def -> def.setMarks(finalMarks));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Mark update rejected:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        var applied = marks;
        ctx.getSource().sendSuccess(() -> Component.literal(applied.isEmpty()
                ? "[StoryNPCs] Cleared marks on " + npcId
                : "[StoryNPCs] Set mark color on " + npcId), true);
        return 1;
    }

    /** {@code npc set visibility <npc_id> <0|1|2>} — authored display visibility. */
    private static int setNpcVisibility(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        int mode = IntegerArgumentType.getInteger(ctx, "mode");
        StoryNpcs mod = mod(ctx);
        if (mod.getRegistry().getNpc(npcId).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                def -> def.getDisplay().setVisibility(mode));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Visibility update rejected:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Set visibility of " + npcId + " to " + mode), true);
        return 1;
    }

    /** {@code npc home <npc_id>} — reset start/home position to current entity spot. */
    private static int setNpcHome(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        var level = ctx.getSource().getLevel();
        var found = new java.util.ArrayList<com.storynpcs.entity.StoryNpcEntity>();
        for (var entity : level.getEntitiesOfClass(com.storynpcs.entity.StoryNpcEntity.class,
                ctx.getSource().getPosition() != null
                        ? new net.minecraft.world.phys.AABB(
                                level.getWorldBorder().getMinX(), level.getMinBuildHeight(),
                                level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(),
                                level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ())
                        : new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0))) {
            if (npcId.toString().equals(entity.getDefinitionId())) found.add(entity);
        }
        if (found.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] No loaded entity for " + npcId + " — spawn it first."));
            return 0;
        }
        int count = 0;
        for (var entity : found) {
            entity.setStartPosition(entity.blockPosition());
            count++;
        }
        final int n = count;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Reset home position for " + n + " entity(ies) of " + npcId), true);
        return n;
    }

    /** {@code npc respawn <npc_id>} — despawn live entities then re-spawn at source pos. */
    private static int respawnNpc(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        if (mod(ctx).getRegistry().getNpc(npcId).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] NPC definition not found: " + npcId));
            return 0;
        }
        var level = ctx.getSource().getLevel();
        var position = ctx.getSource().getPosition();
        int removed = 0;
        for (var entity : level.getEntitiesOfClass(com.storynpcs.entity.StoryNpcEntity.class,
                new net.minecraft.world.phys.AABB(
                        level.getWorldBorder().getMinX(), level.getMinBuildHeight(),
                        level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(),
                        level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()))) {
            if (npcId.toString().equals(entity.getDefinitionId())) {
                entity.discard();
                removed++;
            }
        }
        var entity = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
        if (entity == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Failed to create NPC entity"));
            return 0;
        }
        entity.setPos(position.x, position.y, position.z);
        entity.setDefinitionId(npcId.toString());
        entity.setStartPosition(entity.blockPosition());
        level.addFreshEntity(entity);
        final int n = removed;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Respawned " + npcId + " (despawned " + n + " prior entity(ies))"), true);
        return 1;
    }

    // ── P9-3 clone-template handlers ─────────────────────────────────────────

    /** {@code template capture <template_id> <npc_id>} — clone/add parity. */
    private static int captureTemplate(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var templateId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "template_id").toString());
        var npcId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "npc_id").toString());
        var def = mod.getRegistry().getNpc(npcId).orElse(null);
        if (def == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] NPC definition not found: " + npcId));
            return 0;
        }
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(templateId);
        // Detach through the serde round-trip so the template never aliases the
        // live definition's mutable facets.
        var detached = com.storynpcs.domain.npc.NpcDefinitionSerde.fromJson(
                com.storynpcs.domain.npc.NpcDefinitionSerde.toJson(def)).orElse(null);
        if (detached == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] NPC definition failed to snapshot."));
            return 0;
        }
        template.setDefinition(detached);
        var result = service.saveTemplate(
                commandMutationRequest(ctx, service, "template", "replace", templateId), template);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template capture rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Captured " + npcId + " as template " + templateId), true);
        return 1;
    }

    /** {@code template spawn <template_id> [pos] [name]} — clone/spawn parity. */
    private static int spawnTemplate(CommandContext<CommandSourceStack> ctx, Vec3 pos, String displayName) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var templateId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "template_id").toString());
        var template = mod.getRegistry().getTemplate(templateId).orElse(null);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template not found: " + templateId));
            return 0;
        }
        Vec3 spawnPos = pos != null ? pos : ctx.getSource().getPosition();
        // Derived NPC id: template-<hash> — deterministic, namespaced, collision-checked.
        var npcId = NamespacedId.of(templateId + "_clone_" + Integer.toHexString(
                java.util.Objects.hash(spawnPos.x, spawnPos.y, spawnPos.z, displayName)));
        var definition = template.instantiate(npcId);
        if (displayName != null) {
            definition.getDisplay().setName(displayName);
        }
        var create = service.createNpc(
                commandMutationRequest(ctx, service, "npc", "create", npcId), definition);
        if (!create.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Clone spawn rejected:\n" + create.formatReport(5)));
            return 0;
        }
        return spawnNpcEntity(ctx, mod, service, npcId, spawnPos);
    }

    /** {@code template grid <template_id> <length> <width> [pos]} — bounded clone grid. */
    private static int gridTemplate(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        int length = IntegerArgumentType.getInteger(ctx, "length");
        int width = IntegerArgumentType.getInteger(ctx, "width");
        if ((long) length * width > 16) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Grid too large — length*width must be <= 16."));
            return 0;
        }
        var templateId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "template_id").toString());
        var template = mod.getRegistry().getTemplate(templateId).orElse(null);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template not found: " + templateId));
            return 0;
        }
        Vec3 base = pos != null ? pos : ctx.getSource().getPosition();
        int spawned = 0;
        for (int x = 0; x < length; x++) {
            for (int z = 0; z < width; z++) {
                var cell = new Vec3(base.x + x, base.y, base.z + z);
                var npcId = NamespacedId.of(templateId + "_grid_" + x + "_" + z + "_"
                        + Integer.toHexString(java.util.Objects.hash(base.x, base.z)));
                var definition = template.instantiate(npcId);
                var create = service.createNpc(
                        commandMutationRequest(ctx, service, "npc", "create", npcId), definition);
                if (create.applied()
                        && spawnNpcEntity(ctx, mod, service, npcId, cell) > 0) {
                    spawned++;
                }
            }
        }
        final int n = spawned;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Spawned " + n + "/" + (length * width) + " clone(s) for " + templateId), true);
        return n;
    }

    private static int spawnNpcEntity(CommandContext<CommandSourceStack> ctx, StoryNpcs mod,
                                      com.storynpcs.service.StoryNpcsApplicationService service,
                                      NamespacedId npcId, Vec3 pos) {
        var level = ctx.getSource().getLevel();
        var entity = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
        if (entity == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Failed to create NPC entity"));
            return 0;
        }
        entity.setPos(pos.x, pos.y, pos.z);
        entity.setDefinitionId(npcId.toString());
        entity.setStartPosition(entity.blockPosition());
        level.addFreshEntity(entity);
        return 1;
    }

    // ── P9-3 config/dialogue/faction/follower handlers ───────────────────────

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> TUNABLE_KEYS =
            (ctx, builder) -> {
                var mod = modOrNull(ctx);
                var keys = mod != null ? mod.getRuntimeTunables().snapshot().keySet() : java.util.Set.<String>of();
                return net.minecraft.commands.SharedSuggestionProvider.suggest(keys, builder);
            };

    private static int configList(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Mod not initialized."));
            return 0;
        }
        var snapshot = mod.getRuntimeTunables().snapshot();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Runtime tunables (rev " + mod.getRuntimeTunables().revision() + "):"), false);
        snapshot.forEach((k, v) -> ctx.getSource().sendSuccess(
                () -> Component.literal("  " + k + " = " + v), false));
        return snapshot.size();
    }

    private static int configGet(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Mod not initialized."));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key");
        var snapshot = mod.getRuntimeTunables().snapshot();
        if (!snapshot.containsKey(key)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Unknown tunable: " + key));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] " + key + " = " + snapshot.get(key)), false);
        return 1;
    }

    private static int configSet(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        var result = service.mutateRuntimeTunables(
                new com.storynpcs.service.MutationRequest("config.mutate", "command",
                        "config.mutate", com.storynpcs.domain.common.NamespacedId.of("storynpcs:config"),
                        mod.getRuntimeTunables().revision(), java.util.UUID.randomUUID(),
                        ctx.getSource().hasPermission(2) ? 2 : 0),
                java.util.Map.of(key, value));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Config set rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] " + key + " = " + value), true);
        return 1;
    }

    /** {@code dialogue markread|unmarkread <dialogue_id> <player>} — visit-marker parity. */
    private static int dialogueMarkRead(CommandContext<CommandSourceStack> ctx, boolean mark) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var dialogueId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "dialogue_id").toString());
        ServerPlayer subject;
        try {
            subject = EntityArgument.getPlayer(ctx, "player");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Player not found."));
            return 0;
        }
        var actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        var request = new com.storynpcs.service.PlayerProgressionActionRequest(
                mark ? "dialogue.mark.read" : "dialogue.mark.clear",
                actorUuid != null ? "player" : "command", actorUuid, subject.getUUID(),
                java.util.UUID.randomUUID(), ctx.getSource().hasPermission(2) ? 2 : 0);
        var result = mark
                ? service.markDialogueRead(request, dialogueId)
                : service.clearDialogueReadMarkers(request, dialogueId);
        if (!result.applied()) {
            String reason = result.decision() != null ? result.decision().message() : "not applicable";
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + reason));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] " + (mark ? "Marked" : "Cleared") + " read state on "
                        + dialogueId + " for " + subject.getScoreboardName()), true);
        return 1;
    }

    /** {@code faction reset <faction_id> [player]} — standing back to authored default. */
    private static int resetFaction(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = resolvePlayer(ctx, targetPlayer);
        if (player == null) return 0;
        var id = getNamespacedId(ctx, "faction_id");
        var mod = mod(ctx);
        var faction = mod.getRegistry().getFaction(id).orElse(null);
        if (faction == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Faction not found: " + id));
            return 0;
        }
        var service = mod.getApplicationService();
        var actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        var request = com.storynpcs.service.FactionProgressionMutationRequest.set(
                "command", actorUuid, player.getUUID(), id, faction.getDefaultPoints(),
                service.currentFactionProgressionRevision(player.getUUID()), java.util.UUID.randomUUID(),
                ctx.getSource().hasPermission(2) ? 2 : 0);
        var result = service.mutateFactionProgression(request);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Failed to reset faction standing: " + result.formatReport()));
            return 0;
        }
        var target = player;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Reset faction '%s' standing to default %d for %s",
                id, faction.getDefaultPoints(), target.getScoreboardName())), true);
        return 1;
    }

    /** {@code faction remove <faction_id> [player]} — drop the standing entry. */
    private static int removeFaction(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = resolvePlayer(ctx, targetPlayer);
        if (player == null) return 0;
        var id = getNamespacedId(ctx, "faction_id");
        var service = mod(ctx).getApplicationService();
        var actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        var request = com.storynpcs.service.FactionProgressionMutationRequest.remove(
                "command", actorUuid, player.getUUID(), id,
                service.currentFactionProgressionRevision(player.getUUID()), java.util.UUID.randomUUID(),
                ctx.getSource().hasPermission(2) ? 2 : 0);
        var result = service.mutateFactionProgression(request);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Failed to remove faction entry: " + result.formatReport()));
            return 0;
        }
        var target = player;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Removed faction '%s' entry for %s", id, target.getScoreboardName())), true);
        return 1;
    }

    private static ServerPlayer resolvePlayer(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (target != null) return target;
        if (ctx.getSource().getEntity() instanceof ServerPlayer sp) return sp;
        ctx.getSource().sendFailure(Component.literal(
                "Player must be specified when executed from console"));
        return null;
    }

    // ── P9-4 remote admin handlers (target SPacketRemote*) ──────────────────

    /**
     * Resolve a remote-op target: the entity must be loaded in the caller's
     * own level — cross-dimension and unloaded targets are refused before any
     * mutation, which is the "global actions cannot mutate an unloaded or
     * wrong-world target" contract.
     */
    private static com.storynpcs.entity.StoryNpcEntity resolveRemoteTarget(
            CommandContext<CommandSourceStack> ctx, String op) {
        var uuid = net.minecraft.commands.arguments.UuidArgument.getUuid(ctx, "entity_uuid");
        var entity = ctx.getSource().getLevel().getEntity(uuid);
        if (!(entity instanceof com.storynpcs.entity.StoryNpcEntity npc)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] REMOTE_TARGET_UNLOADED — no loaded StoryNPC entity "
                            + uuid + " in this dimension."));
            return null;
        }
        return npc;
    }

    private static void auditRemote(CommandContext<CommandSourceStack> ctx, String op,
                                    com.storynpcs.entity.StoryNpcEntity npc, String outcome) {
        var mod = modOrNull(ctx);
        if (mod == null) return;
        var operator = ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp.getUUID() : null;
        mod.getEventPublisher().publish(new com.storynpcs.api.event.RemoteAdminAuditEvent(
                op, operator, npc.getDefinitionId() + "@" + npc.blockPosition().toShortString(),
                outcome));
    }

    /** {@code remote freeze <entity_uuid>} — toggle no-AI on a loaded NPC. */
    private static int remoteFreeze(CommandContext<CommandSourceStack> ctx) {
        var npc = resolveRemoteTarget(ctx, "freeze");
        if (npc == null) return 0;
        boolean frozen = !npc.isNoAi();
        npc.setNoAi(frozen);
        auditRemote(ctx, "remote.freeze", npc, frozen ? "frozen" : "unfrozen");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] " + (frozen ? "Froze" : "Unfroze") + " " + npc.getDefinitionId()), true);
        return 1;
    }

    /** {@code remote delete <entity_uuid>} — discard a loaded NPC entity. */
    private static int remoteDelete(CommandContext<CommandSourceStack> ctx) {
        var npc = resolveRemoteTarget(ctx, "delete");
        if (npc == null) return 0;
        var label = npc.getDefinitionId();
        npc.discard();
        auditRemote(ctx, "remote.delete", npc, "discarded");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Deleted remote NPC entity of " + label), true);
        return 1;
    }

    /** {@code remote reset <entity_uuid>} — respawn a loaded NPC at its start pos. */
    private static int remoteReset(CommandContext<CommandSourceStack> ctx) {
        var npc = resolveRemoteTarget(ctx, "reset");
        if (npc == null) return 0;
        var level = ctx.getSource().getLevel();
        var start = npc.getStartPosition() != null ? npc.getStartPosition() : npc.blockPosition();
        var defId = npc.getDefinitionId();
        var fresh = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
        if (fresh == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Failed to create NPC entity"));
            return 0;
        }
        npc.discard();
        fresh.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        fresh.setDefinitionId(defId);
        fresh.setStartPosition(start);
        level.addFreshEntity(fresh);
        auditRemote(ctx, "remote.reset", fresh, "respawned at start");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Reset remote NPC " + defId + " to " + start.toShortString()), true);
        return 1;
    }

    /** {@code remote tp <entity_uuid>} — teleport a loaded NPC to the operator. */
    private static int remoteTeleport(CommandContext<CommandSourceStack> ctx) {
        var npc = resolveRemoteTarget(ctx, "tp");
        if (npc == null) return 0;
        var pos = ctx.getSource().getPosition();
        npc.teleportTo(pos.x, pos.y, pos.z);
        auditRemote(ctx, "remote.tp", npc, "teleported to operator");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Teleported " + npc.getDefinitionId() + " to the command source"), true);
        return 1;
    }

    /** {@code remote list [radius]} — bounded loaded-NPC listing near the source. */
    private static int remoteList(CommandContext<CommandSourceStack> ctx, int radius) {
        var level = ctx.getSource().getLevel();
        var origin = ctx.getSource().getPosition();
        var box = new net.minecraft.world.phys.AABB(
                origin.x - radius, level.getMinBuildHeight(), origin.z - radius,
                origin.x + radius, level.getMaxBuildHeight(), origin.z + radius);
        int found = 0;
        for (var entity : level.getEntitiesOfClass(
                com.storynpcs.entity.StoryNpcEntity.class, box)) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + entity.getUUID() + " " + entity.getDefinitionId()
                            + " @ " + entity.blockPosition().toShortString()), false);
            found++;
        }
        final int n = found;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] " + n + " loaded NPC entity(ies) within " + radius + " blocks"), false);
        return n;
    }

    // ── P9-4 player-data admin handlers (target SPacketPlayerData*) ─────────

    /** {@code playerdata read [player]} — detached progression summary. */
    private static int playerDataRead(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        var subject = resolvePlayer(ctx, target);
        if (subject == null) return 0;
        var service = mod(ctx).getApplicationService();
        var actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        var result = service.adminReadPlayerData(
                new com.storynpcs.service.PlayerProgressionActionRequest(
                        "playerdata.read",
                        actorUuid != null && actorUuid.equals(subject.getUUID()) ? "player" : "command",
                        actorUuid, subject.getUUID(), UUID.randomUUID(),
                        ctx.getSource().hasPermission(2) ? 2 : 0));
        if (!result.applied()) {
            String reason = result.authorization().decision() != null
                    ? result.authorization().decision().message() : "read denied";
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + reason));
            return 0;
        }
        var s = result.summary();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Player data for " + subject.getScoreboardName()
                        + ": factions=" + s.factionEntries()
                        + " quests=" + s.questEntries()
                        + " dialogueVisits=" + s.dialogueVisits()
                        + " mail=" + s.pendingMail()
                        + " (rev q=" + s.questRevision() + " f=" + s.factionRevision() + ")"), false);
        return 1;
    }

    /** {@code playerdata clear [player]} — delete the durable progression record. */
    private static int playerDataClear(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        var subject = resolvePlayer(ctx, target);
        if (subject == null) return 0;
        var service = mod(ctx).getApplicationService();
        var actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        var result = service.adminClearPlayerData(
                new com.storynpcs.service.PlayerProgressionActionRequest(
                        "playerdata.clear",
                        actorUuid != null && actorUuid.equals(subject.getUUID()) ? "player" : "command",
                        actorUuid, subject.getUUID(), UUID.randomUUID(),
                        ctx.getSource().hasPermission(2) ? 2 : 0));
        if (!result.applied()) {
            String reason = result.decision() != null ? result.decision().message() : "clear denied";
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + reason));
            return 0;
        }
        var mod = mod(ctx);
        if (mod != null) {
            mod.getEventPublisher().publish(new com.storynpcs.api.event.RemoteAdminAuditEvent(
                    "playerdata.clear", actorUuid, subject.getUUID().toString(), "record deleted"));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Cleared stored data for " + subject.getScoreboardName()), true);
        return 1;
    }

    /** {@code follower owner [npc_id]} — report loaded follower owners. */
    private static int followerOwnerReport(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Only players can inspect followers."));
            return 0;
        }
        NamespacedId filter = null;
        try {
            filter = getNamespacedId(ctx, "npc_id");
        } catch (IllegalArgumentException notProvided) {
            // zero-arg form — report all loaded followers
        }
        var level = ctx.getSource().getLevel();
        var followers = new java.util.ArrayList<com.storynpcs.entity.StoryNpcEntity>();
        for (var entity : level.getEntitiesOfClass(com.storynpcs.entity.StoryNpcEntity.class,
                player.getBoundingBox().inflate(256.0))) {
            var role = entity.getFollowerRole();
            if (role == null) continue;
            if (filter != null && !filter.toString().equals(entity.getDefinitionId())) continue;
            followers.add(entity);
        }
        if (followers.isEmpty()) {
            var scope = filter != null ? " for " + filter : "";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] No loaded followers" + scope + "."), false);
            return 0;
        }
        for (var entity : followers) {
            var role = entity.getFollowerRole();
            var owner = role != null && role.getOwnerUuid() != null
                    ? role.getOwnerUuid().toString() : "<none>";
            var label = entity.getDefinitionId() + " @ " + entity.blockPosition().toShortString();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] " + label + " owner=" + owner), false);
        }
        return followers.size();
    }

    /** {@code follower owner <npc_id> <player>} — reassign ownership via canonical op. */
    private static int followerOwnerSet(CommandContext<CommandSourceStack> ctx, ServerPlayer newOwner) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Only the current owner can reassign a follower."));
            return 0;
        }
        var npcId = getNamespacedId(ctx, "npc_id");
        var level = ctx.getSource().getLevel();
        var service = mod(ctx).getApplicationService();
        int changed = 0;
        for (var entity : level.getEntitiesOfClass(com.storynpcs.entity.StoryNpcEntity.class,
                player.getBoundingBox().inflate(256.0))) {
            if (!npcId.toString().equals(entity.getDefinitionId())) continue;
            var role = entity.getFollowerRole();
            if (role == null || !role.isOwnedBy(player.getUUID())) continue;
            var request = com.storynpcs.service.FollowerStateMutationRequest.setOwner(
                    "player", player.getUUID(), player.getUUID(), npcId,
                    newOwner.getUUID(), java.util.UUID.randomUUID(),
                    ctx.getSource().hasPermission(2) ? 2 : 0);
            var result = service.mutateFollowerState(request, role);
            if (result.applied()) changed++;
        }
        final int n = changed;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Reassigned " + n + " follower(s) of " + npcId
                        + " to " + newOwner.getScoreboardName()), true);
        return n;
    }

    private static int setNpcDialogue(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        NamespacedId dialogueId = getNamespacedId(ctx, "dialogue_id");
        StoryNpcs mod = mod(ctx);
        try {
            var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                    npc -> npc.setDialogueId(dialogueId));
            if (result.hasErrors()) {
                ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Failed to assign dialogue:\n" + result.formatReport(5)));
                return 0;
            }
            refreshLoadedEntities(ctx.getSource(), npcId);
            ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Assigned dialogue '" + dialogueId + "' to NPC '" + npcId + "' (persisted to YAML)."), true);
            return 1;
        } catch (NoSuchElementException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()));
            return 0;
        }
    }

    private static int setNpcFaction(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        NamespacedId factionId = getNamespacedId(ctx, "faction_id");
        StoryNpcs mod = mod(ctx);
        try {
            var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                    npc -> npc.setFactionId(factionId));
            if (result.hasErrors()) {
                ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Failed to assign faction:\n" + result.formatReport(5)));
                return 0;
            }
            refreshLoadedEntities(ctx.getSource(), npcId);
            ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Assigned faction '" + factionId + "' to NPC '" + npcId + "' (persisted to YAML)."), true);
            return 1;
        } catch (NoSuchElementException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()));
            return 0;
        }
    }

    private static int setNpcVariant(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String raw = StringArgumentType.getString(ctx, "type");
        StoryNpcs mod = mod(ctx);
        com.storynpcs.domain.npc.NpcVariant variant;
        try {
            variant = com.storynpcs.domain.npc.NpcVariant.fromWire(raw);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                def -> def.getDisplay().setVariant(variant));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        com.storynpcs.domain.npc.NpcVariant chosen = variant;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Set variant of '" + npcId + "' to '" + chosen.wire() + "' (persisted to YAML)."), true);
        return 1;
    }

    private static int setNpcPart(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        com.storynpcs.domain.npc.NpcCosmeticPart spec;
        try {
            var part = com.storynpcs.domain.npc.NpcBodyPart.fromWire(
                    StringArgumentType.getString(ctx, "part"));
            int type = IntegerArgumentType.getInteger(ctx, "type");
            String colorRaw = StringArgumentType.getString(ctx, "color")
                    .replaceFirst("^#", "");
            int color = Integer.parseInt(colorRaw, 16);
            var behavior = com.storynpcs.domain.npc.NpcCosmeticPart.PartBehavior.fromWire(
                    StringArgumentType.getString(ctx, "behavior"));
            spec = new com.storynpcs.domain.npc.NpcCosmeticPart(part, type, color, behavior);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()));
            return 0;
        }
        var result = mutateNpc(ctx, mod.getApplicationService(), npcId,
                def -> def.getDisplay().setPart(spec));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        com.storynpcs.domain.npc.NpcCosmeticPart applied = spec;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Set %s part of '%s' (type %d, #%06X, %s) — persisted to YAML.",
                applied.part().wire(), npcId, applied.type(), applied.color(),
                applied.behavior().wire())), true);
        return 1;
    }

    private static int clearNpcPart(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String raw = StringArgumentType.getString(ctx, "part");
        StoryNpcs mod = mod(ctx);
        var result = "clear".equalsIgnoreCase(raw)
                ? mutateNpc(ctx, mod.getApplicationService(), npcId,
                        def -> def.getDisplay().setParts(java.util.Map.of()))
                : mutateNpc(ctx, mod.getApplicationService(), npcId,
                        def -> def.getDisplay().clearPart(
                                com.storynpcs.domain.npc.NpcBodyPart.fromWire(raw)));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        refreshLoadedEntities(ctx.getSource(), npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Cleared " + ("clear".equalsIgnoreCase(raw) ? "all parts" : "part '" + raw + "'")
                        + " of '" + npcId + "' (persisted to YAML)."), true);
        return 1;
    }

    /** Runtime emote trigger — applies to every live projection of the definition. */
    private static int playNpcEmote(CommandContext<CommandSourceStack> ctx) {
        NamespacedId npcId = getNamespacedId(ctx, "npc_id");
        String raw = StringArgumentType.getString(ctx, "type");
        int duration = com.storynpcs.domain.npc.NpcEmote.DEFAULT_DURATION_TICKS;
        for (var node : ctx.getNodes()) {
            if ("duration".equals(node.getNode().getName())) {
                duration = IntegerArgumentType.getInteger(ctx, "duration");
                break;
            }
        }
        com.storynpcs.domain.npc.NpcEmote emote;
        try {
            emote = com.storynpcs.domain.npc.NpcEmote.fromWire(raw);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()));
            return 0;
        }
        CommandSourceStack source = ctx.getSource();
        int affected = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof StoryNpcEntity npc
                        && npcId.toString().equals(npc.getDefinitionId())) {
                    npc.playEmote(emote, duration);
                    affected++;
                }
            }
        }
        if (affected == 0) {
            source.sendFailure(Component.literal(
                    "[StoryNPCs] No live entities of '" + npcId + "' — spawn one first."));
            return 0;
        }
        int count = affected;
        com.storynpcs.domain.npc.NpcEmote chosen = emote;
        source.sendSuccess(() -> Component.literal("[StoryNPCs] Emote '" + chosen.wire()
                + "' playing on " + count + " entit(ies) of '" + npcId + "'."), true);
        return 1;
    }

    private static int spawnNpc(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        CommandSourceStack source = ctx.getSource();
        NamespacedId id = getNamespacedId(ctx, "npc_id");

        DefinitionRegistry reg = mod(ctx).getRegistry();
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

    /**
     * Spawns an owner-bound fake-living display puppet (#148). The puppet
     * renders as the chosen entity type, is bound to the executing player's
     * session, and self-discards once that owner is gone.
     */
    private static int spawnFakeLiving(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(
                    "[StoryNPCs] A fake-living puppet must be spawned by a player (owner binding)."));
            return 0;
        }
        ResourceLocation typeId = ResourceLocationArgument.getId(ctx, "entity_type");
        var typeOpt = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(typeId);
        if (typeOpt.isEmpty()) {
            source.sendFailure(Component.literal("[StoryNPCs] Unknown entity type: " + typeId));
            return 0;
        }
        ServerLevel level = source.getLevel();
        // A "fake living" must render as a living entity — probe the type so
        // markers/players/other non-living types can't leave invisible,
        // invulnerable, persistent ghosts behind.
        var probe = typeOpt.get().create(level);
        if (!(probe instanceof net.minecraft.world.entity.LivingEntity)) {
            source.sendFailure(Component.literal(
                    "[StoryNPCs] " + typeId + " is not a living entity type."));
            return 0;
        }
        var fake = new com.storynpcs.entity.FakeLivingEntity(
                com.storynpcs.entity.StoryNpcRegistry.NPC_FAKE_LIVING.get(), level);
        fake.setPos(player.getX() + 1.0, player.getY(), player.getZ() + 1.0);
        fake.setOwnerUuid(player.getUUID());
        fake.setDisplayEntityType(typeOpt.get());
        level.addFreshEntity(fake);
        source.sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Spawned fake living displaying '%s' (owner-bound; despawns when you leave).",
                typeId)), true);
        return 1;
    }

    private static int deleteNpc(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var result = mod.getApplicationService().deleteNpc(
                commandMutationRequest(ctx, mod.getApplicationService(), "npc", "delete", id));
        if (result.applied()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Deleted NPC definition '" + id + "' from registry. To remove in-world entities, use '/storynpcs npc despawn " + id + "'."), true);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("NPC deletion rejected: " + result.diagnostics().formatReport(3)));
            return 0;
        }
    }

    /**
     * One command to a working NPC: scaffold a valid YAML definition, validate + persist
     * it through the application service, then spawn it at the admin's feet in-game.
     * From the console the entity step is skipped and a clickable [Spawn] is offered.
     */
    private static int createNpc(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        if (mod.getRegistry().getNpc(id).isPresent()) {
            source.sendFailure(Component.literal("[StoryNPCs] NPC '" + id + "' already exists — use '/storynpcs npc info " + id + "'"));
            return 0;
        }

        String displayName = (name != null && !name.isBlank()) ? name.trim() : humanizeName(id.getPath());
        var def = new com.storynpcs.domain.npc.NpcDefinition(id, displayName);
        var result = mod.getApplicationService().createNpc(
                commandMutationRequest(ctx, mod.getApplicationService(), "npc", "create", id), def);
        if (!result.applied()) {
            source.sendFailure(Component.literal("[StoryNPCs] NPC scaffold rejected (nothing written):\n" + result.formatReport(10)));
            return 0;
        }

        final String fileName = com.storynpcs.yaml.YamlDefinitionWriter.fileNameFor(id);
        // In-game: spawn it right where the admin stands — zero extra steps to a working NPC
        if (source.getEntity() != null) {
            source.sendSuccess(() -> Component.literal("[StoryNPCs] Created NPC '" + displayName
                    + "' (" + id + ") — saved to npcs/" + fileName + ".yaml"), true);
            return spawnNpc(ctx, null);
        }
        // Console: definition exists but no position — offer the one-click spawn path
        MutableComponent line = Component.literal("[StoryNPCs] Created NPC '" + displayName
                + "' (" + id + ") — saved to npcs/" + fileName + ".yaml  ")
                .append(clickable("§a[Spawn]", "/storynpcs npc spawn " + id + " ",
                        ClickEvent.Action.SUGGEST_COMMAND, "Spawn '" + id + "' (add coordinates)"))
                .append(Component.literal(" "))
                .append(clickable("§b[Info]", "/storynpcs npc info " + id,
                        ClickEvent.Action.RUN_COMMAND, "View '" + id + "' details"));
        source.sendSuccess(() -> line, true);
        return 1;
    }

    /** "guard_captain" → "Guard Captain" — sensible display name when none is given. */
    private static String humanizeName(String path) {
        String[] parts = path.split("[^a-zA-Z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : path;
    }

    // ---------------------------------------------------------------------------
    // /storynpcs quickstart — zero-reading on-ramp: one demo NPC + all five wands.
    // ---------------------------------------------------------------------------

    /** Bundled starter NPC — preferred demo target when it has a working dialogue. */
    private static final NamespacedId QUICKSTART_SEEDED_NPC = NamespacedId.of("storynpcs:guard_captain");
    /** Throwaway NPC scaffolded when the bundled starter content is absent. */
    static final NamespacedId QUICKSTART_DEMO_NPC = NamespacedId.of("storynpcs:quickstart_demo");
    static final NamespacedId QUICKSTART_DEMO_DIALOGUE = NamespacedId.of("storynpcs:quickstart_dialogue");
    /** Reuse radius in blocks — a prior quickstart NPC within range is reused, never duplicated. */
    static final double QUICKSTART_REUSE_RADIUS = 32.0;
    /** Spawn distance in front of the sender. */
    private static final double QUICKSTART_SPAWN_OFFSET = 2.5;

    /**
     * Picks the NPC definition the demo should use, or {@code null} when neither the
     * seeded captain nor an already-scaffolded demo NPC is usable and scaffolding
     * is required.
     */
    static NamespacedId resolveDemoNpcId(DefinitionRegistry reg) {
        if (isTalkableDemo(reg, QUICKSTART_SEEDED_NPC)) return QUICKSTART_SEEDED_NPC;
        if (isTalkableDemo(reg, QUICKSTART_DEMO_NPC)) return QUICKSTART_DEMO_NPC;
        return null;
    }

    private static boolean isTalkableDemo(DefinitionRegistry reg, NamespacedId id) {
        return reg.getNpc(id)
                .filter(n -> n.getDialogueId() != null && reg.getDialogue(n.getDialogueId()).isPresent())
                .isPresent();
    }

    private static int quickstart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("[StoryNPCs] /storynpcs quickstart must be run by a player in-game"));
            return 0;
        }
        StoryNpcs mod = mod(ctx);
        DefinitionRegistry reg = mod.getRegistry();

        // 1. Demo definition: bundled starter content only — quickstart resolves
        // loaded YAML definitions and never constructs or persists them from Java.
        NamespacedId demoId = resolveDemoNpcId(reg);
        if (demoId == null) {
            source.sendFailure(Component.literal("[StoryNPCs] No talkable demo definition is loaded. Expected bundled"
                    + " YAML for " + QUICKSTART_SEEDED_NPC + " or " + QUICKSTART_DEMO_NPC
                    + " under world/storynpcs/definitions/ — check the startup loader diagnostics,"
                    + " then /storynpcs definitions reload."));
            return 0;
        }

        // 2. Spawn-or-reuse: a living quickstart NPC within radius is reused, never duplicated
        final NamespacedId finalDemoId = demoId;
        boolean alreadyNearby = !source.getLevel().getEntitiesOfClass(StoryNpcEntity.class,
                player.getBoundingBox().inflate(QUICKSTART_REUSE_RADIUS),
                e -> finalDemoId.toString().equals(e.getDefinitionId())).isEmpty();
        if (!alreadyNearby) {
            StoryNpcEntity entity = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(source.getLevel());
            if (entity == null) {
                source.sendFailure(Component.literal("[StoryNPCs] Failed to create NPC entity"));
                return 0;
            }
            Vec3 look = player.getLookAngle();
            Vec3 pos = player.position().add(look.x * QUICKSTART_SPAWN_OFFSET, 0.0, look.z * QUICKSTART_SPAWN_OFFSET);
            entity.setPos(pos.x, pos.y, pos.z);
            entity.setDefinitionId(demoId.toString());
            entity.setStartPosition(entity.blockPosition());
            source.getLevel().addFreshEntity(entity);
        }

        // 3. One of each wand — skip what the sender already carries (offhand included)
        int wandsGiven = 0;
        for (var wand : List.of(StoryNpcsItems.NPC_WAND, StoryNpcsItems.NPC_CLONER, StoryNpcsItems.NPC_PATH,
                StoryNpcsItems.NPC_MOUNTER, StoryNpcsItems.NPC_DIALOGUE_WAND)) {
            if (player.getInventory().hasAnyMatching(s -> s.is(wand.get()))) continue;
            ItemStack stack = new ItemStack(wand.get());
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            wandsGiven++;
        }

        // 4. Fixed three-step guide — sender only
        String npcName = reg.getNpc(demoId).map(n -> n.getDisplay().getName()).orElse(demoId.toString());
        String status = alreadyNearby
                ? "reused the '" + npcName + "' already nearby"
                : "spawned '" + npcName + "' next to you";
        final String header = "§6[StoryNPCs] Quickstart ready — " + status + "."
                + (wandsGiven == 0 ? " Wands already in your inventory." : "");
        source.sendSuccess(() -> Component.literal(header), false);
        source.sendSuccess(() -> Component.literal("§e 1. §fRight-click the NPC to talk — its dialogue is live."), false);
        source.sendSuccess(() -> Component.literal("§e 2. §fRight-click it with the §bDialogue Wand§f to open the graph editor."), false);
        source.sendSuccess(() -> Component.literal("§e 3. §f/storynpcs help lists every command."), false);
        return 1;
    }

    // Dialogue Handlers
    private static int listDialogues(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = mod(ctx).getRegistry();
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
        DefinitionRegistry reg = mod(ctx).getRegistry();
        var dOpt = reg.getDialogue(id);
        if (dOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Dialogue not found: " + id));
            return 0;
        }
        DialogueGraph d = dOpt.get();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("=== Dialogue: %s ('%s') ===", d.getId(), d.getTitle())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Entry Node: %s", d.getEntryNodeId())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Total Nodes: %d", d.getNodes().size())), false);
        var referencingNpcs = mod(ctx).getApplicationService().findNpcsReferencingDialogue(id);
        if (!referencingNpcs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Referenced by NPCs: %s", referencingNpcs)), false);
        }
        if (ctx.getSource().hasPermission(2)) {
            MutableComponent actions = Component.literal(" §7[ ")
                    .append(clickable("§bOpen Editor", "/storynpcs dialogue edit " + id,
                            ClickEvent.Action.RUN_COMMAND, "Open the visual editor for '" + id + "'"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§aStart", "/storynpcs dialogue start " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Start dialogue '" + id + "' (pick a player)"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§cDelete", "/storynpcs dialogue delete " + id,
                            ClickEvent.Action.SUGGEST_COMMAND, "Delete '" + id + "' definition (confirm with Enter)"))
                    .append(Component.literal(" §7]"));
            ctx.getSource().sendSuccess(() -> actions, false);
        }
        return 1;
    }

    private static int createDialogue(CommandContext<CommandSourceStack> ctx, String title) {
        CommandSourceStack source = ctx.getSource();
        NamespacedId id = getNamespacedId(ctx, "dialogue_id");
        StoryNpcs mod = mod(ctx);
        if (mod.getRegistry().getDialogue(id).isPresent()) {
            source.sendFailure(Component.literal("[StoryNPCs] Dialogue '" + id + "' already exists — use '/storynpcs dialogue edit " + id + "'"));
            return 0;
        }

        String dialogueTitle = (title != null && !title.isBlank()) ? title.trim() : humanizeName(id.getPath());
        var result = mod.getApplicationService().createDialogue(
                commandMutationRequest(ctx, mod.getApplicationService(), "dialogue", "create", id), dialogueTitle);
        if (!result.applied()) {
            source.sendFailure(Component.literal("[StoryNPCs] Dialogue scaffold rejected:\n" + result.formatReport(10)));
            return 0;
        }

        final String fileName = com.storynpcs.yaml.YamlDefinitionWriter.fileNameFor(id);
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            // In-game: open the visual editor right away for the new dialogue!
            var dialogueOpt = mod.getRegistry().getDialogue(id);
            if (dialogueOpt.isPresent()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new com.storynpcs.network.ClientboundDialogueEditorOpenPayload(
                                id.toString(),
                                com.storynpcs.domain.dialogue.DialogueGraphSerde.toJson(dialogueOpt.get()),
                                mod.getApplicationService().currentRevision("dialogue", id)));
            }
            source.sendSuccess(() -> Component.literal("[StoryNPCs] Created dialogue '" + dialogueTitle
                    + "' (" + id + ") — saved to dialogues/" + fileName + ".yaml and opened editor."), true);
            return 1;
        }

        // Console:
        MutableComponent line = Component.literal("[StoryNPCs] Created dialogue '" + dialogueTitle
                + "' (" + id + ") — saved to dialogues/" + fileName + ".yaml  ")
                .append(clickable("§b[Info]", "/storynpcs dialogue info " + id,
                        ClickEvent.Action.RUN_COMMAND, "View '" + id + "' details"));
        source.sendSuccess(() -> line, true);
        return 1;
    }

    private static int deleteDialogue(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "dialogue_id");
        StoryNpcs mod = mod(ctx);
        var referencingNpcs = mod.getApplicationService().findNpcsReferencingDialogue(id);
        var result = mod.getApplicationService().deleteDialogue(
                commandMutationRequest(ctx, mod.getApplicationService(), "dialogue", "delete", id));
        if (result.applied()) {
            if (!referencingNpcs.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.literal("§e[StoryNPCs] Warning: Deleted dialogue '" + id + "' was referenced by NPC(s): " + referencingNpcs + ". Assign them a new dialogue with '/storynpcs npc set dialogue <npc> <dialogue>'."), true);
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Deleted dialogue definition '" + id + "' from registry and disk."), true);
            }
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("Dialogue deletion rejected: " + result.diagnostics().formatReport(3)));
            return 0;
        }
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
            DialogueView view = mod(ctx).getApplicationService().startDialogue(
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
        StoryNpcs mod = mod(ctx);
        var dialogueOpt = mod.getRegistry().getDialogue(id);
        if (dialogueOpt.isEmpty()) {
            MutableComponent failMsg = Component.literal("[StoryNPCs] Dialogue not found: '" + id + "'  ")
                    .append(clickable("§a[Create & Edit]", "/storynpcs dialogue create " + id,
                            ClickEvent.Action.RUN_COMMAND, "Scaffold '" + id + "' and open visual editor immediately"));
            ctx.getSource().sendFailure(failMsg);
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
                        com.storynpcs.domain.dialogue.DialogueGraphSerde.toJson(dialogueOpt.get()),
                        mod.getApplicationService().currentRevision("dialogue", id)));
        ctx.getSource().sendSuccess(() -> Component.literal("[StoryNPCs] Opening visual dialogue editor for '" + id + "'."), false);
        return 1;
    }

    private static int listQuests(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = mod(ctx).getRegistry();
        Collection<Quest> quests = reg.getAllQuests();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- Quests (%d loaded) ---", quests.size())), false);
        for (Quest q : quests) {
            MutableComponent line = clickable(
                    String.format(" - %s: '%s' (%s, Objectives: %d)",
                            q.getId(), q.getTitle(), q.getCategory(), q.getObjectives().size()),
                    "/storynpcs quest info " + q.getId(), ClickEvent.Action.RUN_COMMAND,
                    "Click to view quest " + q.getId() + " details and actions");
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return quests.size();
    }

    private static int infoQuest(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        DefinitionRegistry reg = mod(ctx).getRegistry();
        var qOpt = reg.getQuest(id);
        if (qOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Quest not found: " + id));
            return 0;
        }
        Quest q = qOpt.get();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("=== Quest: %s ('%s') ===", q.getId(), q.getTitle())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Category: %s | Repeat: %s", q.getCategory(), q.getRepeatType())), false);
        if (q.getPrerequisites() != null && !q.getPrerequisites().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Prerequisites: %s", q.getPrerequisites())), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Objectives (%d):", q.getObjectives().size())), false);
        for (var obj : q.getObjectives()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("  - [%s] %s (Target: %s, Required: %d)",
                    obj.getType(), obj.getId(), obj.getTarget(), obj.getRequiredCount())), false);
        }
        if (q.getRewards() != null && !q.getRewards().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Rewards (%d):", q.getRewards().size())), false);
            for (var rew : q.getRewards()) {
                ctx.getSource().sendSuccess(() -> Component.literal(String.format("  - %s: %s (amount: %d)",
                        rew.getType(), rew.getTarget(), rew.getAmount())), false);
            }
        }
        if (ctx.getSource().hasPermission(2)) {
            MutableComponent actions = Component.literal(" §7[ ")
                    .append(clickable("§aStart Quest", "/storynpcs quest start " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Start quest '" + id + "' (pick a player)"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§eComplete Quest", "/storynpcs quest complete " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Force-complete quest '" + id + "' (pick a player)"))
                    .append(Component.literal(" §7]"));
            ctx.getSource().sendSuccess(() -> actions, false);
        }
        return 1;
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
            var service = mod(ctx).getApplicationService();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            var request = new com.storynpcs.service.QuestProgressionMutationRequest(
                    "command", actorUuid, player.getUUID(), id,
                    com.storynpcs.service.QuestProgressionMutationRequest.Action.START,
                    "", 0, service.currentQuestProgressionRevision(player.getUUID()), UUID.randomUUID(),
                    ctx.getSource().hasPermission(2) ? 2 : 0);
            var result = service.mutateQuestProgression(request);
            if (result.hasErrors()) {
                ctx.getSource().sendFailure(Component.literal("Failed to start quest: " + result.formatReport()));
                return 0;
            }
            if (!result.applied()) {
                ctx.getSource().sendFailure(Component.literal(result.formatReport()));
                return 0;
            }
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Started quest '%s' for %s", id, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to start quest: " + e.getMessage()));
            return 0;
        }
    }

    private static ServerPlayer teamActor(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
    }

    private static com.storynpcs.service.PlayerProgressionActionRequest teamRequest(
            CommandContext<CommandSourceStack> ctx, String operation, ServerPlayer subject) {
        ServerPlayer actor = teamActor(ctx);
        return new com.storynpcs.service.PlayerProgressionActionRequest(
                operation, actor != null ? "player" : "command",
                actor != null ? actor.getUUID() : null, subject.getUUID(),
                UUID.randomUUID(), ctx.getSource().hasPermission(2) ? 2 : 0);
    }

    private static int teamResult(CommandContext<CommandSourceStack> ctx,
                                  com.storynpcs.service.AuthorizedActionResult result,
                                  String success) {
        if (!result.applied()) {
            String reason = result.decision() != null && !result.decision().allowed()
                    ? result.decision().message() : "not applicable";
            ctx.getSource().sendFailure(Component.literal("Team action failed: " + reason));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(success), true);
        return 1;
    }

    private static int teamStatus(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = teamActor(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        var service = mod(ctx).getApplicationService();
        var store = service.getTeamProgressionStore();
        var prog = mod(ctx).getProgressionRepository().getOrCreate(player.getUUID());
        if (prog == null || prog.getTeamId() == null || store == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("Not in a team"), false);
            return 1;
        }
        try {
            var team = store.get(prog.getTeamId());
            if (team.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.literal("Not in a team"), false);
                return 1;
            }
            var t = team.get();
            StringBuilder sb = new StringBuilder("Team " + t.getTeamId()
                    + " — " + t.getMemberUuids().size() + " member(s)");
            if (player.getUUID().equals(t.getOwnerUuid())) sb.append(" (you own it)");
            t.getQuests().forEach((qid, state) -> sb.append("\n  ").append(qid)
                    .append(": ").append(state.getStatus()));
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Team state unavailable: " + e.getMessage()));
            return 0;
        }
    }

    private static int teamCreate(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = teamActor(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        var result = mod(ctx).getApplicationService()
                .teamCreate(teamRequest(ctx, "team.create", player));
        return teamResult(ctx, result, "Team created — invite members with /storynpcs team invite <player>");
    }

    private static int teamInvite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = teamActor(ctx);
        ServerPlayer invitee = EntityArgument.getPlayer(ctx, "player");
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        var result = mod(ctx).getApplicationService()
                .teamInvite(teamRequest(ctx, "team.invite", player), invitee.getUUID());
        return teamResult(ctx, result, "Invited " + invitee.getScoreboardName() + " to your team");
    }

    private static int teamJoin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = teamActor(ctx);
        ServerPlayer owner = EntityArgument.getPlayer(ctx, "owner");
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        var result = mod(ctx).getApplicationService()
                .teamJoin(teamRequest(ctx, "team.join", player), owner.getUUID());
        return teamResult(ctx, result, "Joined " + owner.getScoreboardName() + "'s team");
    }

    private static int teamLeave(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = teamActor(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        var result = mod(ctx).getApplicationService()
                .teamLeave(teamRequest(ctx, "team.leave", player));
        return teamResult(ctx, result, "Left the team");
    }

    private static int teamOwner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = teamActor(ctx);
        ServerPlayer newOwner = EntityArgument.getPlayer(ctx, "player");
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        var result = mod(ctx).getApplicationService()
                .teamTransferOwner(teamRequest(ctx, "team.owner", player), newOwner.getUUID());
        return teamResult(ctx, result, "Ownership transferred to " + newOwner.getScoreboardName());
    }

    private static int teamShare(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = teamActor(ctx);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Team commands require a player"));
            return 0;
        }
        boolean share = BoolArgumentType.getBool(ctx, "faction_points");
        var result = mod(ctx).getApplicationService()
                .teamSetSharing(teamRequest(ctx, "team.share", player), share);
        return teamResult(ctx, result,
                "Faction-point sharing " + (share ? "enabled" : "disabled") + " for your team");
    }

    private static int resetQuest(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
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
            var service = mod(ctx).getApplicationService();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            var request = com.storynpcs.service.QuestProgressionMutationRequest.reset(
                    "command", actorUuid, player.getUUID(), id,
                    service.currentQuestProgressionRevision(player.getUUID()), UUID.randomUUID(),
                    ctx.getSource().hasPermission(2) ? 2 : 0);
            var result = service.mutateQuestProgression(request);
            if (result.hasErrors()) {
                ctx.getSource().sendFailure(Component.literal("Failed to reset quest: " + result.formatReport()));
                return 0;
            }
            if (!result.applied()) {
                ctx.getSource().sendFailure(Component.literal(result.formatReport()));
                return 0;
            }
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    String.format("Reset quest '%s' for %s — it can be taken again", id, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to reset quest: " + e.getMessage()));
            return 0;
        }
    }

    /** {@code quest objectives <quest_id> [player]} — report per-objective progress. */
    private static int reportQuestObjectives(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = resolvePlayer(ctx, targetPlayer);
        if (player == null) return 0;
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        StoryNpcs mod = mod(ctx);
        var quest = mod.getRegistry().getQuest(id).orElse(null);
        if (quest == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + id));
            return 0;
        }
        var repo = mod.getProgressionRepository();
        var prog = repo.getOrCreate(player.getUUID());
        var state = prog.peekQuestState(id);
        var objectives = quest.getObjectives();
        if (objectives.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] Quest " + id + " defines no objectives."), false);
            return 0;
        }
        var finalPlayer = player;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Objectives for " + id + " (" + finalPlayer.getScoreboardName() + "):"), false);
        for (var objective : objectives) {
            int count = state.getCount(objective.getId());
            int required = objective.getRequiredCount();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + objective.getId() + ": " + count + "/" + required), false);
        }
        return objectives.size();
    }

    /** {@code quest progress <quest_id> <objective_id> <amount> [player]} — canonical progress set. */
    private static int setQuestProgress(CommandContext<CommandSourceStack> ctx, ServerPlayer targetPlayer) {
        ServerPlayer player = resolvePlayer(ctx, targetPlayer);
        if (player == null) return 0;
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        String objectiveId = StringArgumentType.getString(ctx, "objective_id");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        try {
            var service = mod(ctx).getApplicationService();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            var request = com.storynpcs.service.QuestProgressionMutationRequest.progress(
                    "command", actorUuid, player.getUUID(), id, objectiveId, amount,
                    service.currentQuestProgressionRevision(player.getUUID()), UUID.randomUUID());
            var result = service.mutateQuestProgression(request);
            if (!result.applied()) {
                ctx.getSource().sendFailure(Component.literal(
                        "Failed to set quest progress: " + result.formatReport()));
                return 0;
            }
            ServerPlayer finalPlayer = player;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "Set objective '%s' progress to %d on quest '%s' for %s",
                    objectiveId, amount, id, finalPlayer.getScoreboardName())), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to set quest progress: " + e.getMessage()));
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
            var service = mod(ctx).getApplicationService();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            QuestCompletionResult result = service.completeQuest(
                    new com.storynpcs.service.QuestCompletionMutationRequest(
                            "command", actorUuid, player.getUUID(), id,
                            service.currentQuestProgressionRevision(player.getUUID()), UUID.randomUUID(),
                            ctx.getSource().hasPermission(2) ? 2 : 0));
            ServerPlayer finalPlayer = player;
            CompletionFeedback feedback = completionFeedback(id, finalPlayer.getScoreboardName(), result);
            if (feedback.success()) {
                ctx.getSource().sendSuccess(() -> Component.literal(feedback.message()), true);
                return 1;
            }
            ctx.getSource().sendFailure(Component.literal(feedback.message()));
            return 0;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to complete quest: " + e.getMessage()));
            return 0;
        }
    }

    static CompletionFeedback completionFeedback(
            NamespacedId questId, String playerName, QuestCompletionResult result) {
        return switch (result.outcome()) {
            case COMPLETED -> new CompletionFeedback(true,
                    String.format("Completed quest '%s' for %s", questId, playerName));
            case ALREADY_COMPLETED -> new CompletionFeedback(true,
                    String.format("Quest '%s' was already completed for %s", questId, playerName));
            case REJECTED -> new CompletionFeedback(false,
                    String.format("Could not complete quest '%s' for %s: %s", questId, playerName, result.code()));
            case FAILED -> new CompletionFeedback(false,
                    String.format("Quest '%s' failed for %s after applying %d rewards: %s",
                            questId, playerName, result.rewardsApplied(), result.code()));
        };
    }

    record CompletionFeedback(boolean success, String message) {}

    /**
     * /storynpcs quest create — scaffolds a minimal valid quest (one placeholder
     * CUSTOM objective, required by the validator) through the service layer.
     */
    private static int createQuest(CommandContext<CommandSourceStack> ctx, String title) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        StoryNpcs mod = mod(ctx);
        var result = mod.getApplicationService().createQuest(
                commandMutationRequest(ctx, mod.getApplicationService(), "quest", "create", id), title);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest creation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Created quest '" + id + "'" + (title != null ? " ('" + title + "')" : "")
                        + " — persisted to YAML. Add real objectives via /storynpcs quest objective add "
                        + id + " <type> <target> <count>."), true);
        return 1;
    }

    /**
     * /storynpcs quest set — description/category/repeatType. repeatType is validated
     * against the domain enum; category is a free-form label per the domain model.
     */
    private static int setQuestField(CommandContext<CommandSourceStack> ctx, String field) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        String value = StringArgumentType.getString(ctx, "value").trim();
        StoryNpcs mod = mod(ctx);
        var questOpt = mod.getRegistry().getQuest(id);
        if (questOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + id));
            return 0;
        }
        Quest.RepeatType repeatType = null;
        if ("repeatType".equals(field)) {
            try {
                repeatType = Quest.RepeatType.fromString(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                ctx.getSource().sendFailure(Component.literal(
                        "[StoryNPCs] Invalid repeatType '" + value + "' — expected NORMAL, REPEATABLE, DAILY, WEEKLY, RESET, or INSTANT (legacy: ONCE)."));
                return 0;
            }
        } else if (!field.equals("description") && !field.equals("category")) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Unknown quest field: " + field));
            return 0;
        }
        Quest.RepeatType finalRepeatType = repeatType;
        var result = mutateQuest(ctx, mod.getApplicationService(), id, quest -> {
            switch (field) {
                case "description" -> quest.setDescription(value);
                case "category" -> quest.setCategory(value);
                case "repeatType" -> quest.setRepeatType(finalRepeatType);
                default -> throw new IllegalArgumentException("Unknown quest field: " + field);
            }
        });
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Set " + field + " of '" + id + "' to '" + value + "' (persisted to YAML)."), true);
        return 1;
    }

    /**
     * /storynpcs quest objective add — objective ids are auto-generated as
     * obj_1, obj_2, ... unique within the quest (the issue's grammar carries no
     * explicit id argument).
     */
    private static int addQuestObjective(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        String typeRaw = StringArgumentType.getString(ctx, "type");
        String target = StringArgumentType.getString(ctx, "target").trim();
        int count = IntegerArgumentType.getInteger(ctx, "requiredCount");
        StoryNpcs mod = mod(ctx);
        QuestObjective.Type type;
        try {
            type = QuestObjective.Type.valueOf(typeRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Invalid objective type '" + typeRaw
                            + "' — expected KILL_ENTITY, COLLECT_ITEM, VISIT_LOCATION, TALK_TO_NPC, or CUSTOM."));
            return 0;
        }
        if (target.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Objective target must not be blank."));
            return 0;
        }
        var questOpt = mod.getRegistry().getQuest(id);
        if (questOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + id));
            return 0;
        }
        String objId = nextObjectiveId(questOpt.get());
        var result = mutateQuest(ctx, mod.getApplicationService(), id,
                quest -> quest.getObjectives().add(new QuestObjective(objId, type, target, count)));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Added objective '%s' (%s %s x%d) to quest '%s' (persisted to YAML).",
                objId, type, target, count, id)), true);
        return 1;
    }

    /**
     * /storynpcs quest gui [quest_id] — opens the quest editor client-side.
     * Empty id opens the browsable list; a given id opens that quest directly.
     */
    private static int openQuestGui(CommandContext<CommandSourceStack> ctx, NamespacedId questId) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] The quest editor can only be opened by a player, not the console."));
            return 0;
        }
        StoryNpcs mod = mod(ctx);
        if (questId != null && mod.getRegistry().getQuest(questId).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + questId));
            return 0;
        }
        String questsJson = com.storynpcs.domain.quest.QuestSerde.toJsonList(
                java.util.List.copyOf(mod.getRegistry().getAllQuests()));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.storynpcs.network.ClientboundQuestEditorOpenPayload(
                        questId != null ? questId.toString() : "", questsJson,
                        questId != null ? mod.getApplicationService().currentRevision("quest", questId) : 0L,
                        com.storynpcs.editor.EditorRevisions.toJson(
                                mod.getApplicationService().currentRevisions("quest"))));
        ctx.getSource().sendSuccess(() -> Component.literal(questId != null
                ? "[StoryNPCs] Opening quest editor for '" + questId + "'."
                : "[StoryNPCs] Opening quest browser."), false);
        return 1;
    }

    private static String nextObjectiveId(Quest quest) {
        int n = 1;
        Set<String> existing = new HashSet<>();
        for (QuestObjective o : quest.getObjectives()) existing.add(o.getId());
        while (existing.contains("objective_" + n)) n++;
        return "objective_" + n;
    }

    /** /storynpcs quest objective remove — removes an objective by its generated id. */
    private static int removeQuestObjective(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        String objectiveId = StringArgumentType.getString(ctx, "objective_id");
        StoryNpcs mod = mod(ctx);
        var questOpt = mod.getRegistry().getQuest(id);
        if (questOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + id));
            return 0;
        }
        Quest quest = questOpt.get();
        QuestObjective removed = null;
        for (var o : quest.getObjectives()) {
            if (o.getId().equals(objectiveId)) { removed = o; break; }
        }
        if (removed == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Quest '" + id + "' has no objective '" + objectiveId + "'."));
            return 0;
        }
        var result = mutateQuest(ctx, mod.getApplicationService(), id,
                updated -> updated.getObjectives().removeIf(o -> o.getId().equals(objectiveId)));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Validation failed (a quest needs at least one objective):\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Removed objective '" + objectiveId + "' from quest '" + id + "' (persisted to YAML)."), true);
        return 1;
    }

    /**
     * /storynpcs quest reward add — rewards have no id field in the domain model;
     * removal is by 1-based list index as shown by `quest info`.
     */
    private static int addQuestReward(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        String typeRaw = StringArgumentType.getString(ctx, "type");
        String target = StringArgumentType.getString(ctx, "target").trim();
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        StoryNpcs mod = mod(ctx);
        QuestReward.Type type;
        try {
            type = QuestReward.Type.valueOf(typeRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Invalid reward type '" + typeRaw
                            + "' — expected EXPERIENCE, ITEM, FACTION_POINTS, or COMMAND."));
            return 0;
        }
        if (target.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Reward target must not be blank."));
            return 0;
        }
        var questOpt = mod.getRegistry().getQuest(id);
        if (questOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + id));
            return 0;
        }
        var result = mutateQuest(ctx, mod.getApplicationService(), id,
                quest -> quest.getRewards().add(new QuestReward(type, target, amount)));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Added reward #%d (%s %s x%d) to quest '%s' (persisted to YAML).",
                 questOpt.get().getRewards().size() + 1, type, target, amount, id)), true);
        return 1;
    }

    /** /storynpcs quest reward remove — removes a reward by its 1-based index. */
    private static int removeQuestReward(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        int index = IntegerArgumentType.getInteger(ctx, "index");
        StoryNpcs mod = mod(ctx);
        var questOpt = mod.getRegistry().getQuest(id);
        if (questOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest not found: " + id));
            return 0;
        }
        Quest quest = questOpt.get();
        if (index > quest.getRewards().size()) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] Quest '%s' only has %d reward(s) — index %d out of range.",
                    id, quest.getRewards().size(), index)));
            return 0;
        }
        QuestReward removed = quest.getRewards().get(index - 1);
        var result = mutateQuest(ctx, mod.getApplicationService(), id,
                updated -> updated.getRewards().remove(index - 1));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Removed reward #%d (%s %s) from quest '%s' (persisted to YAML).",
                index, removed.getType(), removed.getTarget(), id)), true);
        return 1;
    }

    /**
     * /storynpcs quest delete — removes the quest definition via the application
     * service (registry + YAML file). Dialogue graphs are captured BEFORE deletion
     * so the admin is warned about dangling START_QUEST actions.
     */
    private static int deleteQuest(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "quest_id");
        StoryNpcs mod = mod(ctx);
        var service = mod.getApplicationService();
        List<NamespacedId> referencing = service.findDialoguesStartingQuest(id);
        var result = service.deleteQuest(commandMutationRequest(ctx, service, "quest", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Quest deletion rejected: " + result.diagnostics().formatReport(3)));
            return 0;
        }
        if (!referencing.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] WARNING: " + referencing.size() + " dialogue(s) still START_QUEST '" + id
                            + "' — those actions are now dangling: " + referencing + "."), true);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Deleted quest '" + id + "' (removed from registry and YAML)."), true);
        return 1;
    }

    /** /storynpcs faction create — scaffolds a faction with domain defaults. */
    private static int createFaction(CommandContext<CommandSourceStack> ctx, String name) {
        NamespacedId id = getNamespacedId(ctx, "faction_id");
        StoryNpcs mod = mod(ctx);
        var result = mod.getApplicationService().createFaction(
                commandMutationRequest(ctx, mod.getApplicationService(), "faction", "create", id), name);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Faction creation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Created faction '" + id + "'" + (name != null ? " ('" + name + "')" : "")
                        + " — persisted to YAML. Tune thresholds via /storynpcs faction configure "
                        + id + " defaultPoints|hostileThreshold|friendlyThreshold <value>."), true);
        return 1;
    }

    /**
     * /storynpcs faction configure — defaultPoints/hostileThreshold/friendlyThreshold.
     * Threshold consistency (hostile &lt; friendly) is enforced by the service so the
     * GUI authoring path gets the same guarantee.
     */
    private static int configureFaction(CommandContext<CommandSourceStack> ctx, String field) {
        NamespacedId id = getNamespacedId(ctx, "faction_id");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        StoryNpcs mod = mod(ctx);
        var factionOpt = mod.getRegistry().getFaction(id);
        if (factionOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Faction not found: " + id));
            return 0;
        }
        if (!field.equals("defaultPoints") && !field.equals("hostileThreshold") && !field.equals("friendlyThreshold")) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Unknown faction field: " + field));
            return 0;
        }
        var result = mutateFaction(ctx, mod.getApplicationService(), id, faction -> {
            switch (field) {
                case "defaultPoints" -> faction.setDefaultPoints(value);
                case "hostileThreshold" -> faction.setHostileThreshold(value);
                case "friendlyThreshold" -> faction.setFriendlyThreshold(value);
                default -> throw new IllegalArgumentException("Unknown faction field: " + field);
            }
        });
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Set " + field + " of '" + id + "' to " + value + " (persisted to YAML)."), true);
        return 1;
    }

    /**
     * /storynpcs faction gui [faction_id] — opens the faction editor client-side.
     * Empty id opens the browsable list; a given id opens that faction directly.
     */
    private static int openFactionGui(CommandContext<CommandSourceStack> ctx, NamespacedId factionId) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] The faction editor can only be opened by a player, not the console."));
            return 0;
        }
        StoryNpcs mod = mod(ctx);
        if (factionId != null && mod.getRegistry().getFaction(factionId).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Faction not found: " + factionId));
            return 0;
        }
        String factionsJson = com.storynpcs.domain.faction.FactionSerde.toJsonList(
                java.util.List.copyOf(mod.getRegistry().getAllFactions()));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.storynpcs.network.ClientboundFactionEditorOpenPayload(
                        factionId != null ? factionId.toString() : "", factionsJson,
                        factionId != null ? mod.getApplicationService().currentRevision("faction", factionId) : 0L,
                        com.storynpcs.editor.EditorRevisions.toJson(
                                mod.getApplicationService().currentRevisions("faction"))));
        ctx.getSource().sendSuccess(() -> Component.literal(factionId != null
                ? "[StoryNPCs] Opening faction editor for '" + factionId + "'."
                : "[StoryNPCs] Opening faction browser."), false);
        return 1;
    }

    /**
     * /storynpcs faction delete — repairs references and deletes the faction in one
     * canonical service operation, with a detached-snapshot rollback on write failure.
     */
    private static int deleteFaction(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "faction_id");
        StoryNpcs mod = mod(ctx);
        var service = mod.getApplicationService();
        NamespacedId fallback = null;
        try {
            fallback = getNamespacedId(ctx, "fallback_id");
        } catch (IllegalArgumentException noFallbackArg) {
            // Overload without the fallback argument — plan must be viable without one.
        }
        // FactionDeletionPlanner: primary NPC bindings need a fallback,
        // matrix entries are stripped, and other dependents must be repaired first.
        var registry = mod.getRegistry();
        var npcs = new java.util.ArrayList<>(registry.getAllNpcs());
        var factions = new java.util.ArrayList<>(registry.getAllFactions());
        var planner = new com.storynpcs.domain.faction.FactionDeletionPlanner();
        var plan = planner.plan(id, npcs, new java.util.ArrayList<>(registry.getAllTemplates()), factions,
                new java.util.ArrayList<>(registry.getAllDialogues()),
                new java.util.ArrayList<>(registry.getAllQuests()),
                new java.util.ArrayList<>(registry.getAllTransportLocations()), fallback);
        if (!plan.viable()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Faction deletion blocked:\n " + String.join("\n ", plan.diagnostics())));
            return 0;
        }
        var result = service.deleteFactionWithRepairs(
                commandMutationRequest(ctx, service, "faction", "delete", id),
                plan.fallbackFactionId());
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Faction deletion rejected: " + result.formatReport(5)));
            return 0;
        }
        if (plan.hasReferences()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] Repaired " + plan.references().size()
                            + " faction reference(s) before deletion."), true);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[StoryNPCs] Deleted faction '" + id + "' (removed from registry and YAML)."), true);
        return 1;
    }

    // ── Behavior rule handlers (issue #19) ──────────────────────────────────

    private static final List<String> RULE_CONDITION_TYPES = List.of(
            "always", "actor_is_player", "faction_standing", "health_percent", "strike_count");
    private static final List<String> RULE_ACTION_TYPES = List.of(
            "send_message", "add_threat", "shout_alert", "yield_combat", "change_stance", "adjust_faction");

    /** /storynpcs npc rule list — readable rule summary with clickable remove. */
    private static int listNpcRules(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }
        List<com.storynpcs.domain.rule.BehaviorRule> rules = npcOpt.get().getRules();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("=== Rules on '%s' (%d) ===", id, rules.size())), false);
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            final int idx = i + 1;
            MutableComponent line = Component.literal(String.format(
                    " §7[%d] §f%s §7if §b%s §7→ §a%s", idx,
                    rule.getTrigger(), describeRuleConditions(rule), describeRuleActions(rule)))
                    .append(clickable(" §c[Remove]",
                            "/storynpcs npc rule remove " + id + " " + idx,
                            ClickEvent.Action.SUGGEST_COMMAND, "Remove rule #" + idx + " from '" + id + "'"));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (rules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    " §7(none — add one via /storynpcs npc rule add)"), false);
        }
        return rules.size();
    }

    /** /storynpcs npc rule remove — 1-based index into npc.getRules(). */
    private static int removeNpcRule(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        int index = IntegerArgumentType.getInteger(ctx, "index");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }
        NpcDefinition npc = npcOpt.get();
        List<com.storynpcs.domain.rule.BehaviorRule> rules = npc.getRules();
        if (index < 1 || index > rules.size()) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] NPC '%s' only has %d rule(s) — index %d out of range.",
                    id, rules.size(), index)));
            return 0;
        }
        var removed = rules.get(index - 1);
        var result = mutateNpc(ctx, mod.getApplicationService(), id,
                updated -> updated.getRules().remove(index - 1));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Removed rule #%d (%s → %s) from NPC '%s' (persisted to YAML).",
                index, describeRuleConditions(removed), describeRuleActions(removed), id)), true);
        return 1;
    }

    /** /storynpcs npc rule add — builds a one-condition/one-action rule and persists via saveNpc. */
    private static int addNpcRule(CommandContext<CommandSourceStack> ctx, String cond, String condOp, String act) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }

        com.storynpcs.domain.rule.TriggerType trigger;
        String triggerRaw = StringArgumentType.getString(ctx, "trigger");
        try {
            trigger = com.storynpcs.domain.rule.TriggerType.valueOf(triggerRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Unknown trigger '" + triggerRaw + "'. Valid: on_damaged, on_witness_assault, "
                            + "on_interact, on_health_percent_drop, on_target_lost, on_tick, on_yield"));
            return 0;
        }

        com.storynpcs.domain.rule.condition.RuleCondition condition;
        com.storynpcs.domain.rule.action.RuleAction action;
        try {
            condition = buildRuleCondition(ctx, cond, condOp);
            action = buildRuleAction(ctx, act);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()
                    + " Conditions: " + RULE_CONDITION_TYPES + " Actions: " + RULE_ACTION_TYPES));
            return 0;
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Bad rule argument: " + e.getMessage()));
            return 0;
        }

        NpcDefinition npc = npcOpt.get();
        var rule = new com.storynpcs.domain.rule.BehaviorRule(nextRuleId(npc), trigger);
        if (condition != null) {
            rule.addCondition(condition);
        }
        rule.addAction(action);

        var result = mutateNpc(ctx, mod.getApplicationService(), id,
                updated -> updated.getRules().add(rule));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Added rule '%s' to '%s': on %s if %s → %s (persisted to YAML).",
                rule.getId(), id, trigger, describeRuleConditions(rule), describeRuleActions(rule))), true);
        return 1;
    }

    // ---- npc ability commands (#147) ----

    private static com.storynpcs.domain.ability.AbilityTrigger defaultTrigger(
            com.storynpcs.domain.ability.AbilityType type) {
        return switch (type) {
            case BLOCK -> com.storynpcs.domain.ability.AbilityTrigger.DAMAGED;
            case SMASH -> com.storynpcs.domain.ability.AbilityTrigger.ATTACK;
            default -> com.storynpcs.domain.ability.AbilityTrigger.UPDATE;
        };
    }

    private static com.storynpcs.domain.ability.AbilityTrigger parseTrigger(
            com.storynpcs.domain.ability.AbilityType type, CommandContext<CommandSourceStack> ctx) {
        String raw;
        try {
            raw = StringArgumentType.getString(ctx, "trigger");
        } catch (IllegalArgumentException notBound) {
            return defaultTrigger(type);
        }
        var trigger = com.storynpcs.domain.ability.AbilityTrigger.valueOf(raw.trim().toUpperCase());
        if (!type.allows(trigger)) {
            throw new IllegalArgumentException("'" + type + "' cannot use trigger '" + trigger
                    + "' — allowed: " + type.allowedTriggers());
        }
        return trigger;
    }

    private static int addNpcAbility(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        if (mod.getRegistry().getNpc(id).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }

        com.storynpcs.domain.ability.AbilityType type;
        try {
            type = com.storynpcs.domain.ability.AbilityType.valueOf(
                    StringArgumentType.getString(ctx, "type").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Unknown ability type. Valid: block, pull, push, smash, snare, teleport"));
            return 0;
        }

        com.storynpcs.domain.ability.AbilityTrigger trigger;
        try {
            trigger = parseTrigger(type, ctx);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + e.getMessage()));
            return 0;
        }

        long cooldown;
        double chance;
        try {
            cooldown = IntegerArgumentType.getInteger(ctx, "cooldown_ticks");
        } catch (IllegalArgumentException notBound) {
            cooldown = 40;
        }
        try {
            chance = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "chance");
        } catch (IllegalArgumentException notBound) {
            chance = 1.0;
        }

        var ability = new com.storynpcs.domain.ability.NpcAbility(type, trigger);
        ability.setCooldownTicks(cooldown);
        ability.setChance(chance);

        var result = mutateNpc(ctx, mod.getApplicationService(), id,
                updated -> updated.getAbilities().add(ability));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        long finalCooldown = cooldown;
        double finalChance = chance;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Added %s ability (on %s, cooldown %dt, chance %.2f) to '%s' (persisted to YAML).",
                type, trigger, finalCooldown, finalChance, id)), true);
        return 1;
    }

    private static int setNpcAbilityParam(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }
        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        var abilities = npcOpt.get().getAbilities();
        if (index < 0 || index >= abilities.size()) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] Ability index out of range — '%s' has %d abilities.",
                    id, abilities.size())));
            return 0;
        }
        String param = StringArgumentType.getString(ctx, "param").trim().toLowerCase();
        double value = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "value");

        java.util.function.Consumer<com.storynpcs.domain.ability.NpcAbility> setter =
                switch (param) {
                    case "range" -> a -> a.setRange(value);
                    case "strength" -> a -> a.setStrength(value);
                    case "damage_multiplier" -> a -> a.setDamageMultiplier(value);
                    case "duration_ticks" -> a -> a.setDurationTicks((int) value);
                    case "amplifier" -> a -> a.setAmplifier((int) value);
                    case "bonus_damage" -> a -> a.setBonusDamage(value);
                    case "cooldown_ticks" -> a -> a.setCooldownTicks((long) value);
                    case "chance" -> a -> a.setChance(value);
                    default -> null;
                };
        if (setter == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Unknown param '" + param + "'. Valid: range, strength, "
                            + "damage_multiplier, duration_ticks, amplifier, bonus_damage, "
                            + "cooldown_ticks, chance"));
            return 0;
        }

        var result = mutateNpc(ctx, mod.getApplicationService(), id,
                updated -> setter.accept(updated.getAbilities().get(index)));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Set %s=%s on ability #%d of '%s' (persisted to YAML).",
                param, value, index + 1, id)), true);
        return 1;
    }

    private static int removeNpcAbility(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }
        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        var abilities = npcOpt.get().getAbilities();
        if (index < 0 || index >= abilities.size()) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] Ability index out of range — '%s' has %d abilities.",
                    id, abilities.size())));
            return 0;
        }
        var removed = abilities.get(index);
        var result = mutateNpc(ctx, mod.getApplicationService(), id,
                updated -> updated.getAbilities().remove(index));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Removed %s ability #%d from '%s' (persisted to YAML).",
                removed.getType(), index + 1, id)), true);
        return 1;
    }

    private static int listNpcAbilities(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "npc_id");
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return 0;
        }
        var abilities = npcOpt.get().getAbilities();
        if (abilities.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] '" + id + "' has no abilities — add one via "
                            + "/storynpcs npc ability add"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("[StoryNPCs] Abilities of '" + id + "':");
        for (int i = 0; i < abilities.size(); i++) {
            var a = abilities.get(i);
            sb.append(String.format("%n  #%d %s on %s (cooldown %dt, chance %.2f)",
                    i + 1, a.getType(), a.getTrigger(), a.getCooldownTicks(), a.getChance()));
            if (a.getRange() != null) sb.append(String.format(" range=%.1f", a.getRange()));
            if (a.getStrength() != null) sb.append(String.format(" strength=%.1f", a.getStrength()));
            if (a.getDamageMultiplier() != null) sb.append(String.format(" dmgMult=%.2f", a.getDamageMultiplier()));
            if (a.getDurationTicks() != null) sb.append(String.format(" dur=%dt", a.getDurationTicks()));
            if (a.getAmplifier() != null) sb.append(String.format(" amp=%d", a.getAmplifier()));
            if (a.getBonusDamage() != null) sb.append(String.format(" bonus=%.1f", a.getBonusDamage()));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // ---- npc trade / bank role commands ----

    private static int enableTrader(CommandContext<CommandSourceStack> ctx, String marketName) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        if (npc.getTrader() != null) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] NPC '%s' is already a trader ('%s') — use 'npc trade disable' first.",
                    npc.getId(), npc.getTrader().getMarketName())));
            return 0;
        }
        var trader = marketName != null
                ? new com.storynpcs.domain.role.trader.TraderRole(marketName)
                : new com.storynpcs.domain.role.trader.TraderRole();
        var result = mutateNpc(ctx, mod(ctx).getApplicationService(), npc.getId(), updated ->
                updated.setTrader(trader));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(formatTraderEnabledReceipt(npc.getId(), trader)), true);
        return 1;
    }

    static String formatTraderEnabledReceipt(
            NamespacedId npcId, com.storynpcs.domain.role.trader.TraderRole trader) {
        return String.format(
                "[StoryNPCs] NPC '%s' is now a trader ('%s'). Add stock via 'npc trade add'; players trade by right-clicking the NPC (when it has no dialogue).",
                npcId, trader.getMarketName());
    }

    private static int disableTrader(CommandContext<CommandSourceStack> ctx) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        if (npc.getTrader() == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC '" + npc.getId() + "' is not a trader."));
            return 0;
        }
        var removed = npc.getTrader();
        var result = mutateNpc(ctx, mod(ctx).getApplicationService(), npc.getId(), updated -> updated.setTrader(null));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Removed trader role ('%s') from NPC '%s' (persisted to YAML).",
                removed.getMarketName(), npc.getId())), true);
        return 1;
    }

    private static int listTrades(CommandContext<CommandSourceStack> ctx) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        var trader = npc.getTrader();
        if (trader == null) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] NPC '%s' is not a trader — enable via 'npc trade enable'.", npc.getId())));
            return 0;
        }
        var listings = trader.getListings();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "=== Trades on '%s' — %s (%d) ===", npc.getId(), trader.getMarketName(), listings.size())), false);
        for (int i = 0; i < listings.size(); i++) {
            final int idx = i + 1;
            MutableComponent line = Component.literal(String.format(
                    " §7[%d] §f%s", idx, com.storynpcs.domain.role.trader.TradeSummaries.describe(listings.get(i))))
                    .append(clickable(" §c[Remove]",
                            "/storynpcs npc trade remove " + npc.getId() + " " + idx,
                            ClickEvent.Action.SUGGEST_COMMAND, "Remove listing #" + idx + " from '" + npc.getId() + "'"));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (listings.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    " §7(none — add one via /storynpcs npc trade add " + npc.getId()
                            + " <offer_item> <offer_count> <price_item> <price_count>)"), false);
        }
        return listings.size();
    }

    private static int addTrade(CommandContext<CommandSourceStack> ctx, int maxUses) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        var offerItem = ResourceLocationArgument.getId(ctx, "offer_item");
        var priceItem = ResourceLocationArgument.getId(ctx, "price_item");
        int offerCount = IntegerArgumentType.getInteger(ctx, "offer_count");
        int priceCount = IntegerArgumentType.getInteger(ctx, "price_count");

        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(offerItem).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Unknown offer item: " + offerItem));
            return 0;
        }
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(priceItem).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Unknown price item: " + priceItem));
            return 0;
        }

        var listing = new com.storynpcs.domain.role.trader.TradeListing(
                offerItem.toString(), offerCount, priceItem.toString(), priceCount);
        listing.setMaxUses(maxUses);
        var result = mutateNpc(ctx, mod(ctx).getApplicationService(), npc.getId(), updated -> {
            var trader = updated.getTrader();
            if (trader == null) {
                trader = new com.storynpcs.domain.role.trader.TraderRole();
                updated.setTrader(trader);
            }
            trader.addListing(listing);
        });
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Added trade to NPC '%s': %s (persisted to YAML).",
                npc.getId(), com.storynpcs.domain.role.trader.TradeSummaries.describe(listing))), true);
        return 1;
    }

    private static int removeTrade(CommandContext<CommandSourceStack> ctx) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        int index = IntegerArgumentType.getInteger(ctx, "index");
        var trader = npc.getTrader();
        if (trader == null || trader.getListings().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC '" + npc.getId() + "' has no trade listings."));
            return 0;
        }
        var listings = trader.getListings();
        if (index > listings.size()) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] NPC '%s' only has %d listing(s) — index %d out of range.",
                    npc.getId(), listings.size(), index)));
            return 0;
        }
        var removed = listings.get(index - 1);
        var result = mutateNpc(ctx, mod(ctx).getApplicationService(), npc.getId(), updated ->
                updated.getTrader().removeListing(index - 1));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Removed listing #%d (%s) from NPC '%s' (persisted to YAML).",
                index, com.storynpcs.domain.role.trader.TradeSummaries.describe(removed), npc.getId())), true);
        return 1;
    }

    private static int enableBanker(CommandContext<CommandSourceStack> ctx, String bankName) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        if (npc.getBanker() != null) {
            ctx.getSource().sendFailure(Component.literal(String.format(
                    "[StoryNPCs] NPC '%s' is already a banker ('%s') — use 'npc bank disable' first.",
                    npc.getId(), npc.getBanker().getBankName())));
            return 0;
        }
        var banker = bankName != null
                ? new com.storynpcs.domain.role.banker.BankerRole(bankName)
                : new com.storynpcs.domain.role.banker.BankerRole();
        var result = mutateNpc(ctx, mod(ctx).getApplicationService(), npc.getId(), updated ->
                updated.setBanker(banker));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(formatBankerEnabledReceipt(npc.getId(), banker)), true);
        return 1;
    }

    static String formatBankerEnabledReceipt(
            NamespacedId npcId, com.storynpcs.domain.role.banker.BankerRole banker) {
        return String.format(
                "[StoryNPCs] NPC '%s' is now a banker ('%s', %d max tabs). Players open the vault by right-clicking the NPC (when it has no dialogue).",
                npcId, banker.getBankName(), banker.getMaxTabs());
    }

    private static int disableBanker(CommandContext<CommandSourceStack> ctx) {
        NpcDefinition npc = requireNpc(ctx, "npc_id");
        if (npc == null) return 0;
        if (npc.getBanker() == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC '" + npc.getId() + "' is not a banker."));
            return 0;
        }
        var removed = npc.getBanker();
        var result = mutateNpc(ctx, mod(ctx).getApplicationService(), npc.getId(), updated -> updated.setBanker(null));
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Validation failed:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Removed banker role ('%s') from NPC '%s' (persisted to YAML).",
                removed.getBankName(), npc.getId())), true);
        return 1;
    }

    private static int shareBankAccess(CommandContext<CommandSourceStack> ctx, String membersRaw) {
        try {
            ServerPlayer owner = EntityArgument.getPlayer(ctx, "owner");
            String policyRaw = StringArgumentType.getString(ctx, "policy").toUpperCase(Locale.ROOT);
            com.storynpcs.domain.role.banker.BankVault.AccessPolicy policy;
            try {
                policy = com.storynpcs.domain.role.banker.BankVault.AccessPolicy.valueOf(policyRaw);
            } catch (IllegalArgumentException invalid) {
                ctx.getSource().sendFailure(Component.literal(
                        "[StoryNPCs] Invalid access policy '" + policyRaw + "' — expected PRIVATE or SHARED."));
                return 0;
            }
            java.util.Set<UUID> members = new java.util.LinkedHashSet<>();
            if (membersRaw != null && !membersRaw.isBlank()) {
                for (String token : membersRaw.trim().split("[,\\s]+")) {
                    if (token.isBlank()) continue;
                    try {
                        members.add(UUID.fromString(token));
                    } catch (IllegalArgumentException invalid) {
                        ctx.getSource().sendFailure(Component.literal(
                                "[StoryNPCs] Invalid member UUID: '" + token + "'."));
                        return 0;
                    }
                }
            }
            var mod = mod(ctx);
            var bankRepo = mod.getBankRepository();
            if (bankRepo == null) {
                ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Banking is unavailable right now."));
                return 0;
            }
            long revision = bankRepo.getOrCreate(owner.getUUID()).getRevision();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            var request = new com.storynpcs.service.BankAccessMutationRequest(
                    "command", actorUuid, owner.getUUID(), policy, members,
                    revision, UUID.randomUUID(), ctx.getSource().hasPermission(2) ? 2 : 0);
            var result = mod.getApplicationService().configureBankAccess(request, bankRepo);
            if (result.hasErrors() || !result.applied()) {
                ctx.getSource().sendFailure(Component.literal(
                        "[StoryNPCs] Vault sharing update rejected: " + result.formatReport()));
                return 0;
            }
            String memberText = policy == com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED
                    ? " — members: " + (members.isEmpty() ? "(none)" : members.size() + " listed")
                    : "";
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "[StoryNPCs] Vault of %s set to %s%s.", owner.getScoreboardName(), policy, memberText)), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Failed to update vault sharing: " + e.getMessage()));
            return 0;
        }
    }

    private static com.storynpcs.service.MutationRequest commandMutationRequest(
            CommandContext<CommandSourceStack> ctx,
            StoryNpcsApplicationService service,
            String family,
            String action,
            NamespacedId targetId) {
        CommandSourceStack source = ctx.getSource();
        String actorType = source.getEntity() instanceof ServerPlayer player
                ? "player:" + player.getUUID()
                : source.getEntity() == null ? "console" : "command";
        String capability = family + ("delete".equals(action) ? ".delete" : ".mutate");
        return new com.storynpcs.service.MutationRequest(
                family + "." + action, actorType, capability, targetId,
                service.currentRevision(family, targetId), java.util.UUID.randomUUID(),
                source.hasPermission(2) ? 2 : 0);
    }

    private static com.storynpcs.service.CanonicalMutationResult mutateNpc(
            CommandContext<CommandSourceStack> ctx,
            StoryNpcsApplicationService service,
            NamespacedId id,
            java.util.function.Consumer<NpcDefinition> mutation) {
        return service.mutateNpc(commandMutationRequest(ctx, service, "npc", "mutate", id), mutation);
    }

    private static com.storynpcs.service.CanonicalMutationResult mutateQuest(
            CommandContext<CommandSourceStack> ctx,
            StoryNpcsApplicationService service,
            NamespacedId id,
            java.util.function.Consumer<Quest> mutation) {
        return service.mutateQuest(commandMutationRequest(ctx, service, "quest", "mutate", id), mutation);
    }

    private static com.storynpcs.service.CanonicalMutationResult mutateFaction(
            CommandContext<CommandSourceStack> ctx,
            StoryNpcsApplicationService service,
            NamespacedId id,
            java.util.function.Consumer<Faction> mutation) {
        return service.mutateFaction(commandMutationRequest(ctx, service, "faction", "mutate", id), mutation);
    }

    /** Shared lookup: resolves the npc_id argument to a loaded definition, messaging failures. */
    private static NpcDefinition requireNpc(CommandContext<CommandSourceStack> ctx, String argName) {
        NamespacedId id = getNamespacedId(ctx, argName);
        StoryNpcs mod = mod(ctx);
        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + id));
            return null;
        }
        return npcOpt.get();
    }

    /** Builds the parsed condition from the bound literal + args, or null for 'always'. */
    private static com.storynpcs.domain.rule.condition.RuleCondition buildRuleCondition(
            CommandContext<CommandSourceStack> ctx, String cond, String condOp) throws CommandSyntaxException {
        switch (cond) {
            case "always" -> { return null; }
            case "actor_is_player" -> { return new com.storynpcs.domain.rule.condition.ActorIsPlayerCondition(true); }
            case "faction_standing" -> {
                NamespacedId faction = getNamespacedId(ctx, "c_faction");
                String raw = StringArgumentType.getString(ctx, "c_standing");
                com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing standing;
                try {
                    standing = com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing
                            .valueOf(raw.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown standing '" + raw + "' — use hostile, neutral or friendly.");
                }
                return new com.storynpcs.domain.rule.condition.FactionStandingCondition(faction, standing);
            }
            case "health_percent" -> {
                var op = "gt".equals(condOp)
                        ? com.storynpcs.domain.rule.condition.HealthPercentCondition.Operator.GREATER_THAN
                        : com.storynpcs.domain.rule.condition.HealthPercentCondition.Operator.LESS_THAN_OR_EQUAL;
                return new com.storynpcs.domain.rule.condition.HealthPercentCondition(
                        op, com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "c_threshold"));
            }
            case "strike_count" -> {
                var op = "gt".equals(condOp)
                        ? com.storynpcs.domain.rule.condition.StrikeCountCondition.Operator.GREATER_THAN
                        : com.storynpcs.domain.rule.condition.StrikeCountCondition.Operator.LESS_THAN_OR_EQUAL;
                return new com.storynpcs.domain.rule.condition.StrikeCountCondition(
                        op, IntegerArgumentType.getInteger(ctx, "c_threshold"));
            }
            default -> throw new IllegalArgumentException("Unknown condition type '" + cond + "'.");
        }
    }

    /** Builds the parsed action from the bound literal + args. */
    private static com.storynpcs.domain.rule.action.RuleAction buildRuleAction(
            CommandContext<CommandSourceStack> ctx, String act) throws CommandSyntaxException {
        switch (act) {
            case "send_message":
                return new com.storynpcs.domain.rule.action.SendMessageAction(
                        StringArgumentType.getString(ctx, "text"));
            case "add_threat":
                return new com.storynpcs.domain.rule.action.AddThreatAction(optDouble(ctx, "amount", 100.0));
            case "shout_alert":
                return new com.storynpcs.domain.rule.action.ShoutAlertAction(
                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "radius"),
                        StringArgumentType.getString(ctx, "message"));
            case "yield_combat":
                return new com.storynpcs.domain.rule.action.YieldCombatAction(
                        optDouble(ctx, "fraction", 0.50), optString(ctx, "dialogue", "I yield! Well fought."));
            case "change_stance": {
                String raw = StringArgumentType.getString(ctx, "stance");
                try {
                    return new com.storynpcs.domain.rule.action.ChangeStanceAction(
                            com.storynpcs.domain.npc.TacticalStance.valueOf(raw.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown stance '" + raw
                            + "' — use passive, retaliate_only, defensive, guard or aggressive.");
                }
            }
            case "adjust_faction":
                return new com.storynpcs.domain.rule.action.AdjustFactionAction(
                        getNamespacedId(ctx, "faction_id"), IntegerArgumentType.getInteger(ctx, "delta"));
            default:
                throw new IllegalArgumentException("Unknown action type '" + act + "'.");
        }
    }

    private static double optDouble(CommandContext<CommandSourceStack> ctx, String name, double def) {
        try {
            return com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static String optString(CommandContext<CommandSourceStack> ctx, String name, String def) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** Lowest unused rule_N id on the NPC. */
    private static String nextRuleId(NpcDefinition npc) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var r : npc.getRules()) {
            if (r.getId() != null) ids.add(r.getId());
        }
        int n = 1;
        while (ids.contains("rule_" + n)) n++;
        return "rule_" + n;
    }

    private static String describeRuleConditions(com.storynpcs.domain.rule.BehaviorRule rule) {
        return com.storynpcs.editor.RuleSummaries.describeConditions(rule);
    }

    private static String describeRuleActions(com.storynpcs.domain.rule.BehaviorRule rule) {
        return com.storynpcs.editor.RuleSummaries.describeActions(rule);
    }

    // Faction Handlers
    private static int listFactions(CommandContext<CommandSourceStack> ctx) {
        DefinitionRegistry reg = mod(ctx).getRegistry();
        Collection<Faction> factions = reg.getAllFactions();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("--- Factions (%d loaded) ---", factions.size())), false);
        for (Faction f : factions) {
            MutableComponent line = clickable(
                    String.format(" - %s: '%s' (Default: %d, Hostile: <%d, Friendly: >=%d)",
                            f.getId(), f.getName(), f.getDefaultPoints(), f.getHostileThreshold(), f.getFriendlyThreshold()),
                    "/storynpcs faction info " + f.getId(), ClickEvent.Action.RUN_COMMAND,
                    "Click to view faction " + f.getId() + " details and actions");
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return factions.size();
    }

    private static int infoFaction(CommandContext<CommandSourceStack> ctx) {
        NamespacedId id = getNamespacedId(ctx, "faction_id");
        DefinitionRegistry reg = mod(ctx).getRegistry();
        var fOpt = reg.getFaction(id);
        if (fOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Faction not found: " + id));
            return 0;
        }
        Faction f = fOpt.get();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("=== Faction: %s ('%s') ===", f.getId(), f.getName())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Default Points: %d", f.getDefaultPoints())), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(" Standing Thresholds: Hostile < %d, Neutral %d-%d, Friendly >= %d",
                f.getHostileThreshold(), f.getHostileThreshold(), f.getFriendlyThreshold() - 1, f.getFriendlyThreshold())), false);
        if (ctx.getSource().hasPermission(2)) {
            MutableComponent actions = Component.literal(" §7[ ")
                    .append(clickable("§bSet Points", "/storynpcs faction set " + id + " ",
                            ClickEvent.Action.SUGGEST_COMMAND, "Set points for faction '" + id + "'"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§a+100 Rep", "/storynpcs faction adjust " + id + " 100",
                            ClickEvent.Action.SUGGEST_COMMAND, "Add 100 reputation with '" + id + "'"))
                    .append(Component.literal(" §7| "))
                    .append(clickable("§c-100 Rep", "/storynpcs faction adjust " + id + " -100",
                            ClickEvent.Action.SUGGEST_COMMAND, "Subtract 100 reputation with '" + id + "'"))
                    .append(Component.literal(" §7]"));
            ctx.getSource().sendSuccess(() -> actions, false);
        }
        return 1;
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
            var service = mod(ctx).getApplicationService();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            var request = new com.storynpcs.service.FactionProgressionMutationRequest(
                    "command", actorUuid, player.getUUID(), id,
                    com.storynpcs.service.FactionProgressionMutationRequest.Action.SET, points,
                    service.currentFactionProgressionRevision(player.getUUID()), UUID.randomUUID(),
                    ctx.getSource().hasPermission(2) ? 2 : 0);
            var result = service.mutateFactionProgression(request);
            if (result.hasErrors() || !result.applied()) {
                ctx.getSource().sendFailure(Component.literal("Failed to set faction points: " + result.formatReport()));
                return 0;
            }
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
            var service = mod(ctx).getApplicationService();
            UUID actorUuid = ctx.getSource().getEntity() instanceof ServerPlayer actor
                    ? actor.getUUID() : null;
            var request = new com.storynpcs.service.FactionProgressionMutationRequest(
                    "command", actorUuid, player.getUUID(), id,
                    com.storynpcs.service.FactionProgressionMutationRequest.Action.ADJUST, delta,
                    service.currentFactionProgressionRevision(player.getUUID()), UUID.randomUUID(),
                    ctx.getSource().hasPermission(2) ? 2 : 0);
            var result = service.mutateFactionProgression(request);
            if (result.hasErrors() || !result.applied()) {
                ctx.getSource().sendFailure(Component.literal("Failed to adjust faction points: " + result.formatReport()));
                return 0;
            }
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

        var appService = mod(ctx).getApplicationService();
        if (appService == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Canonical application service is unavailable."));
            return 0;
        }
        int entityIdx = 0;
        for (StoryNpcEntity npc : entities) {
            FollowerRole role = npc.getFollowerRole();
            int finalSlot = slot < 0 ? -1 : (slot + entityIdx);
            var formationResult = appService.mutateFollowerState(
                    com.storynpcs.service.FollowerStateMutationRequest.setFormation(
                            "command", player.getUUID(), player.getUUID(),
                            NamespacedId.of(npc.getDefinitionId()), type, finalSlot, spacing, UUID.randomUUID()),
                    role);
            if (formationResult.applied()) updated++;
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

        var appService = mod(ctx).getApplicationService();
        if (appService == null) {
            source.sendFailure(Component.literal("[StoryNPCs] Canonical application service is unavailable."));
            return 0;
        }
        for (StoryNpcEntity npc : entities) {
            FollowerRole role = npc.getFollowerRole();
            var stateResult = appService.mutateFollowerState(
                    com.storynpcs.service.FollowerStateMutationRequest.setState(
                            "command", player.getUUID(), player.getUUID(),
                            NamespacedId.of(npc.getDefinitionId()), state, UUID.randomUUID()),
                    role);
            if (stateResult.applied()) updated++;
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

    private enum PathOp { ADD, MOVE, DELETE, MODE, CLEAR }

    /** P8-2 teleporter-tool command mapping: move a live NPC to a position. */
    private static int teleportNpc(CommandContext<CommandSourceStack> ctx, ResourceLocation targetNpcId,
                                   net.minecraft.core.BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        var level = source.getLevel();
        List<StoryNpcEntity> entities = level.getEntitiesOfClass(StoryNpcEntity.class,
                new AABB(
                        source.getPosition().x - 512, level.getMinBuildHeight(), source.getPosition().z - 512,
                        source.getPosition().x + 512, level.getMaxBuildHeight(), source.getPosition().z + 512),
                npc -> targetNpcId.toString().equals(npc.getDefinitionId()) && npc.isAlive());
        if (entities.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[StoryNPCs] No live '" + targetNpcId + "' entity found within 512 blocks."));
            return 0;
        }
        StoryNpcs mod = mod(ctx);
        var actorUuid = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        for (StoryNpcEntity npc : entities) {
            npc.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            npc.getNavigation().stop();
            if (mod != null && actorUuid != null && source.getEntity() instanceof ServerPlayer sp) {
                com.storynpcs.item.CreatorToolAudit.publish(mod, sp, "teleporter", "teleport",
                        npc.getName().getString(), "applied");
            }
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[StoryNPCs] Teleported %d '%s' entity(ies) to (%d, %d, %d).",
                entities.size(), targetNpcId, pos.getX(), pos.getY(), pos.getZ())), true);
        return entities.size();
    }

    /** P8-2 path diagnostics: mode, waypoint count, and coordinates for an NPC. */
    private static int pathList(CommandContext<CommandSourceStack> ctx, ResourceLocation targetNpcId) {
        NpcDefinition def = requireNpc(ctx, "npc_id");
        if (def == null) {
            return 0;
        }
        var path = def.getAi() != null ? def.getAi().getWaypointPath() : null;
        if (path == null || path.size() == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] '" + targetNpcId + "' has no waypoints."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "§a[StoryNPCs] '%s' path: mode=%s, %d/%d waypoints:",
                targetNpcId, path.getMode(), path.size(),
                com.storynpcs.domain.ai.WaypointPath.MAX_WAYPOINTS)), false);
        for (int i = 0; i < path.size(); i++) {
            var wp = path.getWaypoint(i);
            int idx = i;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7#%d: (%.1f, %.1f, %.1f)", idx, wp.x(), wp.y(), wp.z())), false);
        }
        return path.size();
    }

    /** P8-2 canonical path editing: add/move/delete/mode/clear via npc.mutate. */
    private static int pathMutate(CommandContext<CommandSourceStack> ctx, ResourceLocation targetNpcId,
                                  net.minecraft.core.BlockPos pos, PathOp op, int index, String mode) {
        NpcDefinition def = requireNpc(ctx, "npc_id");
        if (def == null) {
            return 0;
        }
        NamespacedId id = NamespacedId.of(targetNpcId.toString());
        StoryNpcs mod = mod(ctx);
        var service = mod.getApplicationService();
        var path = def.getAi() != null ? def.getAi().getWaypointPath() : null;
        // Pre-validate so a rejected op never writes a no-op revision bump.
        String rejection = switch (op) {
            case ADD -> path != null && path.size() >= com.storynpcs.domain.ai.WaypointPath.MAX_WAYPOINTS
                    ? String.format("path is full (%d/%d)",
                            com.storynpcs.domain.ai.WaypointPath.MAX_WAYPOINTS,
                            com.storynpcs.domain.ai.WaypointPath.MAX_WAYPOINTS) : null;
            case MOVE, DELETE -> path == null || index < 0 || index >= path.size()
                    ? "no waypoint at index " + index : null;
            case MODE -> parsePatrolMode(mode) == null
                    ? "invalid mode '" + mode + "' (loop|ping_pong|once)" : null;
            case CLEAR -> null;
        };
        if (rejection != null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c[StoryNPCs] Path " + op.name().toLowerCase() + " rejected for '" + id + "': " + rejection));
            return 0;
        }
        var result = mutateNpc(ctx, service, id, d -> {
            if (d.getAi() == null) {
                d.setAi(new com.storynpcs.domain.npc.NpcAi());
            }
            var p = d.getAi().getWaypointPath();
            switch (op) {
                case ADD -> {
                    p.addWaypoint(new com.storynpcs.domain.ai.Waypoint(
                            pos.getX(), pos.getY(), pos.getZ()));
                    d.getAi().setMovementType(com.storynpcs.domain.npc.NpcAi.MovementType.PATHING);
                }
                case MOVE -> p.moveWaypoint(index, new com.storynpcs.domain.ai.Waypoint(
                        pos.getX(), pos.getY(), pos.getZ()));
                case DELETE -> p.removeWaypoint(index);
                case MODE -> p.setMode(parsePatrolMode(mode));
                case CLEAR -> p.setWaypoints(new java.util.ArrayList<>());
            }
        });
        if (result.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c[StoryNPCs] Failed to persist path for '" + id + "': " + result.formatReport(2)));
            return 0;
        }
        refreshLiveNpcs(ctx, id);
        if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
            com.storynpcs.item.CreatorToolAudit.publish(mod, sp, "path",
                    "waypoint." + op.name().toLowerCase(), id.toString(), "applied");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[StoryNPCs] Path " + op.name().toLowerCase() + " applied to '" + id + "'."), true);
        return 1;
    }

    private static com.storynpcs.domain.ai.WaypointPath.PatrolMode parsePatrolMode(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "loop" -> com.storynpcs.domain.ai.WaypointPath.PatrolMode.LOOP;
            case "ping_pong" -> com.storynpcs.domain.ai.WaypointPath.PatrolMode.PING_PONG;
            case "once" -> com.storynpcs.domain.ai.WaypointPath.PatrolMode.ONCE;
            default -> null;
        };
    }

    /** Re-applies a definition to every live entity after a canonical path mutation. */
    private static void refreshLiveNpcs(CommandContext<CommandSourceStack> ctx, NamespacedId id) {
        var server = ctx.getSource().getServer();
        if (server == null) {
            return;
        }
        for (net.minecraft.server.level.ServerLevel sl : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : sl.getAllEntities()) {
                if (entity instanceof StoryNpcEntity sne && id.toString().equals(sne.getDefinitionId())) {
                    sne.applyDefinition();
                }
            }
        }
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

        StoryNpcs mod = mod(ctx);
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
                "§e/storynpcs quickstart §7- One-command demo: NPC + all wands\n" +
                "§e/storynpcs me [player] §7- Your quests & faction standing\n" +
                "§e/storynpcs reload §7- Reload YAML definitions\n" +
                "§e/storynpcs npc <create|list|info|set|spawn|despawn|delete|rule|trade|bank> §7- Manage NPCs\n" +
                "§e/storynpcs dialogue <create|list|info|edit|delete|start> §7- Manage Dialogues\n" +
                "§e/storynpcs quest <list|info|create|set|objective|reward|gui|start|complete> §7- Manage Quests\n" +
                "§e/storynpcs faction <list|info|create|configure|gui|set|adjust> §7- Manage Factions\n" +
                "§e/storynpcs follower <recall|formation|state> §7- Command Followers\n" +
                "§7Every system has three paths: YAML in world/storynpcs/definitions/, the commands above, and GUIs (wand for NPCs, edit/gui subcommands for the rest).\n" +
                "§7Alias: /sn · IDs tab-complete · list entries are clickable"), false);
        return 1;
    }

    private static int sendNpcHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs NPC Commands ---§r\n" +
                "  §e/storynpcs npc create <npc_id> [name]  §7- Scaffold a new NPC (writes YAML, spawns it)\n" +
                "  §e/storynpcs npc list  §7- List all registered NPC definitions\n" +
                "  §e/storynpcs npc info <npc_id>  §7- View details & action buttons for an NPC\n" +
                "  §e/storynpcs npc set name <npc_id> <name>  §7- Set NPC display name\n" +
                "  §e/storynpcs npc set title <npc_id> <title>  §7- Set NPC title / role\n" +
                "  §e/storynpcs npc set skin <npc_id> <skin_texture>  §7- Set NPC skin texture\n" +
                "  §e/storynpcs npc set health <npc_id> <health>  §7- Set NPC max health\n" +
                "  §e/storynpcs npc set damage <npc_id> <damage>  §7- Set NPC attack damage\n" +
                "  §e/storynpcs npc set speed <npc_id> <speed>  §7- Set NPC movement speed\n" +
                "  §e/storynpcs npc set range <npc_id> <range>  §7- Set NPC wander range\n" +
                "  §e/storynpcs npc set movement <npc_id> <standing|wandering|pathing>  §7- Set AI movement\n" +
                "  §e/storynpcs npc set stance <npc_id> <guard|passive|neutral|aggressive|evasive>  §7- Set combat stance\n" +
                "  §e/storynpcs npc set dialogue <npc_id> <dialogue_id>  §7- Assign dialogue to an NPC\n" +
                "  §e/storynpcs npc set faction <npc_id> <faction_id>  §7- Assign faction to an NPC\n" +
                "  §e/storynpcs npc spawn <npc_id> [pos]  §7- Spawn an NPC into the world\n" +
                "  §e/storynpcs npc despawn [npc_id] [radius]  §7- Remove spawned NPCs from the world\n" +
                "  §e/storynpcs npc delete <npc_id>  §7- Delete NPC definition from registry & disk\n" +
                "  §e/storynpcs npc rule list <npc_id>  §7- List behavior rules on an NPC\n" +
                "  §e/storynpcs npc rule add <npc_id> <trigger> <condition> <action>  §7- Add a behavior rule\n" +
                "  §e/storynpcs npc rule remove <npc_id> <index>  §7- Remove a behavior rule\n" +
                "  §e/storynpcs npc trade enable <npc_id> [name]  §7- Make an NPC a trader\n" +
                "  §e/storynpcs npc trade add <npc_id> <offer_item> <offer_count> <price_item> <price_count> [max_uses]  §7- Add a trade listing\n" +
                "  §e/storynpcs npc trade list <npc_id>  §7- List trade listings\n" +
                "  §e/storynpcs npc trade remove <npc_id> <index>  §7- Remove a trade listing\n" +
                "  §e/storynpcs npc trade disable <npc_id>  §7- Remove the trader role\n" +
                "  §e/storynpcs npc bank enable <npc_id> [name]  §7- Make an NPC a banker\n" +
                "  §e/storynpcs npc bank disable <npc_id>  §7- Remove the banker role\n" +
                "  §7GUI: give @s storynpcs:npc_wand then Shift+Right-click an NPC (also hosts the Rules editor)\n" +
                "  §7YAML: world/storynpcs/definitions/npcs/<file>.yaml"), false);
        return 1;
    }

    private static int sendDialogueHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Dialogue Commands ---§r\n" +
                "§e/storynpcs dialogue create <dialogue_id> [title] §7- Scaffold starter dialogue & open editor\n" +
                "§e/storynpcs dialogue list §7- List loaded dialogue graphs\n" +
                "§e/storynpcs dialogue info <dialogue_id> §7- View dialogue graph structure & referencing NPCs\n" +
                "§e/storynpcs dialogue edit <dialogue_id> §7- Open visual graph editor GUI\n" +
                "§e/storynpcs dialogue delete <dialogue_id> §7- Delete dialogue definition from registry & disk\n" +
                "§e/storynpcs dialogue start <dialogue_id> [player] §7- Initiate dialogue session\n" +
                "§7YAML: world/storynpcs/definitions/dialogues/<file>.yaml"), false);
        return 1;
    }

    private static int sendQuestHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Quest Commands ---§r\n" +
                "§e/storynpcs quest list §7- List all registered quests\n" +
                "§e/storynpcs quest info <quest_id> §7- View quest objectives, rewards & details\n" +
                "§e/storynpcs quest start <quest_id> [player] §7- Start a quest for a player\n" +
                "§e/storynpcs quest complete <quest_id> [player] §7- Complete a quest for a player\n" +
                "§e/storynpcs quest create <quest_id> [title] §7- Scaffold a new quest definition\n" +
                "§e/storynpcs quest set <quest_id> <description|category|repeatType> <value> §7- Edit quest fields\n" +
                "§e/storynpcs quest objective|reward add|remove <quest_id> ... §7- Edit objectives/rewards\n" +
                "§e/storynpcs quest gui [quest_id] §7- Open the quest editor GUI\n" +
                "§e/storynpcs quest delete <quest_id> §7- Delete a quest definition (warns about dangling START_QUEST actions)\n" +
                "§7YAML: world/storynpcs/definitions/quests/<file>.yaml"), false);
        return 1;
    }

    private static int sendFactionHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6--- StoryNPCs Faction Commands ---§r\n" +
                "§e/storynpcs faction list §7- List all factions\n" +
                "§e/storynpcs faction info <faction_id> §7- View faction thresholds & standing\n" +
                "§e/storynpcs faction set <faction_id> <points> [player] §7- Set player faction reputation\n" +
                "§e/storynpcs faction adjust <faction_id> <delta> [player] §7- Adjust player faction reputation\n" +
                "§e/storynpcs faction create <faction_id> [name] §7- Scaffold a new faction definition\n" +
                "§e/storynpcs faction configure <faction_id> <field> <value> §7- Tune thresholds\n" +
                "§e/storynpcs faction gui [faction_id] §7- Open the faction editor GUI\n" +
                "§e/storynpcs faction delete <faction_id> §7- Delete a faction definition (warns about dangling NPC bindings)\n" +
                "§7YAML: world/storynpcs/definitions/factions/<file>.yaml"), false);
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

    // ── P6-3 transport ───────────────────────────────────────────────────────

    private static int listTransports(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        UUID playerUuid = ctx.getSource().getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;
        var locations = service.listTransports(playerUuid);
        if (locations.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] No transport destinations unlocked. YAML: definitions/transports/"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("--- StoryNPCs Transports (%d) ---", locations.size())), false);
        for (var loc : locations) {
            final var line = Component.literal(String.format(" §e%s§r — %s (%.0f, %.0f, %.0f) fee=%d",
                    loc.getId(), loc.getName(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getFee()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int transport(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Only players can use transport."));
            return 0;
        }
        var locationId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "location_id").toString());
        var result = service.requestTransport(new com.storynpcs.service.PlayerProgressionActionRequest(
                "transport.request", "command", player.getUUID(), player.getUUID(),
                java.util.UUID.randomUUID(), ctx.getSource().hasPermission(2) ? 2 : -1),
                locationId);
        if (!result.approved()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Transport refused: " + result.detail()));
            return 0;
        }
        final String msg = result.feeCharged() > 0
                ? "§aTransported to " + result.destinationName() + " (fee: " + result.feeCharged() + " emeralds)"
                : "§aTransported to " + result.destinationName();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    // ── P8-1 templates ───────────────────────────────────────────────────────

    private static int listTemplates(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Mod not initialized."));
            return 0;
        }
        var templates = mod.getRegistry().getAllTemplates();
        if (templates.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] No templates loaded. YAML: definitions/templates/"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("--- StoryNPCs Templates (%d) ---", templates.size())), false);
        for (var t : templates) {
            final var line = Component.literal(String.format(" §e%s§r v%d — %s %s",
                    t.getId(), t.getSchemaVersion(), t.getDescription(),
                    t.getTags().isEmpty() ? "" : t.getTags()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int applyTemplate(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var templateId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "template_id").toString());
        var npcId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "npc_id").toString());
        var template = mod.getRegistry().getTemplate(templateId).orElse(null);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template not found: " + templateId));
            return 0;
        }
        // instantiate() deep-copies — the cloned definition never shares state
        // with the template and is persisted through the canonical create path.
        var definition = template.instantiate(npcId);
        var result = service.createNpc(
                commandMutationRequest(ctx, service, "npc", "create", npcId), definition);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template apply rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aCreated NPC " + npcId + " from template " + templateId), true);
        return 1;
    }

    // ── P8-1 spawners ────────────────────────────────────────────────────────

    private static int listSpawners(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Mod not initialized."));
            return 0;
        }
        var rules = mod.getRegistry().getAllSpawnerRules();
        if (rules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[StoryNPCs] No spawners loaded. YAML: definitions/spawners/"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("--- StoryNPCs Spawners (%d) ---", rules.size())), false);
        for (var r : rules) {
            String anchor = r.isAnchored()
                    ? String.format("%s @ (%d,%d,%d)", r.getDimension(),
                            r.getAnchorX(), r.getAnchorY(), r.getAnchorZ())
                    : "unanchored";
            final var line = Component.literal(String.format(
                    " §e%s§r → %s | quota %d every %dt | %s%s",
                    r.getId(), r.getTemplateId(), r.getQuota(), r.getSpawnIntervalTicks(),
                    anchor, r.isEnabled() ? "" : " [disabled]"));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int spawnerInfo(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Mod not initialized."));
            return 0;
        }
        var id = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "spawner_id").toString());
        var rule = mod.getRegistry().getSpawnerRule(id).orElse(null);
        if (rule == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Spawner not found: " + id));
            return 0;
        }
        String templateState = mod.getRegistry().getTemplate(rule.getTemplateId()).isPresent()
                ? "loaded" : "MISSING — spawner inert";
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "--- Spawner %s ---%n template: %s (%s)%n quota: %d  interval: %dt  radius: %.1f%n"
                        + " cleanupOnChunkUnload: %s  respawnOnDeath: %s  enabled: %s%n anchor: %s",
                rule.getId(), rule.getTemplateId(), templateState,
                rule.getQuota(), rule.getSpawnIntervalTicks(), rule.getPlacementRadiusBlocks(),
                rule.isCleanupOnChunkUnload(), rule.isRespawnOnDeath(), rule.isEnabled(),
                rule.isAnchored() ? String.format("%s @ (%d,%d,%d)", rule.getDimension(),
                        rule.getAnchorX(), rule.getAnchorY(), rule.getAnchorZ())
                        : "unanchored (inert)")), false);
        return 1;
    }

    /**
     * Convenience anchor: creates (or replaces) a spawner rule bound to the
     * executor's position/dimension through the canonical spawner.replace op —
     * the same operation a YAML file or editor save would take.
     */
    private static int placeSpawner(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var spawnerId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "spawner_id").toString());
        var templateId = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "template_id").toString());
        if (mod.getRegistry().getTemplate(templateId).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template not found: " + templateId));
            return 0;
        }
        // Service detaches the payload internally — a fresh bean is safe here.
        var rule = new com.storynpcs.creator.template.SpawnerRule();
        rule.setId(spawnerId);
        rule.setTemplateId(templateId);
        var pos = ctx.getSource().getPosition();
        rule.setDimension(ctx.getSource().getLevel().dimension().location().toString());
        rule.setAnchorX((int) Math.floor(pos.x));
        rule.setAnchorY((int) Math.floor(pos.y));
        rule.setAnchorZ((int) Math.floor(pos.z));
        var result = service.saveSpawner(
                commandMutationRequest(ctx, service, "spawner", "replace", spawnerId), rule);
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Spawner save rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aPlaced spawner " + spawnerId + " → template " + templateId
                        + " at " + rule.getDimension()
                        + " (" + rule.getAnchorX() + "," + rule.getAnchorY() + ","
                        + rule.getAnchorZ() + ")"), true);
        return 1;
    }

    private static int deleteSpawner(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "spawner_id").toString());
        var result = service.deleteSpawner(
                commandMutationRequest(ctx, service, "spawner", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Spawner delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aDeleted spawner " + id + " (owned actors remain in-world)"), true);
        return 1;
    }

    /** {@code template delete <template_id>} — canonical template removal (P9-3). */
    private static int deleteTemplate(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "template_id").toString());
        var result = service.deleteTemplate(
                commandMutationRequest(ctx, service, "template", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Template delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aDeleted template " + id), true);
        return 1;
    }

    // ── P8-3 world tools ─────────────────────────────────────────────────────

    private static int listWorldTools(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var tools = mod.getRegistry().getAllWorldTools();
        if (tools.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No world tools loaded (worldtools/*.yaml)."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[StoryNPCs] " + tools.size() + " world tool(s):"), false);
        for (var tool : tools) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s — family=%s, block=%s, budget=%d, hooks=%d",
                    tool.getId(), tool.getFamily(),
                    tool.getBlockId() == null ? "none" : tool.getBlockId(),
                    tool.getMaxBlocksPerActivation(), tool.getHooks().size())), false);
        }
        return tools.size();
    }

    /** Shows the tool's reversible/irreversible op catalog — the preview contract. */
    private static int showWorldTool(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "tool_id").toString());
        var tool = mod.getRegistry().getWorldTool(id).orElse(null);
        if (tool == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] World tool not found: " + id));
            return 0;
        }
        var ops = com.storynpcs.creator.world.WorldToolOpsCatalog.opsFor(tool.getFamily());
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "§a[StoryNPCs] %s — family=%s, dimension=%s, budget=%d",
                tool.getId(), tool.getFamily(),
                tool.getDimensionId() == null ? "any" : tool.getDimensionId(),
                tool.getMaxBlocksPerActivation())), false);
        for (var op : ops.reversible()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  §areversible: §7" + op.opId() + " — " + op.description()), false);
        }
        for (var op : ops.irreversible()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  §clogged-only: §7" + op.opId() + " — " + op.description()), false);
        }
        return ops.reversible().size() + ops.irreversible().size();
    }

    /**
     * Preview → confirm activation: the first call shows the plan and arms a
     * bounded confirmation; the matching second call executes and reports
     * each leg's exact status.
     */
    private static int activateWorldTool(CommandContext<CommandSourceStack> ctx,
                                         ResourceLocation toolLoc, net.minecraft.core.BlockPos pos) {
        var mod = modOrNull(ctx);
        var source = ctx.getSource();
        if (mod == null || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[StoryNPCs] Only players can activate world tools."));
            return 0;
        }
        var id = NamespacedId.of(toolLoc.toString());
        var tool = mod.getRegistry().getWorldTool(id).orElse(null);
        if (tool == null) {
            source.sendFailure(Component.literal("[StoryNPCs] World tool not found: " + id));
            return 0;
        }
        var level = source.getLevel();
        var sessions = mod.getRuntimeSessions(source.getServer());
        long now = System.currentTimeMillis();
        String confirmTool = "worldtool:" + id;
        if (sessions.consumePosConfirmation(player.getUUID(), confirmTool,
                pos.asLong(), now) == null) {
            // Preview pass — show the plan; arm the confirm.
            var executor = mod.getWorldToolExecutor(source.getServer());
            sessions.armPosConfirmation(player.getUUID(), confirmTool, pos.asLong(), 15_000);
            source.sendSuccess(() -> Component.literal(String.format(
                    "§e[StoryNPCs] Preview: '%s' (%s) at (%d,%d,%d) would run:",
                    id, tool.getFamily(), pos.getX(), pos.getY(), pos.getZ())), false);
            for (var leg : executor.preview(tool)) {
                source.sendSuccess(() -> Component.literal(String.format(
                        "  %s%s§7", leg.reversible() ? "§a" : "§c", leg.opId())), false);
            }
            source.sendSuccess(() -> Component.literal(
                    "§eRun the same command within 15 seconds to apply. Irreversible legs are logged, not rolled back."), false);
            return 1;
        }
        var executor = mod.getWorldToolExecutor(source.getServer());
        var report = executor.activate(tool, level, pos, player);
        for (var leg : report.legs()) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "  %s%s%s §7%s",
                    leg.status() == com.storynpcs.runtime.worldtool.WorldToolExecutor.LegStatus.APPLIED
                            || leg.status() == com.storynpcs.runtime.worldtool.WorldToolExecutor.LegStatus.LOGGED
                            ? "§a" : leg.status() == com.storynpcs.runtime.worldtool.WorldToolExecutor.LegStatus.ROLLED_BACK
                            ? "§e" : "§c",
                    leg.opId(), leg.status() == com.storynpcs.runtime.worldtool.WorldToolExecutor.LegStatus.APPLIED
                            ? "" : " [" + leg.status() + "]",
                    leg.detail() == null ? "" : "— " + leg.detail())), false);
        }
        if (!report.fullyApplied()) {
            source.sendFailure(Component.literal(
                    "§c[StoryNPCs] '" + id + "' partially failed — see leg report above."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "§a[StoryNPCs] '" + id + "' activated at (" + pos.toShortString() + ")."), true);
        return 1;
    }

    // ── P8-4 recipes ─────────────────────────────────────────────────────────

    private static int listRecipes(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var recipes = mod.getRegistry().getAllRecipes();
        if (recipes.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No recipes loaded (recipes/*.yaml)."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[StoryNPCs] " + recipes.size() + " recipe(s):"), false);
        for (var recipe : recipes) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s — group=%s, %s, out=%s x%d",
                    recipe.getId(), recipe.getGroupId(),
                    recipe.isShapeless() ? "shapeless" : "shaped",
                    recipe.getOutputItemId(), recipe.getOutputCount())), false);
        }
        return recipes.size();
    }

    private static int recipeInfo(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "recipe_id").toString());
        var recipe = mod.getRegistry().getRecipe(id).orElse(null);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Recipe not found: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "§a[StoryNPCs] %s — group=%s, %s, out=%s x%d",
                recipe.getId(), recipe.getGroupId(),
                recipe.isShapeless() ? "shapeless" : "shaped",
                recipe.getOutputItemId(), recipe.getOutputCount())), false);
        for (int row = 0; row < 3; row++) {
            String line = String.format("  §7| %s | %s | %s |",
                    cell(recipe.getGrid(), row * 3),
                    cell(recipe.getGrid(), row * 3 + 1),
                    cell(recipe.getGrid(), row * 3 + 2));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static String cell(java.util.List<String> grid, int i) {
        String s = i < grid.size() ? grid.get(i) : null;
        return s == null || s.isBlank() ? "·" : s;
    }

    private static int deleteRecipe(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(
                ResourceLocationArgument.getId(ctx, "recipe_id").toString());
        var result = service.deleteRecipe(
                commandMutationRequest(ctx, service, "recipe", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Recipe delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aDeleted recipe " + id), true);
        return 1;
    }

    // ── P8-5 orchestration ───────────────────────────────────────────────────

    private static int listScenes(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var scenes = mod.getRegistry().getAllScenes();
        if (scenes.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No scenes loaded (scenes/*.yaml)."), false);
            return 0;
        }
        for (var scene : scenes) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s — participants=%d, entities≤%d, duration≤%dt, stages=%d, recovery=%s%s",
                    scene.getId(), scene.getParticipantTemplateIds().size(),
                    scene.getMaxEntities(), scene.getMaxDurationTicks(),
                    scene.getStages().size(), scene.getCancelRecovery(),
                    mod.getSceneRuntime().isRunning(scene.getId()) ? " §a[RUNNING]" : "")), false);
        }
        return scenes.size();
    }

    private static int sceneInfo(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "scene_id").toString());
        var scene = mod.getRegistry().getScene(id).orElse(null);
        if (scene == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Scene not found: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "§a[StoryNPCs] %s — %d stage(s), recovery=%s",
                scene.getId(), scene.getStages().size(), scene.getCancelRecovery())), false);
        for (var stage : scene.getStages()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7stage '%s' — %dt %s", stage.name(), stage.durationTicks(),
                    stage.cueText() == null ? "" : "— " + stage.cueText())), false);
        }
        return scene.getStages().size();
    }

    private static int startScene(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null || !(ctx.getSource().getEntity() instanceof ServerPlayer)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Only players can start scenes."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "scene_id").toString());
        var scene = mod.getRegistry().getScene(id).orElse(null);
        if (scene == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Scene not found: " + id));
            return 0;
        }
        var result = mod.getSceneRuntime().start(scene, ctx.getSource().getLevel(),
                ctx.getSource().getEntity().blockPosition(),
                ctx.getSource().getLevel().getGameTime());
        if (!result.started()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Scene '" + id + "' did not start: " + result.outcome()
                            + (result.missingTemplate() != null
                                    ? " (" + result.missingTemplate() + ")" : "")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aStarted scene " + id), true);
        return 1;
    }

    private static int cancelScene(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "scene_id").toString());
        if (!mod.getSceneRuntime().cancel(id, "cancelled by " + ctx.getSource().getTextName())) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Scene '" + id + "' is not running."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aCancelled scene " + id + " (recovery applied)"), true);
        return 1;
    }

    /** {@code scene cancelall} — cancel every active scene in the caller's dimension (P9-3). */
    private static int cancelAllScenes(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        int cancelled = mod.getSceneRuntime().cancelAll(ctx.getSource().getLevel());
        final int n = cancelled;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aCancelled " + n + " active scene(s)"), true);
        return n;
    }

    private static int deleteScene(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "scene_id").toString());
        mod.getSceneRuntime().cancel(id, "definition deleted");
        var result = service.deleteScene(
                commandMutationRequest(ctx, service, "scene", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Scene delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aDeleted scene " + id), true);
        return 1;
    }

    private static int listTransforms(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var rules = mod.getRegistry().getAllTransforms();
        if (rules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No transform rules loaded (transforms/*.yaml)."), false);
            return 0;
        }
        for (var rule : rules) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s → %s — policy=%s, trigger=%s, facets=%s",
                    rule.getId(), rule.getTargetTemplateId(), rule.getIdentityPolicy(),
                    rule.getTrigger(), rule.getReplacedFacets())), false);
        }
        return rules.size();
    }

    /**
     * Apply a transform rule: PRESERVE merges the template's declared facets
     * into the actor's existing definition (identity + progression kept);
     * REPLACE creates a fresh canonical definition under a derived id. The
     * live entity refresh runs through the canonical mutate path.
     */
    private static int applyTransform(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var ruleId = NamespacedId.of(ResourceLocationArgument.getId(ctx, "rule_id").toString());
        var npcId = NamespacedId.of(ResourceLocationArgument.getId(ctx, "npc_id").toString());
        var rule = mod.getRegistry().getTransform(ruleId).orElse(null);
        if (rule == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Transform not found: " + ruleId));
            return 0;
        }
        var template = mod.getRegistry().getTemplate(rule.getTargetTemplateId()).orElse(null);
        if (template == null || template.getDefinition() == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Transform target template missing: " + rule.getTargetTemplateId()));
            return 0;
        }
        var source = mod.getRegistry().getNpc(npcId).orElse(null);
        if (source == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] NPC not found: " + npcId));
            return 0;
        }
        var staged = template.instantiate(npcId);
        switch (rule.getIdentityPolicy()) {
            case PRESERVE -> {
                var result = service.mutateNpc(
                        commandMutationRequest(ctx, service, "npc", "mutate", npcId),
                        npc -> applyTransformFacets(npc, staged, rule));
                if (!result.applied()) {
                    ctx.getSource().sendFailure(Component.literal(
                            "[StoryNPCs] Transform rejected:\n" + result.formatReport(5)));
                    return 0;
                }
            }
            case REPLACE -> {
                // Fresh logical identity at a deterministic derived id —
                // create-first ordering means a failed retire leaves a
                // duplicate actor (recoverable), never a lost one.
                var derivedId = NamespacedId.of(ruleId.getNamespace()
                        + ":transformed/" + npcId.getPath().replace('/', '_'));
                var stagedReplace = template.instantiate(derivedId);
                var created = service.createNpc(
                        commandMutationRequest(ctx, service, "npc", "create", derivedId),
                        stagedReplace);
                if (!created.applied() && !created.duplicate()) {
                    ctx.getSource().sendFailure(Component.literal(
                            "[StoryNPCs] Transform rejected:\n" + created.formatReport(5)));
                    return 0;
                }
                var retired = service.deleteNpc(
                        commandMutationRequest(ctx, service, "npc", "delete", npcId));
                if (!retired.applied() && !retired.duplicate()) {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§eTransformed " + npcId + " → " + derivedId
                                    + " but source retire was rejected (actor duplicated):\n"
                                    + retired.formatReport(5)), true);
                    return 0;
                }
            }
        }
        mod.getEventPublisher().publish(
                new com.storynpcs.api.event.P85OrchestrationEvents.NpcTransformEvent(
                        null, ruleId, rule.getIdentityPolicy().name(),
                        "applied to " + npcId));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aTransformed " + npcId + " via " + ruleId
                        + " (" + rule.getIdentityPolicy() + ")"), true);
        return 1;
    }

    /** Facet-level merge — only declared facets are overwritten. */
    private static void applyTransformFacets(com.storynpcs.domain.npc.NpcDefinition target,
            com.storynpcs.domain.npc.NpcDefinition staged,
            com.storynpcs.creator.transform.TransformRule rule) {
        for (String facet : rule.getReplacedFacets()) {
            switch (facet) {
                case "display" -> target.setDisplay(staged.getDisplay());
                case "stats" -> target.setStats(staged.getStats());
                case "ai" -> target.setAi(staged.getAi());
                case "inventory" -> target.setInventory(staged.getInventory());
                case "rules" -> target.setRules(staged.getRules());
                case "abilities" -> target.setAbilities(staged.getAbilities());
                default -> { /* unknown facets are reported by the validator, never applied */ }
            }
        }
    }

    private static int deleteTransform(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "rule_id").toString());
        var result = service.deleteTransform(
                commandMutationRequest(ctx, service, "transform", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Transform delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aDeleted transform " + id), true);
        return 1;
    }

    private static int listNaturalSpawns(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var rules = mod.getRegistry().getAllNaturalSpawns();
        if (rules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No natural-spawn rules loaded (naturalspawns/*.yaml)."), false);
            return 0;
        }
        for (var rule : rules) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s → %s — weight=%d, cap=%d/dim, minDist=%.0f%s%s",
                    rule.getId(), rule.getTemplateId(), rule.getWeight(),
                    rule.getMaxPerDimension(), rule.getMinPlayerDistanceBlocks(),
                    rule.getDimensionId() == null ? "" : ", dim=" + rule.getDimensionId(),
                    rule.isEnabled() ? "" : " §c[disabled]")), false);
        }
        return rules.size();
    }

    private static int deleteNaturalSpawn(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "rule_id").toString());
        var result = service.deleteNaturalSpawn(
                commandMutationRequest(ctx, service, "naturalspawn", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Natural-spawn delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aDeleted natural-spawn rule " + id), true);
        return 1;
    }

    // ── P8-6 custom-GUI / preset / overlay commands ─────────────────────────

    private static int listGuiLayouts(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var layouts = mod.getRegistry().getAllGuiLayouts();
        if (layouts.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No GUI layouts loaded (guilayouts/*.yaml)."), false);
            return 0;
        }
        for (var layout : layouts) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  §7" + layout.getId() + " — root="
                            + (layout.getRoot() == null ? "none" : layout.getRoot().getName())), false);
        }
        return layouts.size();
    }

    private static int showGuiLayout(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "layout_id").toString());
        var layout = mod.getRegistry().getGuiLayout(id).orElse(null);
        if (layout == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Layout not found: " + id));
            return 0;
        }
        printGuiElement(ctx, layout.getRoot(), "root", 0);
        return 1;
    }

    private static void printGuiElement(CommandContext<CommandSourceStack> ctx,
            com.storynpcs.creator.gui.CustomGuiLayout.GuiElement el, String path, int depth) {
        if (el == null || depth > com.storynpcs.creator.gui.CustomGuiLayout.MAX_DEPTH) {
            return;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  §7" + "  ".repeat(depth) + path + " [" + el.getType() + "] "
                        + el.getWidth() + "x" + el.getHeight()
                        + (el.getTextureRef() != null ? " tex=" + el.getTextureRef() : "")), false);
        for (var child : el.getChildren()) {
            printGuiElement(ctx, child, child.getName(), depth + 1);
        }
    }

    /**
     * Preview ≠ commit — validates the layout and reports a bounded structural
     * projection without any registry write or file mutation.
     */
    private static int previewGuiLayout(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "layout_id").toString());
        var layout = mod.getRegistry().getGuiLayout(id).orElse(null);
        if (layout == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Layout not found: " + id));
            return 0;
        }
        var diagnostics = layout.validate();
        int[] stats = {0, 0}; // count, maxDepth
        measureGuiElement(layout.getRoot(), 1, stats);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "§e[StoryNPCs] Preview %s — %d element(s), depth %d, diagnostics %d error(s)",
                id, stats[0], stats[1], diagnostics.getErrors().size())), false);
        if (diagnostics.hasErrors()) {
            ctx.getSource().sendFailure(Component.literal(
                    diagnostics.formatReport(5)));
        }
        return 1;
    }

    private static void measureGuiElement(
            com.storynpcs.creator.gui.CustomGuiLayout.GuiElement el, int depth, int[] stats) {
        if (el == null) {
            return;
        }
        stats[0]++;
        stats[1] = Math.max(stats[1], depth);
        for (var child : el.getChildren()) {
            measureGuiElement(child, depth + 1, stats);
        }
    }

    private static int deleteGuiLayout(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "layout_id").toString());
        var result = service.deleteGuiLayout(
                commandMutationRequest(ctx, service, "guilayout", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Layout delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aDeleted GUI layout " + id), true);
        return 1;
    }

    private static int listModelPresets(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var presets = mod.getRegistry().getAllModelPresets();
        if (presets.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No model presets loaded (presets/*.yaml)."), false);
            return 0;
        }
        for (var preset : presets) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s → %s — %d layer(s), %d texture(s)%s",
                    preset.getId(), preset.getModelRef(), preset.getLayers().size(),
                    preset.getTextureRefs().size(),
                    preset.getDisplayName().isBlank() ? "" : " \"" + preset.getDisplayName() + "\"")),
                    false);
        }
        return presets.size();
    }

    private static int showModelPreset(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "preset_id").toString());
        var preset = mod.getRegistry().getModelPreset(id).orElse(null);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Preset not found: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§7Preset " + preset.getId() + " → " + preset.getModelRef()), false);
        for (var layer : preset.getLayers()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7layer %s = #%06x", layer.getName(), layer.getRgb())), false);
        }
        for (var tex : preset.getTextureRefs()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  §7texture " + tex), false);
        }
        return 1;
    }

    private static int deleteModelPreset(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var id = NamespacedId.of(ResourceLocationArgument.getId(ctx, "preset_id").toString());
        var result = service.deleteModelPreset(
                commandMutationRequest(ctx, service, "modelpreset", "delete", id));
        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Preset delete rejected:\n" + result.formatReport(5)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aDeleted model preset " + id), true);
        return 1;
    }

    private static int listOverlays(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var source = ctx.getSource();
        if (mod == null || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[StoryNPCs] Player context required."));
            return 0;
        }
        int nowTick = (int) (source.getLevel().getGameTime() & 0x7FFFFFFF);
        var active = mod.getOverlaySessions(source.getServer()).active(player.getUUID(), nowTick);
        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e[StoryNPCs] No active overlays."), false);
            return 0;
        }
        for (var overlay : active) {
            source.sendSuccess(() -> Component.literal(
                    "  §7" + overlay.overlayId() + " — " + overlay.elementId()
                            + " expires@" + overlay.expiryTick()), false);
        }
        return active.size();
    }

    private static int showOverlay(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        var source = ctx.getSource();
        if (mod == null || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[StoryNPCs] Player context required."));
            return 0;
        }
        var layoutId = NamespacedId.of(ResourceLocationArgument.getId(ctx, "layout_id").toString());
        var elementName = StringArgumentType.getString(ctx, "element_name");
        int duration = IntegerArgumentType.getInteger(ctx, "duration_ticks");
        if (mod.getRegistry().getGuiLayout(layoutId).isEmpty()) {
            source.sendFailure(Component.literal("[StoryNPCs] Layout not found: " + layoutId));
            return 0;
        }
        int nowTick = (int) (source.getLevel().getGameTime() & 0x7FFFFFFF);
        var session = mod.getOverlaySessions(source.getServer());
        // Ensure the player session exists — login opens it, but the overlay
        // command must not silently no-op on an edge-case join.
        if (session.playerOf(player.getUUID()).isEmpty()) {
            session.openSession(player.getUUID(), player.getUUID());
        }
        var overlay = session.show(player.getUUID(), elementName, duration, nowTick);
        if (overlay == null) {
            source.sendFailure(Component.literal(
                    "[StoryNPCs] Overlay rejected — session full (max "
                            + com.storynpcs.creator.gui.OverlaySession.MAX_OVERLAYS_PER_SESSION
                            + ") or bad duration."));
            return 0;
        }
        mod.getEventPublisher().publish(
                new com.storynpcs.api.event.P86GuiEvents.OverlayShownEvent(
                        layoutId, elementName, player.getUUID(), overlay.overlayId(),
                        overlay.expiryTick()));
        source.sendSuccess(() -> Component.literal(
                "§aOverlay " + overlay.overlayId() + " shown (" + elementName
                        + ", " + duration + "t)"), true);
        return 1;
    }

    private static int linkNpc(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        UUID actor = parseUuid(ctx, "actor_uuid");
        UUID target = parseUuid(ctx, "target_uuid");
        if (actor == null || target == null) {
            return 0;
        }
        // Membership check for the two endpoints only — direct UUID resolution,
        // never a level-wide entity scan.
        var level = ctx.getSource().getLevel();
        var known = new java.util.HashSet<UUID>();
        for (UUID u : java.util.List.of(actor, target)) {
            if (level.getEntity(u) instanceof com.storynpcs.entity.StoryNpcEntity) {
                known.add(u);
            }
        }
        var reject = mod.getLinkedNpcRuntime().link(actor, target, known);
        if (reject != null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Link rejected: " + reject));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aLinked " + actor + " → " + target), true);
        return 1;
    }

    private static int unlinkNpc(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        UUID actor = parseUuid(ctx, "actor_uuid");
        if (actor == null) {
            return 0;
        }
        if (!mod.getLinkedNpcRuntime().unlink(actor)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] No link on " + actor));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aUnlinked " + actor), true);
        return 1;
    }

    private static int listTimers(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var timers = mod.getTimerRuntime().timers();
        if (timers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[StoryNPCs] No scheduled timers."), false);
            return 0;
        }
        for (var t : timers.values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  §7%s — actor=%s, event=%s, next=%d, period=%dt",
                    t.timerId(), t.actorId(), t.eventId(), t.nextFireTick(), t.periodTicks())), false);
        }
        return timers.size();
    }

    private static int scheduleTimer(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        UUID actor = parseUuid(ctx, "actor_uuid");
        if (actor == null) {
            return 0;
        }
        long first = ctx.getSource().getLevel().getGameTime()
                + IntegerArgumentType.getInteger(ctx, "delay_ticks");
        try {
            var entry = mod.getTimerRuntime().schedule(actor, first,
                    IntegerArgumentType.getInteger(ctx, "period_ticks"),
                    StringArgumentType.getString(ctx, "event_id"));
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aScheduled timer " + entry.timerId() + " → " + entry.eventId()), true);
            return 1;
        } catch (java.io.IOException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Timer persist failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int cancelTimer(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        UUID timer = parseUuid(ctx, "timer_id");
        if (timer == null) {
            return 0;
        }
        if (!mod.getTimerRuntime().cancel(timer)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] No such timer: " + timer));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aCancelled timer " + timer), true);
        return 1;
    }

    private static UUID parseUuid(CommandContext<CommandSourceStack> ctx, String arg) {
        try {
            return UUID.fromString(StringArgumentType.getString(ctx, arg));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] '" + StringArgumentType.getString(ctx, arg)
                            + "' is not a valid UUID"));
            return null;
        }
    }

    // ── P11-1 definition import ──────────────────────────────────────────────

    private static final java.util.Set<String> IMPORT_FAMILY_DIRS =
            java.util.Set.of("npcs", "dialogues", "quests", "factions", "templates");

    private static int runImport(CommandContext<CommandSourceStack> ctx,
                                 String policyName, boolean dryRun) {
        var mod = modOrNull(ctx);
        var service = mod != null ? mod.getApplicationService() : null;
        if (service == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Service unavailable."));
            return 0;
        }
        var policy = switch (policyName.toLowerCase(java.util.Locale.ROOT)) {
            case "skip" -> com.storynpcs.migration.ConflictPolicy.SKIP;
            case "fail" -> com.storynpcs.migration.ConflictPolicy.FAIL;
            case "replace" -> com.storynpcs.migration.ConflictPolicy.REPLACE;
            case "rename" -> com.storynpcs.migration.ConflictPolicy.RENAME;
            default -> null;
        };
        if (policy == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Unknown policy '" + policyName + "' (skip|fail|replace|rename)"));
            return 0;
        }
        String packageName = StringArgumentType.getString(ctx, "package");
        if (!packageName.matches("[a-zA-Z0-9_.-]+") || packageName.contains("..")) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Invalid package name — a single safe directory segment is required."));
            return 0;
        }
        var server = ctx.getSource().getServer();
        var importDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("storynpcs").resolve("import").resolve(packageName);
        if (!java.nio.file.Files.isDirectory(importDir)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Import package not found: " + importDir));
            return 0;
        }
        java.util.Map<String, java.util.Map<String, String>> documents = new java.util.TreeMap<>();
        try (var stream = java.nio.file.Files.walk(importDir)) {
            for (var file : stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted().toList()) {
                var parent = file.getParent().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                String family = switch (parent) {
                    case "npcs", "npc" -> "npc";
                    case "dialogues", "dialogue" -> "dialogue";
                    case "quests", "quest" -> "quest";
                    case "factions", "faction" -> "faction";
                    case "templates", "template" -> "template";
                    default -> parent; // forwarded so the importer can quarantine it
                };
                documents.computeIfAbsent(family, k -> new java.util.TreeMap<>())
                        .put(file.getFileName().toString(), java.nio.file.Files.readString(file));
            }
        } catch (java.io.IOException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Could not read import package: " + e.getMessage()));
            return 0;
        }
        if (documents.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Package contains no YAML documents: " + importDir));
            return 0;
        }
        var definitionsRoot = mod.getLoader().getLastLoadedRootPath();
        if (definitionsRoot == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Definitions root unknown — world not fully loaded."));
            return 0;
        }
        var sink = new com.storynpcs.migration.RegistryImportSink(
                service, mod.getRegistry());
        var importer = new com.storynpcs.migration.DefinitionImporter();
        var source = new com.storynpcs.migration.ImportSource(
                com.storynpcs.migration.ImportSource.Kind.STORYNPCS_YAML_PACKAGE,
                packageName, com.storynpcs.migration.ImportSource.CURRENT_FORMAT_VERSION,
                importDir.toString());
        var plan = importer.plan(source, policy, documents, sink);
        var report = dryRun ? importer.dryRun(plan) : importer.apply(plan, sink);
        if (!dryRun) {
            // Import applies/rollbacks go through non-canonical sink ops — re-evaluate
            // live dialogue sessions against the post-apply registry (P5-1 reload
            // invalidation parity with canonical mutations).
            service.notifyDialogueDefinitionsReloaded();
            service.notifyRecipesLoaded("import");
        }
        final boolean wasDryRun = report.dryRun();
        long applied = report.count(com.storynpcs.migration.ImportReport.StepResult.Outcome.APPLIED);
        long wouldApply = report.count(com.storynpcs.migration.ImportReport.StepResult.Outcome.WOULD_APPLY);
        long quarantined = report.count(com.storynpcs.migration.ImportReport.StepResult.Outcome.QUARANTINED);
        long failed = report.count(com.storynpcs.migration.ImportReport.StepResult.Outcome.FAILED);
        long skipped = report.count(com.storynpcs.migration.ImportReport.StepResult.Outcome.SKIPPED);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Import %s '%s' (policy=%s): %d apply, %d skip, %d quarantine, %d failed — rollback=%s",
                wasDryRun ? "dry-run" : "apply", packageName, policyName,
                wasDryRun ? wouldApply : applied, skipped, quarantined, failed,
                report.rollbackOutcome())), true);
        for (var r : report.steps()) {
            final var line = Component.literal(String.format("  §7[%s] %s/%s%s — %s",
                    r.outcome(), r.family(), r.sourceName(),
                    r.resolvedId() != null && !r.resolvedId().equals(r.definitionId())
                            ? "→" + r.resolvedId() : "",
                    r.detail()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (!report.failureReason().isBlank()) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] " + report.failureReason()));
        }
        return report.steps().isEmpty() ? 0 : 1;
    }

    // ── P2-2 persistence store manifest ─────────────────────────────────────

    private static int listStores(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "--- StoryNPCs Persistence Stores (18-store manifest) ---"), false);
        for (var entry : com.storynpcs.persistence.PersistenceStoreMap.all()) {
            final var line = Component.literal(String.format(
                    " §7%s§r [%s/%s] → %s §8(issue %s)",
                    entry.targetSymbol(), entry.ownership(), entry.status(), entry.store(), entry.ownerIssue()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    // ── P10-2 patch-plan validation ──────────────────────────────────────────

    private static int validatePatchPlan(CommandContext<CommandSourceStack> ctx) {
        var mod = modOrNull(ctx);
        if (mod == null) {
            ctx.getSource().sendFailure(Component.literal("[StoryNPCs] Mod not initialized."));
            return 0;
        }
        String planName = StringArgumentType.getString(ctx, "plan");
        if (!planName.matches("[a-zA-Z0-9_.-]+") || planName.contains("..")) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Invalid plan name — a single safe filename stem is required."));
            return 0;
        }
        var server = ctx.getSource().getServer();
        var planFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("storynpcs").resolve("patches").resolve(planName + ".yaml");
        if (!java.nio.file.Files.isRegularFile(planFile)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Patch plan not found: " + planFile));
            return 0;
        }
        com.storynpcs.authoring.ai.PatchPlan plan;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            plan = mapper.readValue(planFile.toFile(), com.storynpcs.authoring.ai.PatchPlan.class);
        } catch (java.io.IOException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[StoryNPCs] Patch plan could not be parsed: " + e.getMessage()));
            return 0;
        }
        var existingIds = new java.util.HashSet<String>();
        mod.getRegistry().getAllNpcs().forEach(n -> existingIds.add(n.getId().toString()));
        mod.getRegistry().getAllDialogues().forEach(d -> existingIds.add(d.getId().toString()));
        mod.getRegistry().getAllQuests().forEach(q -> existingIds.add(q.getId().toString()));
        mod.getRegistry().getAllFactions().forEach(f -> existingIds.add(f.getId().toString()));
        var report = new com.storynpcs.authoring.ai.PatchPlanValidator().dryRun(
                plan, com.storynpcs.authoring.ai.SchemaBundle.current(),
                existingIds, mod.getRegistry().revision());
        var diagnostics = report.diagnostics();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[StoryNPCs] Patch plan '%s': %d ops, %d dependencies — %s",
                planName, plan.deduplicated().ops().size(), report.dependencies().size(),
                diagnostics.hasErrors() ? "REJECTED" : "valid")), true);
        if (!diagnostics.getDiagnostics().isEmpty()) {
            final var issues = Component.literal(diagnostics.formatReport(10));
            ctx.getSource().sendSuccess(() -> issues, false);
        }
        for (var summary : report.operationSummary()) {
            final var line = Component.literal("  §7" + summary);
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return diagnostics.hasErrors() ? 0 : 1;
    }
}
