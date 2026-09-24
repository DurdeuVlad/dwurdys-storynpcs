package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.ai.Waypoint;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * NPC Moving Path — Authors patrol routes by clicking waypoints in the world.
 * - Right-click NPC: Binds the tool to that NPC.
 * - Right-click block: Appends a waypoint, switches AI to PATHING, and saves YAML.
 * - Sneak + right-click air: Clears waypoints for the selected NPC.
 */
public class NpcPathItem extends Item {

    public NpcPathItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_path.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_path.tooltip.2").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target instanceof StoryNpcEntity npc) {
            if (player.level().isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            if (player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2)) {
                var defOpt = npc.getDefinition();
                if (defOpt.isPresent()) {
                    NamespacedId id = defOpt.get().getId();
                    StoryNpcs mod = StoryNpcs.getInstance();
                    if (mod == null) {
                        return InteractionResult.FAIL;
                    }
                    mod.getRuntimeSessions(serverPlayer.getServer()).selectPathNpc(serverPlayer.getUUID(), id);
                    String name = defOpt.get().getDisplay() != null ? defOpt.get().getDisplay().getName() : id.toString();
                    int points = defOpt.get().getAi() != null ? defOpt.get().getAi().getWaypointPath().size() : 0;
                    serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs Pather] Selected '§f" + name + "§a' (" + id + ") [current waypoints: " + points + "]. Right-click blocks to add waypoints!"));
                    return InteractionResult.SUCCESS;
                }
            }
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("§c[StoryNPCs] You must have operator level 2 to use the Pathing tool."));
            }
            return InteractionResult.FAIL;
        }

        StoryNpcs mod = StoryNpcs.getInstance();
        if (mod == null) return InteractionResult.FAIL;

        NamespacedId id = mod.getRuntimeSessions(serverPlayer.getServer()).selectedPathNpc(serverPlayer.getUUID());
        if (id == null) {
            serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Pather] No NPC selected! Right-click an existing NPC first."));
            return InteractionResult.FAIL;
        }

        var npcOpt = mod.getRegistry().getNpc(id);
        if (npcOpt.isEmpty()) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Pather] Selected NPC '" + id + "' is not loaded."));
            return InteractionResult.FAIL;
        }

        BlockPos targetPos = context.getClickedPos().relative(context.getClickedFace());
        Waypoint wp = new Waypoint(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);

        var service = mod.getApplicationService();
        var result = service.mutateNpc(new com.storynpcs.service.MutationRequest(
                "npc.mutate", "player:" + serverPlayer.getUUID(), "npc.mutate", id,
                service.currentRevision("npc", id), java.util.UUID.randomUUID(), 2), def -> {
            if (def.getAi() == null) {
                def.setAi(new NpcAi());
            }
            def.getAi().getWaypointPath().addWaypoint(wp);
            def.getAi().setMovementType(NpcAi.MovementType.PATHING);
        });
        if (result.hasErrors()) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Pather] Failed to persist path: " + result.formatReport(2)));
            return InteractionResult.FAIL;
        }

        // Live refresh in-world entities
        if (serverPlayer.getServer() != null) {
            for (net.minecraft.server.level.ServerLevel sl : serverPlayer.getServer().getAllLevels()) {
                for (net.minecraft.world.entity.Entity entity : sl.getAllEntities()) {
                    if (entity instanceof StoryNpcEntity sne && id.toString().equals(sne.getDefinitionId())) {
                        sne.applyDefinition();
                    }
                }
            }
        }

        int count = mod.getRegistry().getNpc(id).flatMap(def ->
                java.util.Optional.ofNullable(def.getAi()))
                .map(ai -> ai.getWaypointPath().size()).orElse(0);
        serverPlayer.sendSystemMessage(Component.literal(String.format("§a[StoryNPCs Pather] Added waypoint #%d at (%d, %d, %d) for '%s' (set to PATHING).",
                count, targetPos.getX(), targetPos.getY(), targetPos.getZ(), id)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer && serverPlayer.isShiftKeyDown()) {
            if (!serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal(
                        "§c[StoryNPCs] You must have operator level 2 to clear an NPC path."));
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            StoryNpcs mod = StoryNpcs.getInstance();
            NamespacedId id = mod == null ? null : mod.getRuntimeSessions(serverPlayer.getServer()).selectedPathNpc(serverPlayer.getUUID());
            if (id != null) {
                if (mod != null) {
                    var npcOpt = mod.getRegistry().getNpc(id);
                    if (npcOpt.isPresent()) {
                        var service = mod.getApplicationService();
                        var result = service.mutateNpc(new com.storynpcs.service.MutationRequest(
                                "npc.mutate", "player:" + serverPlayer.getUUID(), "npc.mutate", id,
                                service.currentRevision("npc", id), java.util.UUID.randomUUID(), 2), def -> {
                            if (def.getAi() != null) {
                                def.getAi().getWaypointPath().setWaypoints(new java.util.ArrayList<>());
                                def.getAi().setMovementType(NpcAi.MovementType.STANDING);
                            }
                        });
                        if (!result.hasErrors()) {
                            serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Pather] Cleared all waypoints for '" + id + "'. Switched to STANDING."));
                            return InteractionResultHolder.success(player.getItemInHand(hand));
                        }
                        serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Pather] Failed to clear path: " + result.formatReport(2)));
                        return InteractionResultHolder.fail(player.getItemInHand(hand));
                    }
                }
            }
        }
        return super.use(level, player, hand);
    }
}
