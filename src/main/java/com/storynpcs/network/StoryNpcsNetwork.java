package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.DiagnosticHints;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.MutationRequest;
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

        // Trader/banker role interaction payloads
        registrar.playToClient(
                ClientboundTradeOpenPayload.TYPE,
                ClientboundTradeOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openTrade(payload));
                }
        );

        registrar.playToServer(
                ServerboundTradeExecutePayload.TYPE,
                ServerboundTradeExecutePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleTradeExecute(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundBankOpenPayload.TYPE,
                ClientboundBankOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openBank(payload));
                }
        );

        registrar.playToServer(
                ServerboundBankActionPayload.TYPE,
                ServerboundBankActionPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleBankAction(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundQuestEditorOpenPayload.TYPE,
                ClientboundQuestEditorOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openQuestEditor(payload));
                }
        );

        registrar.playToServer(
                ServerboundQuestSavePayload.TYPE,
                ServerboundQuestSavePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleQuestSave(serverPlayer, payload));
                    }
                }
        );

        registrar.playToServer(
                ServerboundQuestDeletePayload.TYPE,
                ServerboundQuestDeletePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleQuestDelete(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundQuestSaveResultPayload.TYPE,
                ClientboundQuestSaveResultPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.handleQuestSaveResult(payload));
                }
        );

        registrar.playToClient(
                ClientboundFactionEditorOpenPayload.TYPE,
                ClientboundFactionEditorOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openFactionEditor(payload));
                }
        );

        registrar.playToServer(
                ServerboundFactionSavePayload.TYPE,
                ServerboundFactionSavePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleFactionSave(serverPlayer, payload));
                    }
                }
        );

        registrar.playToServer(
                ServerboundFactionDeletePayload.TYPE,
                ServerboundFactionDeletePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleFactionDelete(serverPlayer, payload));
                    }
                }
        );

        registrar.playToClient(
                ClientboundFactionSaveResultPayload.TYPE,
                ClientboundFactionSaveResultPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.handleFactionSaveResult(payload));
                }
        );
    }

    private static void handleFactionSave(ServerPlayer player, ServerboundFactionSavePayload payload) {
        if (!player.hasPermissions(2)) {
            sendFactionSaveResult(player, false, "Insufficient permissions — faction editing requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendFactionSaveResult(player, false, "StoryNPCs service is not available on this server.",
                    payload.requestId(), "SERVICE_UNAVAILABLE", payload.expectedRevision());
            return;
        }
        var factionOpt = com.storynpcs.domain.faction.FactionSerde.fromJson(payload.factionJson());
        if (factionOpt.isEmpty() || factionOpt.get().getId() == null) {
            sendFactionSaveResult(player, false, "Malformed faction data — save rejected.",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var mutation = service.replaceFaction(new MutationRequest(
                "faction.replace", "player:" + player.getUUID(), "faction.edit", factionOpt.get().getId(),
                payload.expectedRevision(), payload.requestId(), 2),
                factionOpt.get());
        var result = mutation.diagnostics();
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
            sendFactionSaveResult(player, false, "Save rejected: " + first + (result.getErrors().size() > 1
                    ? " (+" + (result.getErrors().size() - 1) + " more)" : ""),
                    payload.requestId(), "VALIDATION_REJECTED", mutation.revision());
        } else {
            sendFactionSaveResult(player, true, "Faction '" + factionOpt.get().getId() + "' saved to disk and updated.",
                    payload.requestId(), mutation.duplicate() ? "DUPLICATE" : "APPLIED", mutation.revision());
        }
    }

    private static void handleFactionDelete(ServerPlayer player, ServerboundFactionDeletePayload payload) {
        if (!player.hasPermissions(2)) {
            sendFactionSaveResult(player, false, "Insufficient permissions — faction deletion requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendFactionSaveResult(player, false, "StoryNPCs service is not available on this server.",
                    payload.requestId(), "SERVICE_UNAVAILABLE", payload.expectedRevision());
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.factionId());
        } catch (Exception e) {
            sendFactionSaveResult(player, false, "Malformed faction id: '" + payload.factionId() + "'",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var referencing = service.findNpcsReferencingFaction(id);
        var deleteResult = service.deleteFaction(new MutationRequest(
                "faction.delete", "player:" + player.getUUID(), "faction.delete", id,
                payload.expectedRevision(), payload.requestId(), 2));
        if (deleteResult.applied()) {
            String msg = "Faction '" + id + "' deleted from registry and disk."
                    + (referencing.isEmpty() ? "" : " Warning: NPC(s) " + referencing + " were bound to it — reassign with /storynpcs npc set faction.");
            sendFactionSaveResult(player, true, msg, payload.requestId(), "APPLIED", deleteResult.revision());
        } else {
            sendFactionSaveResult(player, false, "Faction deletion rejected: " + deleteResult.diagnostics().formatReport(3),
                    payload.requestId(), "MUTATION_REJECTED", deleteResult.revision());
        }
    }

    private static void sendFactionSaveResult(ServerPlayer player, boolean success, String message) {
        sendFactionSaveResult(player, success, message, new java.util.UUID(0L, 0L),
                success ? "APPLIED" : "REJECTED", 0L);
    }

    private static void sendFactionSaveResult(ServerPlayer player, boolean success, String message,
                                               java.util.UUID requestId, String code, long revision) {
        var registry = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getRegistry() : null;
        String factionsJson = registry != null
                ? com.storynpcs.domain.faction.FactionSerde.toJsonList(List.copyOf(registry.getAllFactions()))
                : "[]";
        PacketDistributor.sendToPlayer(player, new ClientboundFactionSaveResultPayload(
                success, message, factionsJson, code, requestId, revision));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    private static void handleQuestSave(ServerPlayer player, ServerboundQuestSavePayload payload) {
        if (!player.hasPermissions(2)) {
            sendQuestSaveResult(player, false, "Insufficient permissions — quest editing requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendQuestSaveResult(player, false, "StoryNPCs service is not available on this server.",
                    payload.requestId(), "SERVICE_UNAVAILABLE", payload.expectedRevision());
            return;
        }
        var questOpt = com.storynpcs.domain.quest.QuestSerde.fromJson(payload.questJson());
        if (questOpt.isEmpty() || questOpt.get().getId() == null) {
            sendQuestSaveResult(player, false, "Malformed quest data — save rejected.",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var mutation = service.replaceQuest(new MutationRequest(
                "quest.replace", "player:" + player.getUUID(), "quest.edit", questOpt.get().getId(),
                payload.expectedRevision(), payload.requestId(), 2),
                questOpt.get());
        var result = mutation.diagnostics();
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
            sendQuestSaveResult(player, false, "Save rejected: " + first + (result.getErrors().size() > 1
                    ? " (+" + (result.getErrors().size() - 1) + " more)" : ""),
                    payload.requestId(), "VALIDATION_REJECTED", mutation.revision());
        } else {
            sendQuestSaveResult(player, true, "Quest '" + questOpt.get().getId() + "' saved to disk and updated.",
                    payload.requestId(), mutation.duplicate() ? "DUPLICATE" : "APPLIED", mutation.revision());
        }
    }

    private static void handleQuestDelete(ServerPlayer player, ServerboundQuestDeletePayload payload) {
        if (!player.hasPermissions(2)) {
            sendQuestSaveResult(player, false, "Insufficient permissions — quest deletion requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendQuestSaveResult(player, false, "StoryNPCs service is not available on this server.",
                    payload.requestId(), "SERVICE_UNAVAILABLE", payload.expectedRevision());
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.questId());
        } catch (Exception e) {
            sendQuestSaveResult(player, false, "Malformed quest id: '" + payload.questId() + "'",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var referencing = service.findDialoguesStartingQuest(id);
        var deleteResult = service.deleteQuest(new MutationRequest(
                "quest.delete", "player:" + player.getUUID(), "quest.delete", id,
                payload.expectedRevision(), payload.requestId(), 2));
        if (deleteResult.applied()) {
            String msg = "Quest '" + id + "' deleted from registry and disk."
                    + (referencing.isEmpty() ? "" : " Warning: dialogue(s) " + referencing + " still reference it via START_QUEST.");
            sendQuestSaveResult(player, true, msg, payload.requestId(), "APPLIED", deleteResult.revision());
        } else {
            sendQuestSaveResult(player, false, "Quest deletion rejected: " + deleteResult.diagnostics().formatReport(3),
                    payload.requestId(), "MUTATION_REJECTED", deleteResult.revision());
        }
    }

    private static void sendQuestSaveResult(ServerPlayer player, boolean success, String message) {
        sendQuestSaveResult(player, success, message, new java.util.UUID(0L, 0L),
                success ? "APPLIED" : "REJECTED", 0L);
    }

    private static void sendQuestSaveResult(ServerPlayer player, boolean success, String message,
                                            java.util.UUID requestId, String code, long revision) {
        var registry = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getRegistry() : null;
        String questsJson = registry != null
                ? com.storynpcs.domain.quest.QuestSerde.toJsonList(List.copyOf(registry.getAllQuests()))
                : "[]";
        PacketDistributor.sendToPlayer(player, new ClientboundQuestSaveResultPayload(
                success, message, questsJson, code, requestId, revision));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    private static void handleDialogueSave(ServerPlayer player, ServerboundDialogueSavePayload payload) {
        // Editor saves are an admin operation — same gate as /storynpcs dialogue edit.
        if (!player.hasPermissions(2)) {
            sendSaveResult(player, false, "Insufficient permissions — dialogue editing requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendSaveResult(player, false, "StoryNPCs service is not available on this server.",
                    payload.requestId(), "SERVICE_UNAVAILABLE", payload.expectedRevision());
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.dialogueId());
        } catch (Exception e) {
            sendSaveResult(player, false, "Malformed dialogue id: '" + payload.dialogueId() + "'",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var graphOpt = com.storynpcs.domain.dialogue.DialogueGraphSerde.fromJson(payload.graphJson());
        if (graphOpt.isEmpty()) {
            sendSaveResult(player, false, "Malformed graph data — save rejected.",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var mutation = service.replaceDialogue(new MutationRequest(
                "dialogue.replace", "player:" + player.getUUID(), "dialogue.edit", id,
                payload.expectedRevision(), payload.requestId(), 2),
                graphOpt.get());
        var result = mutation.diagnostics();
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
            sendSaveResult(player, false, "Save rejected: " + first + (result.getErrors().size() > 1
                    ? " (+" + (result.getErrors().size() - 1) + " more)" : ""),
                    payload.requestId(), "VALIDATION_REJECTED", mutation.revision());
        } else {
            sendSaveResult(player, true, "Dialogue '" + id + "' saved to disk and reloaded.",
                    payload.requestId(), mutation.duplicate() ? "DUPLICATE" : "APPLIED", mutation.revision());
        }
    }

    private static void sendSaveResult(ServerPlayer player, boolean success, String message) {
        sendSaveResult(player, success, message, new java.util.UUID(0L, 0L),
                success ? "APPLIED" : "REJECTED", 0L);
    }

    private static void sendSaveResult(ServerPlayer player, boolean success, String message,
                                       java.util.UUID requestId, String code, long revision) {
        PacketDistributor.sendToPlayer(player, new ClientboundDialogueSaveResultPayload(
                success, message, code, requestId, revision));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    private static void handleNpcSave(ServerPlayer player, ServerboundNpcSavePayload payload) {
        if (!player.hasPermissions(2)) {
            sendNpcSaveResult(player, false, "Insufficient permissions — NPC editing requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendNpcSaveResult(player, false, "StoryNPCs service is not available on this server.",
                    payload.requestId(), "SERVICE_UNAVAILABLE", payload.expectedRevision());
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.npcId());
        } catch (Exception e) {
            sendNpcSaveResult(player, false, "Malformed NPC id: '" + payload.npcId() + "'",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        var npcOpt = com.storynpcs.domain.npc.NpcDefinitionSerde.fromJson(payload.npcJson());
        if (npcOpt.isEmpty()) {
            sendNpcSaveResult(player, false, "Malformed NPC data — save rejected.",
                    payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
            return;
        }
        com.storynpcs.domain.npc.NpcDefinition def = npcOpt.get();
        var mutation = service.replaceNpc(new MutationRequest(
                "npc.replace", "player:" + player.getUUID(), "npc.edit", id,
                payload.expectedRevision(), payload.requestId(), 2), def);
        var result = mutation.diagnostics();
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
            sendNpcSaveResult(player, false, "Save rejected: " + first,
                    payload.requestId(), "VALIDATION_REJECTED", mutation.revision());
        } else {
            // Live refresh all in-world entities of this definition
            if (mutation.newlyApplied() && player.getServer() != null) {
                for (net.minecraft.server.level.ServerLevel level : player.getServer().getAllLevels()) {
                    for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                        if (entity instanceof com.storynpcs.entity.StoryNpcEntity npc && id.toString().equals(npc.getDefinitionId())) {
                            npc.applyDefinition();
                        }
                    }
                }
            }
            String message = mutation.duplicate()
                    ? "NPC '" + id + "' was already saved; the duplicate request was acknowledged without refreshing entities."
                    : "NPC '" + id + "' saved to disk and updated in world.";
            sendNpcSaveResult(player, true, message,
                    payload.requestId(), mutation.duplicate() ? "DUPLICATE" : "APPLIED", mutation.revision());
        }
    }

    private static void sendNpcSaveResult(ServerPlayer player, boolean success, String message) {
        sendNpcSaveResult(player, success, message, new java.util.UUID(0L, 0L),
                success ? "APPLIED" : "REJECTED", 0L);
    }

    private static void sendNpcSaveResult(ServerPlayer player, boolean success, String message,
                                          java.util.UUID requestId, String code, long revision) {
        PacketDistributor.sendToPlayer(player, new ClientboundNpcSaveResultPayload(
                success, message, code, requestId, revision));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    // VULN-48 companion: trade/bank action packets mutate inventories and trigger
    // bank-repo disk writes — throttle them like dialogue choices (100 ms).
    private static final long ROLE_ACTION_MIN_INTERVAL_MILLIS = 100;

    /** Returns true when the player's trade/bank action should be dropped as spam. */
    private static boolean isRoleActionThrottled(ServerPlayer player) {
        long now = System.currentTimeMillis();
        var mod = StoryNpcs.getInstance();
        return mod != null && mod.getRuntimeSessions(player.getServer()).throttleRoleAction(
                player.getUUID(), now, ROLE_ACTION_MIN_INTERVAL_MILLIS);
    }

    private static void handleDialogueChoose(ServerPlayer player, ServerboundDialogueChoosePayload payload) {
        // Prevent dead or spectator player exploiting dialogue (VULN-12)
        if (!player.isAlive() || player.isSpectator()) {
            var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
            if (service != null) service.closeDialogue(player.getUUID());
            sendCloseDialogue(player);
            return;
        }

        // Rate-limit incoming choice packets to prevent main-thread disk I/O DoS (VULN-13)
        var mod = StoryNpcs.getInstance();
        if (mod == null) {
            sendCloseDialogue(player);
            return;
        }
        var service = mod.getApplicationService();
        var activeSession = service != null ? service.getActiveSession(player.getUUID()) : java.util.Optional.<com.storynpcs.domain.dialogue.DialogueSession>empty();
        if (activeSession.isEmpty() || !activeSession.get().getSessionId().equals(payload.sessionId())) {
            sendCloseDialogue(player);
            return;
        }
        if (!mod.getRuntimeSessions(player.getServer()).acceptRequest(player.getUUID(), payload.requestId())) {
            return;
        }
        if (mod != null && mod.getRuntimeSessions(player.getServer()).throttleDialogueChoice(
                player.getUUID(), System.currentTimeMillis(), 100)) {
            return;
        }

        try {
            service = StoryNpcs.getInstance().getApplicationService();
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
        var mod = StoryNpcs.getInstance();
        if (mod != null) {
            mod.getRuntimeSessions().clearPlayer(playerUuid);
        }
    }

    public static void clearPlayer(net.minecraft.server.MinecraftServer server, java.util.UUID playerUuid) {
        var mod = StoryNpcs.getInstance();
        if (mod != null) {
            mod.getRuntimeSessions(server).clearPlayer(playerUuid);
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
                        sanitizedHints,
                        StoryNpcs.getInstance().getApplicationService().getActiveSession(player.getUUID())
                                .map(com.storynpcs.domain.dialogue.DialogueSession::getSessionId)
                                .orElse(new java.util.UUID(0L, 0L))
                )
        );
    }

    public static void sendCloseDialogue(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ClientboundDialogueClosePayload.INSTANCE);
    }

    // ---- Trader / banker interaction ----

    /** Opens the trade screen for an NPC whose definition has a trader role. */
    public static void sendTradeOpen(ServerPlayer player, com.storynpcs.domain.npc.NpcDefinition npc) {
        var mod = StoryNpcs.getInstance();
        if (mod == null || npc.getTrader() == null) return;
        var trader = com.storynpcs.domain.role.RoleSerde.copyTrader(npc.getTrader());
        var tradeState = mod.getTradeStateRepository();
        if (tradeState != null) {
            for (var listing : trader.getListings()) {
                try {
                    listing.setUses(tradeState.getUses(npc.getId().toString(), listing.getListingId()));
                } catch (java.io.IOException | RuntimeException ignored) {
                    // The service will fail closed if the durable projection is unavailable.
                }
            }
        }
        var sessionId = mod.getRuntimeSessions(player.getServer()).openRoleSession(
                player.getUUID(), "trade", npc.getId());
        java.util.Map<String, Integer> scores = new java.util.HashMap<>();
        if (mod.getProgressionRepository() != null) {
            var progression = mod.getProgressionRepository().getOrCreate(player.getUUID());
            for (var listing : trader.getListings()) {
                var faction = listing.getRequiredFaction();
                if (faction != null) {
                    int defaultPts = mod.getRegistry().getFaction(faction)
                            .map(com.storynpcs.domain.faction.Faction::getDefaultPoints).orElse(0);
                    scores.put(faction.toString(), progression.getFactionScore(faction, defaultPts));
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new ClientboundTradeOpenPayload(
                npc.getId().toString(), npc.getDisplay().getName(),
                com.storynpcs.domain.role.RoleSerde.toJson(trader),
                com.storynpcs.domain.role.RoleSerde.toJson(scores), sessionId));
    }

    /** Opens the bank screen for an NPC whose definition has a banker role. */
    public static void sendBankOpen(ServerPlayer player, com.storynpcs.domain.npc.NpcDefinition npc) {
        var mod = StoryNpcs.getInstance();
        if (mod == null || npc.getBanker() == null || mod.getBankRepository() == null) return;
        var sessionId = mod.getRuntimeSessions(player.getServer()).openRoleSession(
                player.getUUID(), "bank", npc.getId());
        var vault = mod.getBankRepository().getOrCreate(player.getUUID());
        PacketDistributor.sendToPlayer(player, new ClientboundBankOpenPayload(
                npc.getId().toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(npc.getBanker()),
                com.storynpcs.domain.role.RoleSerde.toJson(vault), sessionId));
    }

    /** True when a live entity of the given definition is within interaction range of the player. */
    private static boolean isNpcNearby(ServerPlayer player, com.storynpcs.domain.common.NamespacedId npcId) {
        var box = player.getBoundingBox().inflate(8.0);
        return !player.serverLevel().getEntitiesOfClass(
                com.storynpcs.entity.StoryNpcEntity.class, box,
                e -> npcId.toString().equals(e.getDefinitionId())).isEmpty();
    }

    private static com.storynpcs.domain.npc.NpcDefinition resolveRoleNpc(
            ServerPlayer player, String npcIdRaw) {
        var mod = StoryNpcs.getInstance();
        if (mod == null) return null;
        com.storynpcs.domain.common.NamespacedId npcId;
        try {
            npcId = com.storynpcs.domain.common.NamespacedId.of(npcIdRaw);
        } catch (Exception e) {
            return null;
        }
        var npcOpt = mod.getRegistry().getNpc(npcId);
        if (npcOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] NPC not found: " + npcIdRaw));
            return null;
        }
        if (!isNpcNearby(player, npcId)) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] That NPC is too far away."), true);
            return null;
        }
        return npcOpt.get();
    }

    private static void handleTradeExecute(ServerPlayer player, ServerboundTradeExecutePayload payload) {
        if (isRoleActionThrottled(player)) return;
        var mod = StoryNpcs.getInstance();
        if (mod == null) return;
        com.storynpcs.domain.common.NamespacedId requestedNpc;
        try {
            requestedNpc = com.storynpcs.domain.common.NamespacedId.of(payload.npcId());
        } catch (Exception e) {
            return;
        }
        if (!mod.getRuntimeSessions(player.getServer()).isRoleSession(
                player.getUUID(), "trade", requestedNpc, payload.sessionId())) {
            return;
        }
        // Trade has a durable request journal; duplicate IDs must reach the
        // canonical service so a lost response can be classified as REPLAYED.
        mod.getRuntimeSessions(player.getServer()).admitRequest(player.getUUID(), payload.requestId());
        var npc = resolveRoleNpc(player, payload.npcId());
        if (npc == null) return;
        var trader = npc.getTrader();
        if (trader == null) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] That NPC is not a trader."), true);
            return;
        }
        var listings = trader.getListings();
        int idx = payload.listingIndex();
        if (idx < 0 || idx >= listings.size()) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] That listing no longer exists."), true);
            sendTradeOpen(player, npc);
            return;
        }
        var listing = listings.get(idx);
        var npcId = requestedNpc;
        boolean ok = mod.getApplicationService()
                .executeTrade(player.getUUID(), npcId, idx, listing, payload.requestId());
        if (ok) {
            player.sendSystemMessage(Component.literal("§aTraded: "
                    + com.storynpcs.domain.role.trader.TradeSummaries.describe(listing)), true);
        } else {
            player.sendSystemMessage(Component.literal("§cTrade failed — sold out, insufficient payment, or faction requirement not met."), true);
        }
        sendTradeOpen(player, npc); // refresh listing availability/uses
    }

    private static void handleBankAction(ServerPlayer player, ServerboundBankActionPayload payload) {
        if (isRoleActionThrottled(player)) return;
        var mod = StoryNpcs.getInstance();
        if (mod == null) return;
        com.storynpcs.domain.common.NamespacedId requestedNpc;
        try {
            requestedNpc = com.storynpcs.domain.common.NamespacedId.of(payload.npcId());
        } catch (Exception e) {
            return;
        }
        if (!mod.getRuntimeSessions(player.getServer()).isRoleSession(
                player.getUUID(), "bank", requestedNpc, payload.sessionId())) {
            return;
        }
        var requestAdmission = mod.getRuntimeSessions(player.getServer())
                .admitRequest(player.getUUID(), payload.requestId());
        // Held-item deposits and whole-stack withdrawals have durable request
        // journals. Paid unlock remains protected by the in-memory window until
        // its inventory-side journal is implemented.
        if (requestAdmission != com.storynpcs.runtime.session.RuntimeSessionRegistry.RequestAdmission.NEW
                && !"deposit_held".equals(payload.action())
                && !"withdraw".equals(payload.action())) {
            return;
        }
        var npc = resolveRoleNpc(player, payload.npcId());
        if (npc == null) return;
        var banker = npc.getBanker();
        if (banker == null) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] That NPC is not a banker."), true);
            return;
        }
        var bankRepo = mod.getBankRepository();
        if (bankRepo == null) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] Banking is unavailable right now."), true);
            return;
        }
        var service = mod.getApplicationService();
        var npcId = requestedNpc;

        switch (payload.action()) {
            case "deposit_held" -> {
                var deposit = service.depositHeldToBank(player.getUUID(), bankRepo,
                        payload.tab(), payload.requestId());
                if (!deposit.accepted()) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Deposit failed — " + deposit.code() + "."), true);
                    break;
                }
                player.sendSystemMessage(Component.literal("§aDeposit " + deposit.outcome().name().toLowerCase()
                        + " in " + banker.getBankName() + " (tab " + (payload.tab() + 1) + ")."), true);
            }
            case "withdraw" -> {
                var withdrawal = service.withdrawAndDeliverFromBank(player.getUUID(), bankRepo,
                        payload.tab(), payload.slot(), payload.requestId());
                if ("REPLAYED".equals(withdrawal.code())) {
                    player.sendSystemMessage(Component.literal("§e[StoryNPCs] Withdrawal already applied; no duplicate item was created."), true);
                    break;
                }
                if (!withdrawal.accepted() || withdrawal.item() == null) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Withdrawal failed — "
                            + withdrawal.code() + "."), true);
                    break;
                }
                player.sendSystemMessage(Component.literal("§aWithdrew " + withdrawal.item().getCount()
                        + "x " + withdrawal.item().getItemId() + "."), true);
            }
            case "unlock_tab" -> {
                boolean ok = service.unlockBankTab(player.getUUID(), bankRepo, banker);
                if (!ok) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Tab unlock failed — max tabs reached or not enough emeralds."), true);
                    break;
                }
                player.sendSystemMessage(Component.literal("§aUnlocked a new vault tab in " + banker.getBankName() + "."), true);
            }
            default -> player.sendSystemMessage(Component.literal("§c[StoryNPCs] Unknown bank action."), true);
        }
        sendBankOpen(player, npc); // refresh vault view
    }

}
