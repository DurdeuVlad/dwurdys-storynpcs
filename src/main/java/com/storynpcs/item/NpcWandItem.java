package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.entity.StoryNpcRegistry;
import com.storynpcs.network.ClientboundNpcEditorOpenPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC Wand — The foundational authoring tool of CustomNPCs / StoryNPCs.
 * - Right-click block: Spawns a new NPC and immediately opens the NPC Editor GUI.
 * - Right-click NPC: Opens the NPC Editor GUI for that existing NPC.
 * - Left-click NPC (creative): Despawns the NPC.
 */
public class NpcWandItem extends Item {

    public NpcWandItem(Properties properties) {
        super(properties);
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
                player.sendSystemMessage(Component.literal("§c[StoryNPCs] You must have operator level 2 to use the NPC Wand."));
            }
            return InteractionResult.FAIL;
        }

        StoryNpcs mod = StoryNpcs.getInstance();
        if (mod == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] Mod instance not initialized."));
            return InteractionResult.FAIL;
        }

        BlockPos clickedPos = context.getClickedPos();
        BlockPos spawnPos = clickedPos.relative(context.getClickedFace());
        Vec3 spawnVec = Vec3.atBottomCenterOf(spawnPos);

        // Generate unique namespaced ID for this new NPC definition
        String uniqueSuffix = String.valueOf(System.currentTimeMillis() % 100000);
        NamespacedId id = NamespacedId.of("storynpcs", "npc_" + uniqueSuffix);
        String defaultName = "StoryNPC " + uniqueSuffix;

        NpcDefinition def = new NpcDefinition(id, defaultName);
        var result = mod.getApplicationService().saveNpc(def);
        if (result.hasErrors()) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to scaffold NPC: " + result.formatReport(3)));
            return InteractionResult.FAIL;
        }

        StoryNpcEntity entity = StoryNpcRegistry.STORY_NPC.get().create(serverPlayer.serverLevel());
        if (entity == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to instantiate NPC entity."));
            return InteractionResult.FAIL;
        }

        entity.setPos(spawnVec.x, spawnVec.y, spawnVec.z);
        entity.setDefinitionId(id.toString());
        entity.setStartPosition(entity.blockPosition());
        serverPlayer.serverLevel().addFreshEntity(entity);

        // Open the in-game editor immediately on the admin's client
        PacketDistributor.sendToPlayer(serverPlayer, new ClientboundNpcEditorOpenPayload(
                id.toString(),
                NpcDefinitionSerde.toJson(def)
        ));

        serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs] Created and spawned '" + defaultName + "' (" + id + ")! Opened editor."));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target instanceof StoryNpcEntity npc) {
            if (player.level().isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            if (player instanceof ServerPlayer serverPlayer) {
                if (!serverPlayer.hasPermissions(2)) {
                    serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] You must have operator level 2 to edit NPCs."));
                    return InteractionResult.FAIL;
                }

                var defOpt = npc.getDefinition();
                if (defOpt.isPresent()) {
                    PacketDistributor.sendToPlayer(serverPlayer, new ClientboundNpcEditorOpenPayload(
                            defOpt.get().getId().toString(),
                            NpcDefinitionSerde.toJson(defOpt.get())
                    ));
                    return InteractionResult.SUCCESS;
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs] NPC definition '" + npc.getDefinitionId() + "' is not loaded."));
                    return InteractionResult.FAIL;
                }
            }
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        if (entity instanceof StoryNpcEntity npc && player.hasPermissions(2) && !player.level().isClientSide()) {
            npc.discard();
            player.sendSystemMessage(Component.literal("§e[StoryNPCs] Despawned NPC '" + npc.getName().getString() + "'."));
            return true;
        }
        return super.onLeftClickEntity(stack, player, entity);
    }
}
