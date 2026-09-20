package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import com.storynpcs.service.DialogueView;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

public class StoryNpcsNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(StoryNpcs.MOD_ID).versioned("1.0.0");

        registrar.playToServer(
                ServerboundDialogueChoosePayload.TYPE,
                ServerboundDialogueChoosePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleDialogueChoose(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundDialogueOpenPayload.TYPE,
                ClientboundDialogueOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openDialogue(payload));
                }
        );

        registrar.playToClient(
                ClientboundDialogueClosePayload.TYPE,
                ClientboundDialogueClosePayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(com.storynpcs.client.StoryNpcsClient::closeDialogue);
                }
        );

        // VULN-49: register editor-open payload so /storynpcs dialogue edit actually opens the GUI
        registrar.playToClient(
                ClientboundDialogueEditorOpenPayload.TYPE,
                ClientboundDialogueEditorOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openDialogueEditor(payload));
                }
        );

        registrar.playToServer(
                ServerboundDialogueSavePayload.TYPE,
                ServerboundDialogueSavePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleDialogueSave(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundDialogueSaveResultPayload.TYPE,
                ClientboundDialogueSaveResultPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.handleDialogueSaveResult(payload));
                }
        );

        // NPC Editor payloads
        registrar.playToClient(
                ClientboundNpcEditorOpenPayload.TYPE,
                ClientboundNpcEditorOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openNpcEditor(payload));
                }
        );

        registrar.playToServer(
                ServerboundNpcSavePayload.TYPE,
                ServerboundNpcSavePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleNpcSave(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundNpcSaveResultPayload.TYPE,
                ClientboundNpcSaveResultPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.handleNpcSaveResult(payload));
                }
        );
    }

    private static void handleDialogueSave(ServerPlayer player, ServerboundDialogueSavePayload payload) {
        // Editor saves are an admin operation — same gate as /storynpcs dialogue edit.
        if (!player.hasPermissions(2)) {
            sendSaveResult(player, false, "Insufficient permissions — dialogue editing requires operator level 2.");
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendSaveResult(player, false, "StoryNPCs service is not available on this server.");
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.dialogueId());
        } catch (Exception e) {
            sendSaveResult(player, false, "Malformed dialogue id: '" + payload.dialogueId() + "'");
            return;
        }
        var graphOpt = com.storynpcs.domain.dialogue.DialogueGraphSerde.fromJson(payload.graphJson());
        if (graphOpt.isEmpty()) {
            sendSaveResult(player, false, "Malformed graph data — save rejected.");
            return;
        }
        var result = service.saveDialogue(id, graphOpt.get());
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed" : result.getErrors().get(0).toString();
            sendSaveResult(player, false, "Save rejected: " + first + (result.getErrors().size() > 1
                    ? " (+" + (result.getErrors().size() - 1) + " more)" : ""));
        } else {
            sendSaveResult(player, true, "Dialogue '" + id + "' saved to disk and reloaded.");
        }
    }

    private static void sendSaveResult(ServerPlayer player, boolean success, String message) {
        PacketDistributor.sendToPlayer(player, new ClientboundDialogueSaveResultPayload(success, message));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    private static void handleNpcSave(ServerPlayer player, ServerboundNpcSavePayload payload) {
        if (!player.hasPermissions(2)) {
            sendNpcSaveResult(player, false, "Insufficient permissions — NPC editing requires operator level 2.");
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendNpcSaveResult(player, false, "StoryNPCs service is not available on this server.");
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.npcId());
        } catch (Exception e) {
            sendNpcSaveResult(player, false, "Malformed NPC id: '" + payload.npcId() + "'");
            return;
        }
        var npcOpt = com.storynpcs.domain.npc.NpcDefinitionSerde.fromJson(payload.npcJson());
        if (npcOpt.isEmpty()) {
            sendNpcSaveResult(player, false, "Malformed NPC data — save rejected.");
            return;
        }
        com.storynpcs.domain.npc.NpcDefinition def = npcOpt.get();
        def.setId(id);
        var result = service.saveNpc(def);
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed" : result.getErrors().get(0).toString();
            sendNpcSaveResult(player, false, "Save rejected: " + first);
        } else {
            // Live refresh all in-world entities of this definition
            if (player.getServer() != null) {
                for (net.minecraft.server.level.ServerLevel level : player.getServer().getAllLevels()) {
                    for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                        if (entity instanceof com.storynpcs.entity.StoryNpcEntity npc && id.toString().equals(npc.getDefinitionId())) {
                            npc.applyDefinition();
                        }
                    }
                }
            }
            sendNpcSaveResult(player, true, "NPC '" + id + "' saved to disk and updated in world.");
        }
    }

    private static void sendNpcSaveResult(ServerPlayer player, boolean success, String message) {
        PacketDistributor.sendToPlayer(player, new ClientboundNpcSaveResultPayload(success, message));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    private static final java.util.Map<java.util.UUID, Long> LAST_CHOICE_MILLIS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void handleDialogueChoose(ServerPlayer player, ServerboundDialogueChoosePayload payload) {
        // Prevent dead or spectator player exploiting dialogue (VULN-12)
        if (!player.isAlive() || player.isSpectator()) {
            var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
            if (service != null) service.closeDialogue(player.getUUID());
            sendCloseDialogue(player);
            return;
        }

        // Rate-limit incoming choice packets to prevent main-thread disk I/O DoS (VULN-13)
        long now = System.currentTimeMillis();
        Long last = LAST_CHOICE_MILLIS.get(player.getUUID());
        if (last != null && now - last < 100) {
            return;
        }
        LAST_CHOICE_MILLIS.put(player.getUUID(), now);

        try {
            var service = StoryNpcs.getInstance().getApplicationService();
            if (service != null) {
                // Validate interaction distance and dimension (VULN-11)
                var sessionOpt = service.getActiveSession(player.getUUID());
                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();
                    if (session.hasLocation()) {
                        String currentDim = player.level().dimension().location().toString();
                        if (!currentDim.equals(session.getDimensionId())) {
                            service.closeDialogue(player.getUUID());
                            sendCloseDialogue(player);
                            player.sendSystemMessage(Component.literal("§c[StoryNPCs] You cannot continue dialogue across dimensions."), true);
                            return;
                        }
                        double distSq = player.distanceToSqr(session.getOriginX(), session.getOriginY(), session.getOriginZ());
                        if (distSq > 144.0) { // 12 block interaction leash
                            service.closeDialogue(player.getUUID());
                            sendCloseDialogue(player);
                            player.sendSystemMessage(Component.literal("§c[StoryNPCs] You walked too far away from the conversation."), true);
                            return;
                        }
                    }
                }

                DialogueView view = service.chooseDialogueOption(player.getUUID(), payload.optionIndex());
                if (view.isTerminal()) {
                    if (view.options().isEmpty()) {
                        if (view.nodeId().isEmpty()) {
                            sendCloseDialogue(player);
                        } else {
                            // Show final completion text cleanly (VULN-14)
                            sendOpenDialogue(player, view);
                        }
                    } else {
                        sendOpenDialogue(player, view);
                    }
                } else {
                    sendOpenDialogue(player, view);
                }
            }
        } catch (Throwable t) {
            System.err.println("Error processing dialogue option for player " + player.getName().getString() + ": " + t.getMessage());
            sendCloseDialogue(player);
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] A dialogue error occurred. Dialogue closed."), true);
        }
    }

    public static void clearPlayer(java.util.UUID playerUuid) {
        if (playerUuid != null) {
            LAST_CHOICE_MILLIS.remove(playerUuid);
        }
    }

    public static void sendOpenDialogue(ServerPlayer player, DialogueView view) {
        List<String> sanitizedOptions = view.options() != null
                ? view.options().stream().map(s -> s != null ? s : "").toList()
                : java.util.List.of();

        List<String> sanitizedHints = view.optionHints() != null
                ? view.optionHints().stream().map(s -> s != null ? s : "").toList()
                : java.util.List.of();

        PacketDistributor.sendToPlayer(
                player,
                new ClientboundDialogueOpenPayload(
                        view.dialogueId() != null ? view.dialogueId().toString() : "",
                        view.nodeId() != null ? view.nodeId() : "",
                        view.text() != null ? view.text() : "",
                        view.sound() != null ? view.sound() : "",
                        sanitizedOptions,
                        view.isTerminal(),
                        view.npcName() != null ? view.npcName() : "",
                        sanitizedHints
                )
        );
    }

    public static void sendCloseDialogue(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ClientboundDialogueClosePayload.INSTANCE);
    }
}
