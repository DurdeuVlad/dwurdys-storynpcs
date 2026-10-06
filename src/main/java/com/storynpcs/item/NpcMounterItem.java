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
            StoryNpcs sneakMod = StoryNpcsAccess.mod(serverPlayer);
            if (target.isPassenger()) {
                target.stopRiding();
                CreatorToolAudit.publish(sneakMod, serverPlayer, "mounter", "dismount",
                        target.getName().getString(), "applied");
                serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Mounter] Dismounted '" + target.getName().getString() + "'."));
            } else if (!target.getPassengers().isEmpty()) {
                target.ejectPassengers();
                CreatorToolAudit.publish(sneakMod, serverPlayer, "mounter", "eject",
                        target.getName().getString(), "applied");
                serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Mounter] Ejected all passengers from '" + target.getName().getString() + "'."));
            } else {
                serverPlayer.sendSystemMessage(Component.literal("§7[StoryNPCs Mounter] Entity is neither a passenger nor carrying any."));
            }
            if (sneakMod != null) {
                sneakMod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());
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

            // P8-2 legal-relationship gate: self-mounts, cycles, stacks past
            // depth 4, and already-mounted passengers are rejected before the
            // world mutation — not just by vanilla's implicit checks.
            var reject = com.storynpcs.creator.tools.MountPolicy.check(
                    passenger.getUUID(), target.getUUID(), mountChain(passenger, target));
            if (reject != null) {
                String why = switch (reject) {
                    case SELF -> "An entity cannot mount itself!";
                    case CYCLE -> "That would create a mount cycle!";
                    case STACK_TOO_DEEP -> "Mount stack would exceed depth "
                            + com.storynpcs.creator.tools.MountPolicy.MAX_STACK_DEPTH + "!";
                    case PASSENGER_ALREADY_MOUNTED -> "Passenger is already mounted — dismount it first.";
                };
                CreatorToolAudit.publish(mod, serverPlayer, "mounter", "mount", target.getName().getString(),
                        "rejected:" + reject);
                serverPlayer.sendSystemMessage(Component.literal(
                        "§c[StoryNPCs Mounter] " + why + " Selection cancelled."));
                return InteractionResult.FAIL;
            }

            boolean success = passenger.startRiding(target, true);
            if (success) {
                CreatorToolAudit.publish(mod, serverPlayer, "mounter", "mount",
                        target.getName().getString(), "applied");
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
        if (passenger.getVehicle() != null) {
            mod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());
            CreatorToolAudit.publish(mod, serverPlayer, "mounter", "seat", passenger.getName().getString(),
                    "rejected:PASSENGER_ALREADY_MOUNTED");
            serverPlayer.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs Mounter] Passenger is already mounted — dismount it first."));
            return InteractionResult.FAIL;
        }
        var chair = new com.storynpcs.entity.ChairMountEntity(
                com.storynpcs.entity.StoryNpcRegistry.NPC_CHAIR_MOUNT.get(), level);
        var seat = net.minecraft.world.phys.Vec3.atBottomCenterOf(context.getClickedPos().above());
        chair.setPos(seat.x, seat.y, seat.z);
        level.addFreshEntity(chair);
        mod.getRuntimeSessions(serverPlayer.getServer()).clearSelectedPassenger(serverPlayer.getUUID());
        if (passenger.startRiding(chair, true)) {
            CreatorToolAudit.publish(mod, serverPlayer, "mounter", "seat",
                    passenger.getName().getString(), "applied");
            serverPlayer.sendSystemMessage(Component.literal(
                    "§a[StoryNPCs Mounter] Seated '§f" + passenger.getName().getString() + "§a'."));
            return InteractionResult.SUCCESS;
        }
        chair.discard();
        serverPlayer.sendSystemMessage(Component.literal(
                "§c[StoryNPCs Mounter] Could not seat the passenger."));
        return InteractionResult.FAIL;
    }

    /**
     * Passenger→vehicle edges the mount policy needs: the candidate's own
     * mount plus the prospective vehicle's full ancestor chain (cycle + depth
     * detection only inspect upward links).
     */
    public static java.util.Map<java.util.UUID, java.util.UUID> mountChain(
            Entity passenger, Entity vehicle) {
        java.util.Map<java.util.UUID, java.util.UUID> mounts = new java.util.HashMap<>();
        if (passenger.getVehicle() != null) {
            mounts.put(passenger.getUUID(), passenger.getVehicle().getUUID());
        }
        for (Entity cursor = vehicle; cursor.getVehicle() != null; cursor = cursor.getVehicle()) {
            mounts.put(cursor.getUUID(), cursor.getVehicle().getUUID());
        }
        return mounts;
    }
}
