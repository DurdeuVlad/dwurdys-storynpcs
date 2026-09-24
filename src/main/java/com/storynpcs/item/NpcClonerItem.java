package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.entity.StoryNpcRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * NPC Cloner — Captures NPC templates and spawns duplicates in seconds.
 * - Right-click NPC: Captures its definition template into memory.
 * - Right-click ground: Spawns an instance of the captured template.
 */
public class NpcClonerItem extends Item {

    public NpcClonerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_cloner.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_cloner.tooltip.2").withStyle(ChatFormatting.GRAY));
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
                    NpcDefinition def = defOpt.get();
                    StoryNpcs mod = StoryNpcs.getInstance();
                    if (mod == null) {
                        return InteractionResult.FAIL;
                    }
                    mod.getRuntimeSessions(serverPlayer.getServer()).selectCloneTemplate(serverPlayer.getUUID(), def.getId());
                    String name = def.getDisplay() != null ? def.getDisplay().getName() : def.getId().toString();
                    serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs Cloner] Captured template: '§f" + name + "§a' (" + def.getId() + ")! Right-click ground to spawn clones."));
                    return InteractionResult.SUCCESS;
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Cloner] Target NPC has no valid loaded definition."));
                    return InteractionResult.FAIL;
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
                player.sendSystemMessage(Component.literal("§c[StoryNPCs] You must have operator level 2 to use the Cloner."));
            }
            return InteractionResult.FAIL;
        }

        StoryNpcs mod = StoryNpcs.getInstance();
        if (mod == null) {
            return InteractionResult.FAIL;
        }
        NamespacedId templateId = mod.getRuntimeSessions(serverPlayer.getServer()).cloneTemplate(serverPlayer.getUUID());
        if (templateId == null) {
            serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs Cloner] Cloner is empty! Right-click an existing NPC to capture its template first."));
            return InteractionResult.FAIL;
        }

        if (mod.getRegistry().getNpc(templateId).isEmpty()) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Cloner] Captured template '" + templateId + "' is no longer registered."));
            return InteractionResult.FAIL;
        }

        BlockPos clickedPos = context.getClickedPos();
        BlockPos spawnPos = clickedPos.relative(context.getClickedFace());
        Vec3 spawnVec = Vec3.atBottomCenterOf(spawnPos);

        StoryNpcEntity entity = StoryNpcRegistry.STORY_NPC.get().create(serverPlayer.serverLevel());
        if (entity == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs Cloner] Failed to instantiate NPC entity."));
            return InteractionResult.FAIL;
        }

        entity.setPos(spawnVec.x, spawnVec.y, spawnVec.z);
        entity.setDefinitionId(templateId.toString());
        entity.setStartPosition(entity.blockPosition());
        serverPlayer.serverLevel().addFreshEntity(entity);

        serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs Cloner] Spawned clone of '" + templateId + "' at (" + spawnPos.getX() + ", " + spawnPos.getY() + ", " + spawnPos.getZ() + ")!"));
        return InteractionResult.SUCCESS;
    }
}
