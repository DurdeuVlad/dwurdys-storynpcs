package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Teleporter tool (P8-2): click an NPC to select it, then click a block to
 * teleport the NPC there. Operator-level tool — selections are per-player
 * server sessions, the move is audited, and a stale/despawned selection
 * recovers cleanly instead of targeting nothing.
 */
public class NpcTeleporterItem extends Item {

    public NpcTeleporterItem(Properties properties) {
        super(properties);
    }

    private boolean allowed(Player player) {
        return player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!allowed(player) || !(target instanceof StoryNpcEntity)) {
            return InteractionResult.PASS;
        }
        var serverPlayer = (ServerPlayer) player;
        var mod = StoryNpcsAccess.mod(serverPlayer);
        mod.getRuntimeSessions(serverPlayer.getServer())
                .selectTeleportEntity(serverPlayer.getUUID(), target.getId());
        serverPlayer.sendSystemMessage(Component.literal(String.format(
                "§a[StoryNPCs Teleporter] Selected '§f%s§a' — right-click a block to teleport it there.",
                target.getName().getString())));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            return InteractionResult.PASS;
        }
        // Sneak-click on a block falls through to use() — it clears the
        // selection instead of teleporting onto an unintended target.
        if (serverPlayer.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        var mod = StoryNpcsAccess.mod(serverPlayer);
        var sessions = mod.getRuntimeSessions(serverPlayer.getServer());
        Integer entityId = sessions.selectedTeleportEntity(serverPlayer.getUUID());
        if (entityId == null) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§e[StoryNPCs Teleporter] Right-click an NPC first to select it."));
            return InteractionResult.FAIL;
        }
        Entity entity = serverLevel.getEntity(entityId);
        if (!(entity instanceof StoryNpcEntity npc) || !npc.isAlive()) {
            sessions.clearSelectedTeleportEntity(serverPlayer.getUUID());
            serverPlayer.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs Teleporter] The selected NPC is no longer available — selection cleared."));
            return InteractionResult.FAIL;
        }
        var targetPos = context.getClickedPos().relative(context.getClickedFace());
        npc.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        CreatorToolAudit.publish(mod, serverPlayer, "teleporter", "teleport",
                npc.getName().getString(), "applied");
        serverPlayer.sendSystemMessage(Component.literal(String.format(
                "§a[StoryNPCs Teleporter] Teleported '§f%s§a' to (%d, %d, %d).",
                npc.getName().getString(), targetPos.getX(), targetPos.getY(), targetPos.getZ())));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Sneak + use clears a dangling selection without a world hit.
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2)
                && player.isShiftKeyDown()) {
            StoryNpcsAccess.mod(serverPlayer).getRuntimeSessions(serverPlayer.getServer())
                    .clearSelectedTeleportEntity(serverPlayer.getUUID());
            serverPlayer.sendSystemMessage(Component.literal(
                    "§e[StoryNPCs Teleporter] Selection cleared."));
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }
}
