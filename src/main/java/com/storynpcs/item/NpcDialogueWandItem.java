package com.storynpcs.item;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueGraphSerde;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.network.ClientboundDialogueEditorOpenPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * NPC Dialogue Wand — Instant access to the visual directed-graph dialogue editor.
 * - Right-click NPC: Opens the visual dialogue graph editor (scaffolding a starter graph if none exists).
 */
public class NpcDialogueWandItem extends Item {

    public NpcDialogueWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.storynpcs.npc_dialogue_wand.tooltip.1").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target instanceof StoryNpcEntity npc) {
            if (player.level().isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            if (player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2)) {
                StoryNpcs mod = StoryNpcs.getInstance();
                if (mod == null) return InteractionResult.FAIL;

                var defOpt = npc.getDefinition();
                if (defOpt.isEmpty()) {
                    serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] NPC definition is not loaded."));
                    return InteractionResult.FAIL;
                }

                var def = defOpt.get();
                NamespacedId dialogueId = def.getDialogueId();

                if (dialogueId == null) {
                    // Scaffold starter dialogue and assign it automatically
                    String path = (def.getId() != null ? def.getId().getPath() : "npc") + "_dialogue";
                    dialogueId = NamespacedId.of("storynpcs", path);
                    String title = (def.getDisplay() != null ? def.getDisplay().getName() : "NPC") + " Dialogue";

                    var service = mod.getApplicationService();
                    var result = service.createDialogue(new com.storynpcs.service.MutationRequest(
                            "dialogue.create", "player:" + serverPlayer.getUUID(), "dialogue.mutate", dialogueId,
                            service.currentRevision("dialogue", dialogueId), java.util.UUID.randomUUID(), 2), title);
                    if (!result.applied() && mod.getRegistry().getDialogue(dialogueId).isEmpty()) {
                        serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to create dialogue: " + result.formatReport(2)));
                        return InteractionResult.FAIL;
                    }
                    boolean createdByThisAttempt = result.applied();

                    NamespacedId assignedDialogueId = dialogueId;
                    var assignment = service.mutateNpc(new com.storynpcs.service.MutationRequest(
                            "npc.mutate", "player:" + serverPlayer.getUUID(), "npc.mutate", def.getId(),
                            service.currentRevision("npc", def.getId()), java.util.UUID.randomUUID(), 2),
                            npcDefinition -> npcDefinition.setDialogueId(assignedDialogueId));
                    if (!assignment.applied()) {
                        String compensation = "Existing dialogue was left unchanged.";
                        if (createdByThisAttempt) {
                            var rollback = service.deleteUnreferencedDialogue(new com.storynpcs.service.MutationRequest(
                                    "dialogue.delete", "player:" + serverPlayer.getUUID(), "dialogue.delete", dialogueId,
                                    service.currentRevision("dialogue", dialogueId), java.util.UUID.randomUUID(), 2));
                            compensation = rollback.applied()
                                    ? "The newly created, unreferenced dialogue was removed."
                                    : mod.getRegistry().getDialogue(dialogueId).isPresent()
                                            ? "The dialogue remains; safe cleanup was rejected: " + rollback.formatReport(2)
                                            : "The dialogue is already absent; no further cleanup was needed.";
                        }
                        serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to assign dialogue: "
                                + assignment.formatReport(2) + " " + compensation));
                        return InteractionResult.FAIL;
                    }
                    serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs] Created & assigned dialogue '§f" + dialogueId + "§a'."));
                }

                Optional<DialogueGraph> graphOpt = mod.getRegistry().getDialogue(dialogueId);
                if (graphOpt.isPresent()) {
                    PacketDistributor.sendToPlayer(serverPlayer, new ClientboundDialogueEditorOpenPayload(
                            dialogueId.toString(),
                            DialogueGraphSerde.toJson(graphOpt.get()),
                            mod.getApplicationService().currentRevision("dialogue", dialogueId)
                    ));
                    serverPlayer.sendSystemMessage(Component.literal("§a[StoryNPCs] Opening visual dialogue editor for '§f" + dialogueId + "§a'."));
                    return InteractionResult.SUCCESS;
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("§c[StoryNPCs] Could not load dialogue graph: " + dialogueId));
                    return InteractionResult.FAIL;
                }
            }
        }
        return InteractionResult.PASS;
    }
}
