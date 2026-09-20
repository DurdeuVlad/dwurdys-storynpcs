package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.DiagnosticHints;
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
            sendFactionSaveResult(player, false, "Insufficient permissions — faction editing requires operator level 2.");
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendFactionSaveResult(player, false, "StoryNPCs service is not available on this server.");
            return;
        }
        var factionOpt = com.storynpcs.domain.faction.FactionSerde.fromJson(payload.factionJson());
        if (factionOpt.isEmpty() || factionOpt.get().getId() == null) {
            sendFactionSaveResult(player, false, "Malformed faction data — save rejected.");
            return;
        }
        var result = service.saveFaction(factionOpt.get());
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
            sendFactionSaveResult(player, false, "Save rejected: " + first + (result.getErrors().size() > 1
                    ? " (+" + (result.getErrors().size() - 1) + " more)" : ""));
        } else {
            sendFactionSaveResult(player, true, "Faction '" + factionOpt.get().getId() + "' saved to disk and updated.");
        }
    }

    private static void handleFactionDelete(ServerPlayer player, ServerboundFactionDeletePayload payload) {
        if (!player.hasPermissions(2)) {
            sendFactionSaveResult(player, false, "Insufficient permissions — faction deletion requires operator level 2.");
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendFactionSaveResult(player, false, "StoryNPCs service is not available on this server.");
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.factionId());
        } catch (Exception e) {
            sendFactionSaveResult(player, false, "Malformed faction id: '" + payload.factionId() + "'");
            return;
        }
        var referencing = service.findNpcsReferencingFaction(id);
        boolean deleted = service.deleteFaction(id);
        if (deleted) {
            String msg = "Faction '" + id + "' deleted from registry and disk."
                    + (referencing.isEmpty() ? "" : " Warning: NPC(s) " + referencing + " were bound to it — reassign with /storynpcs npc set faction.");
            sendFactionSaveResult(player, true, msg);
        } else {
            sendFactionSaveResult(player, false, "Faction not found: " + id);
        }
    }

    private static void sendFactionSaveResult(ServerPlayer player, boolean success, String message) {
        var registry = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getRegistry() : null;
        String factionsJson = registry != null
                ? com.storynpcs.domain.faction.FactionSerde.toJsonList(List.copyOf(registry.getAllFactions()))
                : "[]";
        PacketDistributor.sendToPlayer(player, new ClientboundFactionSaveResultPayload(success, message, factionsJson));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
    }

    private static void handleQuestSave(ServerPlayer player, ServerboundQuestSavePayload payload) {
        if (!player.hasPermissions(2)) {
            sendQuestSaveResult(player, false, "Insufficient permissions — quest editing requires operator level 2.");
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendQuestSaveResult(player, false, "StoryNPCs service is not available on this server.");
            return;
        }
        var questOpt = com.storynpcs.domain.quest.QuestSerde.fromJson(payload.questJson());
        if (questOpt.isEmpty() || questOpt.get().getId() == null) {
            sendQuestSaveResult(player, false, "Malformed quest data — save rejected.");
            return;
        }
        var result = service.saveQuest(questOpt.get());
        if (result.hasErrors()) {
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
            sendQuestSaveResult(player, false, "Save rejected: " + first + (result.getErrors().size() > 1
                    ? " (+" + (result.getErrors().size() - 1) + " more)" : ""));
        } else {
            sendQuestSaveResult(player, true, "Quest '" + questOpt.get().getId() + "' saved to disk and updated.");
        }
    }

    private static void handleQuestDelete(ServerPlayer player, ServerboundQuestDeletePayload payload) {
        if (!player.hasPermissions(2)) {
            sendQuestSaveResult(player, false, "Insufficient permissions — quest deletion requires operator level 2.");
            return;
        }
        var service = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getApplicationService() : null;
        if (service == null) {
            sendQuestSaveResult(player, false, "StoryNPCs service is not available on this server.");
            return;
        }
        com.storynpcs.domain.common.NamespacedId id;
        try {
            id = com.storynpcs.domain.common.NamespacedId.of(payload.questId());
        } catch (Exception e) {
            sendQuestSaveResult(player, false, "Malformed quest id: '" + payload.questId() + "'");
            return;
        }
        var referencing = service.findDialoguesStartingQuest(id);
        boolean deleted = service.deleteQuest(id);
        if (deleted) {
            String msg = "Quest '" + id + "' deleted from registry and disk."
                    + (referencing.isEmpty() ? "" : " Warning: dialogue(s) " + referencing + " still reference it via START_QUEST.");
            sendQuestSaveResult(player, true, msg);
        } else {
            sendQuestSaveResult(player, false, "Quest not found: " + id);
        }
    }

    private static void sendQuestSaveResult(ServerPlayer player, boolean success, String message) {
        var registry = StoryNpcs.getInstance() != null ? StoryNpcs.getInstance().getRegistry() : null;
        String questsJson = registry != null
                ? com.storynpcs.domain.quest.QuestSerde.toJsonList(List.copyOf(registry.getAllQuests()))
                : "[]";
        PacketDistributor.sendToPlayer(player, new ClientboundQuestSaveResultPayload(success, message, questsJson));
        player.sendSystemMessage(Component.literal((success ? "§a[StoryNPCs] " : "§c[StoryNPCs] ") + message));
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
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
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
            String first = result.getErrors().isEmpty() ? "validation failed"
                    : result.getErrors().get(0).toString() + " — " + DiagnosticHints.hintFor(result.getErrors().get(0));
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

    // ---- Trader / banker interaction ----

    /** Opens the trade screen for an NPC whose definition has a trader role. */
    public static void sendTradeOpen(ServerPlayer player, com.storynpcs.domain.npc.NpcDefinition npc) {
        var mod = StoryNpcs.getInstance();
        if (mod == null || npc.getTrader() == null) return;
        var trader = npc.getTrader();
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
                com.storynpcs.domain.role.RoleSerde.toJson(scores)));
    }

    /** Opens the bank screen for an NPC whose definition has a banker role. */
    public static void sendBankOpen(ServerPlayer player, com.storynpcs.domain.npc.NpcDefinition npc) {
        var mod = StoryNpcs.getInstance();
        if (mod == null || npc.getBanker() == null || mod.getBankRepository() == null) return;
        var vault = mod.getBankRepository().getOrCreate(player.getUUID());
        PacketDistributor.sendToPlayer(player, new ClientboundBankOpenPayload(
                npc.getId().toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(npc.getBanker()),
                com.storynpcs.domain.role.RoleSerde.toJson(vault)));
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
        var npcId = com.storynpcs.domain.common.NamespacedId.of(payload.npcId());
        boolean ok = StoryNpcs.getInstance().getApplicationService()
                .executeTrade(player.getUUID(), npcId, listing);
        if (ok) {
            player.sendSystemMessage(Component.literal("§aTraded: "
                    + com.storynpcs.domain.role.trader.TradeSummaries.describe(listing)), true);
        } else {
            player.sendSystemMessage(Component.literal("§cTrade failed — sold out, insufficient payment, or faction requirement not met."), true);
        }
        sendTradeOpen(player, npc); // refresh listing availability/uses
    }

    private static void handleBankAction(ServerPlayer player, ServerboundBankActionPayload payload) {
        var npc = resolveRoleNpc(player, payload.npcId());
        if (npc == null) return;
        var banker = npc.getBanker();
        if (banker == null) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] That NPC is not a banker."), true);
            return;
        }
        var mod = StoryNpcs.getInstance();
        var bankRepo = mod.getBankRepository();
        if (bankRepo == null) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] Banking is unavailable right now."), true);
            return;
        }
        var service = mod.getApplicationService();
        var npcId = com.storynpcs.domain.common.NamespacedId.of(payload.npcId());

        switch (payload.action()) {
            case "deposit_held" -> {
                var held = player.getMainHandItem();
                if (held.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Hold the item you want to deposit in your main hand."), true);
                    break;
                }
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                String tag = held.getComponentsPatch().isEmpty() ? null
                        : held.save(player.level().registryAccess()).toString();
                int slot = service.depositToBankAuto(player.getUUID(), bankRepo,
                        payload.tab(), itemId, held.getCount(), tag);
                if (slot < 0) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Deposit failed — tab locked or full."), true);
                    break;
                }
                held.setCount(0);
                player.sendSystemMessage(Component.literal("§aDeposited " + itemId + " into " + banker.getBankName() + " (tab " + (payload.tab() + 1) + ")."), true);
            }
            case "withdraw" -> {
                var vault = bankRepo.getOrCreate(player.getUUID());
                int count = vault.getTabItems(payload.tab()).stream()
                        .filter(i -> i.getSlot() == payload.slot()).mapToInt(i -> i.getCount()).findFirst().orElse(0);
                var itemOpt = service.withdrawFromBank(player.getUUID(), bankRepo,
                        payload.tab(), payload.slot(), count);
                if (itemOpt.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Withdrawal failed — slot empty or tab locked."), true);
                    break;
                }
                giveVaultItem(player, itemOpt.get());
                player.sendSystemMessage(Component.literal("§aWithdrew " + itemOpt.get().getCount() + "x " + itemOpt.get().getItemId() + "."), true);
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

    /** Materializes a withdrawn vault item into the player's inventory (drops on overflow). */
    private static void giveVaultItem(ServerPlayer player, com.storynpcs.domain.role.banker.BankVault.VaultItem item) {
        net.minecraft.world.item.ItemStack stack = null;
        if (item.getTag() != null && !item.getTag().isBlank()) {
            try {
                var parsed = net.minecraft.nbt.TagParser.parseTag(item.getTag());
                stack = net.minecraft.world.item.ItemStack.parse(
                        player.level().registryAccess(), parsed).orElse(null);
                if (stack != null) {
                    stack.setCount(item.getCount()); // stored SNBT carries the deposit-time count
                }
            } catch (Exception ignored) {}
        }
        if (stack == null) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(item.getItemId());
            var itemOpt = rl != null
                    ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl)
                    : java.util.Optional.<net.minecraft.world.item.Item>empty();
            stack = itemOpt.map(i -> new net.minecraft.world.item.ItemStack(i, item.getCount()))
                    .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        }
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
