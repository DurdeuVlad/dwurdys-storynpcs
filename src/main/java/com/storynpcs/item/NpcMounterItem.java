package com.storynpcs.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;

/**
 * NPC Mounter — Mounts or dismounts entities onto each other (e.g. NPC on Horse).
 * - Click entity A: Selects passenger.
 * - Click entity B: Mounts passenger onto entity B.
 * - Sneak-click entity: Dismounts entity.
 * - Right-click a block with a passenger selected: seats the passenger on a
 *   transient chair mount (#148).
 */
public class NpcMounterItem extends Item {

    public NpcMounterItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_mounter.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_mounter.tooltip.2").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] You must have operator level 2 to use the Mounter tool."));
            return InteractionResult.FAIL;
        }

        // Sneak-click to dismount
        if (serverPlayer.isShiftKeyDown()) {
            if (target.isPassenger()) {
                target.stopRiding();
                serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Mounter] Dismounted '" + target.getName().getString() + "'."));
            } else if (!target.getPassengers().isEmpty()) {
                target.ejectPassengers();
                serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Mounter] Ejected all passengers from '" + target.getName().getString() + "'."));
            } else {
                serverPlayer.sendSystemMessage(Component.literal("§7[StoryNPCs Mounter] Entity is neither a passenger nor carrying any."));
            }
            StoryNpcs mod = StoryNpcsAccess.mod(serverPlayer);
            if (mod != null) {
                mod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());
            }
            return InteractionResult.SUCCESS;
        }

        StoryNpcs mod = StoryNpcsAccess.mod(serverPlayer);
        if (mod == null) {
            return InteractionResult.FAIL;
        }
        Integer passengerId = mod.getRuntimeSessions(serverPlayer.getServer()).selectedPassenger(serverPlayer.getUUID());
        if (passengerId == null) {
            // First click: select passenger
            mod.getRuntimeSessions(serverPlayer.getServer()).selectPassenger(serverPlayer.getUUID(), target.getId());
            serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs Mounter] Selected passenger: '§f" + target.getName().getString() + "§a'. Now right-click the vehicle entity to mount."));
            return InteractionResult.SUCCESS;
        } else {
            // Second click: mount onto vehicle
            Entity passenger = serverPlayer.level().getEntity(passengerId);
            mod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());

            if (passenger == null || !passenger.isAlive()) {
                serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Mounter] Previously selected passenger is no longer available."));
                return InteractionResult.FAIL;
            }

            if (passenger.getId() == target.getId()) {
                serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Mounter] An entity cannot mount itself! Selection cancelled."));
                return InteractionResult.FAIL;
            }

            boolean success = passenger.startRiding(target, true);
            if (success) {
                serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs Mounter] Successfully mounted '§f" + passenger.getName().getString() + "§a' onto '§f" + target.getName().getString() + "§a'!"));
                return InteractionResult.SUCCESS;
            } else {
                serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Mounter] Could not mount entities together."));
                return InteractionResult.FAIL;
            }
        }
    }

    /**
     * Right-click a block with a selected passenger: spawns a transient
     * {@link com.storynpcs.entity.ChairMountEntity} on the clicked face and
     * seats the passenger on it (#148). Without a selection the click is a
     * no-op hint — the chair needs a rider.
     */
    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            return InteractionResult.FAIL;
        }
        StoryNpcs mod = StoryNpcsAccess.mod(serverPlayer);
        if (mod == null) {
            return InteractionResult.FAIL;
        }
        if (context.getClickedFace() != net.minecraft.core.Direction.UP) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§7[StoryNPCs Mounter] Click the top face of a block to seat the passenger."));
            return InteractionResult.FAIL;
        }
        Integer passengerId = mod.getRuntimeSessions(serverPlayer.getServer())
                .selectedPassenger(serverPlayer.getUUID());
        if (passengerId == null) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§7[StoryNPCs Mounter] Select a passenger entity first, then click a block to seat it."));
            return InteractionResult.FAIL;
        }
        Entity passenger = level.getEntity(passengerId);
        if (passenger == null || !passenger.isAlive()) {
            mod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());
            serverPlayer.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs Mounter] Previously selected passenger is no longer available."));
            return InteractionResult.FAIL;
        }
        var chair = new com.storynpcs.entity.ChairMountEntity(
                com.storynpcs.entity.StoryNpcRegistry.NPC_CHAIR_MOUNT.get(), level);
        var seat = net.minecraft.world.phys.Vec3.atBottomCenterOf(context.getClickedPos().above());
        chair.setPos(seat.x, seat.y, seat.z);
        level.addFreshEntity(chair);
        mod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());
        if (passenger.startRiding(chair, true)) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§a[StoryNPCs Mounter] Seated '§f" + passenger.getName().getString() + "§a'."));
            return InteractionResult.SUCCESS;
        }
        chair.discard();
        serverPlayer.sendSystemMessage(Component.literal(
                "§c[StoryNPCs Mounter] Could not seat the passenger."));
        return InteractionResult.FAIL;
    }
}
