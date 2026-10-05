package com.storynpcs.network;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.common.DiagnosticHints;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.MutationRequest;
import net.minecraft.nbt.CompoundTag;
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

        // NBT book (#148): read view for holders; allowlisted edits are
        // session-bound + permission-gated server-side.
        registrar.playToClient(
                ClientboundNbtBookOpenPayload.TYPE,
                ClientboundNbtBookOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openNbtBook(payload));
                }
        );

        // Player-facing panels (issue #150): quest log, faction panel, mail,
        // transport picker. Views are server-built JSON; commits carry the
        // panel session id + idempotent request id.
        registrar.playToClient(
                ClientboundPlayerPanelPayload.TYPE,
                ClientboundPlayerPanelPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> com.storynpcs.client.StoryNpcsClient.openPlayerPanel(payload));
                }
        );

        registrar.playToServer(
                ServerboundNbtBookEditPayload.TYPE,
                ServerboundNbtBookEditPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleNbtBookEdit(serverPlayer, payload));
                    }
                }
        );

        registrar.playToServer(
                ServerboundMailActionPayload.TYPE,
                ServerboundMailActionPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleMailAction(serverPlayer, payload));
                    }
                }
        );

        registrar.playToServer(
                ServerboundToolSessionClosePayload.TYPE,
                ServerboundToolSessionClosePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleToolSessionClose(serverPlayer, payload));
                    }
                }
        );

        registrar.playToServer(
                ServerboundTransportSelectPayload.TYPE,
                ServerboundTransportSelectPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        context.enqueueWork(() -> handleTransportSelect(serverPlayer, payload));
                    }
                }
        );
    }

    /**
     * Opens the NBT book view for the clicked entity; issues the edit session.
     * Throttled like role actions (a full entity serialize + view build per
     * call), and reading a <em>player</em> target requires operator level 2 —
     * their saved tag exposes inventory, ender chest, and recipe state.
     */
    public static void sendNbtBookOpen(ServerPlayer player, net.minecraft.world.entity.Entity target) {
        sendNbtBookOpen(player, target, true);
    }

    private static void sendNbtBookOpen(ServerPlayer player, net.minecraft.world.entity.Entity target,
                                        boolean applyThrottle) {
        var mod = StoryNpcsAccess.mod(player);
        if (mod == null || target == null) {
            return;
        }
        if (applyThrottle && mod.getRuntimeSessions(player.getServer()).throttleToolAction(
                player.getUUID(), System.currentTimeMillis(), 250L)) {
            return;
        }
        if (target instanceof net.minecraft.world.entity.player.Player && !player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] Reading a player's NBT requires operator level 2."));
            return;
        }
        CompoundTag tag = target.saveWithoutId(new CompoundTag());
        var entries = com.storynpcs.domain.support.NbtBookService.buildView(tag);
        boolean canEdit = player.hasPermissions(2);
        var sessionId = mod.getRuntimeSessions(player.getServer()).openEntitySession(
                player.getUUID(), com.storynpcs.item.NbtBookItem.SESSION_KIND, target.getId());
        PacketDistributor.sendToPlayer(player, new ClientboundNbtBookOpenPayload(
                target.getId(), target.getName().getString(),
                com.storynpcs.domain.support.NbtBookService.entriesToJsonBounded(
                        entries, MutationProtocolCodecs.MAX_EDITOR_DOCUMENT_BYTES / 2),
                canEdit, sessionId));
    }

    /** Closes an entity-targeted tool session when its screen is dismissed. */
    private static void handleToolSessionClose(ServerPlayer player, ServerboundToolSessionClosePayload payload) {
        var mod = StoryNpcsAccess.mod(player);
        if (mod == null) {
            return;
        }
        mod.getRuntimeSessions(player.getServer()).closeEntitySession(
                player.getUUID(), payload.kind(), payload.sessionId());
    }

    /**
     * Applies one allowlisted NBT-book edit. Defense-in-depth: permission 2,
     * alive non-spectator sender in interaction range of the target, live
     * entity session bound to this entity, allowlist plan — all must pass,
     * and only typed entity setters are used (never {@code load()}).
     */
    private static void handleNbtBookEdit(ServerPlayer player, ServerboundNbtBookEditPayload payload) {
        var mod = StoryNpcsAccess.mod(player);
        if (mod == null) {
            return;
        }
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] NBT book edits require operator level 2."));
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return;
        }
        if (!mod.getRuntimeSessions(player.getServer()).isEntitySession(player.getUUID(),
                com.storynpcs.item.NbtBookItem.SESSION_KIND, payload.entityId(), payload.sessionId())) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] NBT book session is stale — reopen the book on the target."));
            return;
        }
        net.minecraft.world.entity.Entity target = player.level().getEntity(payload.entityId());
        if (target == null || !target.isAlive()
                || target.distanceToSqr(player) > 64.0 * 64.0) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] Target entity is no longer available or is out of reach."));
            return;
        }
        var edit = com.storynpcs.domain.support.NbtBookService.planEdit(payload.path(), payload.value());
        if (edit.isEmpty()) {
            var result = com.storynpcs.domain.support.NbtBookService.describeEdit(
                    payload.path(), payload.value());
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] " + result.message()));
            return;
        }
        com.storynpcs.domain.support.NbtBookService.applyEdit(target, edit.get());
        player.sendSystemMessage(Component.literal(
                "§a[StoryNPCs] Applied " + payload.path() + " <- " + payload.value().strip()));
        sendNbtBookOpen(player, target, false); // refresh the open view (unthrottled)
    }

    // ---- Player panels (issue #150) ----

    /**
     * Opens (or refreshes) a player panel for {@code player}. The view JSON is
     * built server-side from the player's durable progression + the definition
     * registry; a panel id outside {@link com.storynpcs.service.PlayerPanelViews#PANEL_IDS}
     * is rejected before a session token is minted.
     */
    public static void sendPlayerPanel(ServerPlayer player, String panel) {
        var mod = StoryNpcsAccess.mod(player);
        var service = mod != null ? mod.getApplicationService() : null;
        if (mod == null || service == null
                || !com.storynpcs.service.PlayerPanelViews.PANEL_IDS.contains(panel)) {
            return;
        }
        var sessions = mod.getRuntimeSessions(player.getServer());
        var progression = mod.getProgressionRepository() != null
                ? mod.getProgressionRepository().getOrCreate(player.getUUID()) : null;
        if (progression == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] Player data is unavailable right now."), true);
            return;
        }
        String viewJson = switch (panel) {
            case com.storynpcs.service.PlayerPanelViews.PANEL_QUEST_LOG ->
                    com.storynpcs.domain.role.RoleSerde.toJson(
                            com.storynpcs.service.PlayerPanelViews.questLog(
                                    mod.getRegistry(), progression));
            case com.storynpcs.service.PlayerPanelViews.PANEL_FACTIONS ->
                    com.storynpcs.domain.role.RoleSerde.toJson(
                            com.storynpcs.service.PlayerPanelViews.factionPanel(
                                    mod.getRegistry(), progression));
            case com.storynpcs.service.PlayerPanelViews.PANEL_MAIL ->
                    com.storynpcs.domain.role.RoleSerde.toJson(
                            com.storynpcs.service.PlayerPanelViews.mail(
                                    service.getMailbox(player.getUUID())));
            case com.storynpcs.service.PlayerPanelViews.PANEL_TRANSPORT -> {
                var visible = service.listTransports(player.getUUID());
                var unlocked = new java.util.HashSet<com.storynpcs.domain.common.NamespacedId>();
                for (var l : service.listAvailableTransportLocations(player.getUUID())) {
                    unlocked.add(l.getId());
                }
                yield com.storynpcs.domain.role.RoleSerde.toJson(
                        com.storynpcs.service.PlayerPanelViews.transport(visible, unlocked));
            }
            default -> null;
        };
        if (viewJson == null) {
            return;
        }
        var sessionId = sessions.openPanelSession(player.getUUID(), panel);
        PacketDistributor.sendToPlayer(player,
                new ClientboundPlayerPanelPayload(panel, viewJson, sessionId));
    }

    private static void handleMailAction(ServerPlayer player, ServerboundMailActionPayload payload) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (isRoleActionThrottled(player)) return;
        var mod = StoryNpcsAccess.mod(player);
        var service = mod != null ? mod.getApplicationService() : null;
        if (mod == null || service == null) return;
        var sessions = mod.getRuntimeSessions(player.getServer());
        if (!sessions.isPanelSession(player.getUUID(),
                com.storynpcs.service.PlayerPanelViews.PANEL_MAIL, payload.sessionId())) {
            return; // stale session — fail closed
        }
        // Duplicate request ids still reach the canonical service so a lost
        // response is classified as REPLAYED rather than silently dropped.
        sessions.admitRequest(player.getUUID(), payload.requestId());
        var request = new com.storynpcs.service.PlayerProgressionActionRequest(
                switch (payload.action()) {
                    case ServerboundMailActionPayload.ACTION_MARK_READ -> "mail.read";
                    case ServerboundMailActionPayload.ACTION_DELETE -> "mail.delete";
                    case ServerboundMailActionPayload.ACTION_SEND -> "mail.send";
                    default -> "mail.unknown";
                },
                "player", player.getUUID(), player.getUUID(), payload.requestId(),
                player.hasPermissions(2) ? 2 : 0);
        com.storynpcs.service.AuthorizedActionResult result;
        switch (payload.action()) {
            case ServerboundMailActionPayload.ACTION_MARK_READ -> {
                java.util.UUID mailId;
                try {
                    mailId = java.util.UUID.fromString(payload.target());
                } catch (IllegalArgumentException e) {
                    return;
                }
                result = service.markMailRead(request, mailId);
            }
            case ServerboundMailActionPayload.ACTION_DELETE -> {
                java.util.UUID mailId;
                try {
                    mailId = java.util.UUID.fromString(payload.target());
                } catch (IllegalArgumentException e) {
                    return;
                }
                result = service.deleteMail(request, mailId);
            }
            case ServerboundMailActionPayload.ACTION_SEND -> {
                // Resolve the recipient server-side — the client only names a
                // player; unknown names fail before any mailbox write. The
                // rejection is generic so it does not echo the queried name.
                var profile = player.getServer().getProfileCache() != null
                        ? player.getServer().getProfileCache().get(payload.target())
                        : java.util.Optional.<com.mojang.authlib.GameProfile>empty();
                java.util.UUID recipient = profile.map(p -> p.getId()).orElse(null);
                if (recipient == null) {
                    player.sendSystemMessage(Component.literal(
                            "§c[StoryNPCs] Mail could not be delivered."), true);
                    return;
                }
                result = service.sendMail(request, recipient,
                        player.getGameProfile().getName(), payload.subject(), payload.body());
            }
            default -> {
                return;
            }
        }
        if (result != null && result.applied()) {
            if (ServerboundMailActionPayload.ACTION_SEND.equals(payload.action())) {
                // The sender's own mailbox is unchanged — confirm in chat
                // instead of force-reopening a panel they may have closed.
                player.sendSystemMessage(Component.literal(
                        "§a[StoryNPCs] Mail sent."), true);
            } else {
                sendPlayerPanel(player, com.storynpcs.service.PlayerPanelViews.PANEL_MAIL);
            }
        } else if (result != null && result.decision() != null && !result.decision().allowed()) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] Mail action rejected: " + result.decision().code()), true);
        }
    }

    private static void handleTransportSelect(ServerPlayer player,
                                              ServerboundTransportSelectPayload payload) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (isRoleActionThrottled(player)) return;
        var mod = StoryNpcsAccess.mod(player);
        var service = mod != null ? mod.getApplicationService() : null;
        if (mod == null || service == null) return;
        var sessions = mod.getRuntimeSessions(player.getServer());
        if (!sessions.isPanelSession(player.getUUID(),
                com.storynpcs.service.PlayerPanelViews.PANEL_TRANSPORT, payload.sessionId())) {
            return; // stale session — fail closed
        }
        // Duplicates reach the canonical service so a lost transport response
        // is classified as REPLAYED from the durable journal.
        sessions.admitRequest(player.getUUID(), payload.requestId());
        com.storynpcs.domain.common.NamespacedId locationId;
        try {
            locationId = com.storynpcs.domain.common.NamespacedId.of(payload.locationId());
        } catch (Exception e) {
            return;
        }
        var request = new com.storynpcs.service.PlayerProgressionActionRequest(
                "transport.request", "player", player.getUUID(), player.getUUID(),
                payload.requestId(), player.hasPermissions(2) ? 2 : 0);
        var result = service.requestTransport(request, locationId);
        if (result == null) {
            return;
        }
        if (result.approved()) {
            player.sendSystemMessage(Component.literal(
                    "§a[StoryNPCs] Transporting to " + result.destinationName()
                            + (result.feeCharged() > 0 ? " (fee " + result.feeCharged() + ")" : "")), true);
        } else {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] Transport denied: " + result.detail()), true);
        }
    }

    private static void handleFactionSave(ServerPlayer player, ServerboundFactionSavePayload payload) {
        if (!player.hasPermissions(2)) {
            sendFactionSaveResult(player, false, "Insufficient permissions — faction editing requires operator level 2.",
                    payload.requestId(), "PERMISSION_DENIED", payload.expectedRevision());
            return;
        }
        var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
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
        var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
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

    private static void sendFactionSaveResult(ServerPlayer player, boolean success, String message,
                                               java.util.UUID requestId, String code, long revision) {
        var registry = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getRegistry() : null;
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
        var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
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
        var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
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

    private static void sendQuestSaveResult(ServerPlayer player, boolean success, String message,
                                            java.util.UUID requestId, String code, long revision) {
        var registry = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getRegistry() : null;
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
        var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
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
        var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
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
        // #123: a requested name randomization is generated server-side —
        // clients never supply the generated text. Unknown cultures reject
        // the whole save rather than silently saving without a name.
        String generatedName = null;
        if (!payload.randomizeNameCulture().isBlank()) {
            var nameService = StoryNpcsAccess.mod(player)
                    .getNameGenerationService(player.getServer());
            long seed = player.level().getGameTime();
            // "*" lets the server pick a culture — deterministic under the
            // same seed and catalog.
            if ("*".equals(payload.randomizeNameCulture().trim())) {
                var cultures = nameService != null
                        ? nameService.cultures().stream().sorted(
                                java.util.Comparator.comparing(
                                        com.storynpcs.domain.common.NamespacedId::asString)).toList()
                        : java.util.List.<com.storynpcs.domain.common.NamespacedId>of();
                if (cultures.isEmpty()) {
                    sendNpcSaveResult(player, false,
                            "No name dictionaries loaded — save rejected.",
                            payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
                    return;
                }
                var picked = cultures.get((int) Math.floorMod(seed, cultures.size()));
                var suggested = nameService.suggest(picked, seed);
                if (suggested.isEmpty()) {
                    sendNpcSaveResult(player, false,
                            "Name generation produced no name — save rejected.",
                            payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
                    return;
                }
                generatedName = suggested.get();
            } else {
                com.storynpcs.domain.common.NamespacedId cultureId;
                try {
                    cultureId = com.storynpcs.domain.common.NamespacedId.of(payload.randomizeNameCulture());
                } catch (Exception e) {
                    sendNpcSaveResult(player, false,
                            "Malformed name culture '" + payload.randomizeNameCulture() + "' — save rejected.",
                            payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
                    return;
                }
                var suggested = nameService != null
                        ? nameService.suggest(cultureId, seed)
                        : java.util.Optional.<String>empty();
                if (suggested.isEmpty()) {
                    sendNpcSaveResult(player, false,
                            "Unknown or empty name culture '" + payload.randomizeNameCulture() + "' — save rejected.",
                            payload.requestId(), "INVALID_PAYLOAD", payload.expectedRevision());
                    return;
                }
                generatedName = suggested.get();
            }
            if (def.getDisplay() == null) {
                def.setDisplay(new com.storynpcs.domain.npc.NpcDisplay());
            }
            def.getDisplay().setName(generatedName);
        }
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
                    : "NPC '" + id + "' saved to disk and updated in world."
                            + (generatedName != null ? " Random name applied: '" + generatedName + "'." : "");
            sendNpcSaveResult(player, true, message,
                    payload.requestId(), mutation.duplicate() ? "DUPLICATE" : "APPLIED", mutation.revision());
        }
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
        var mod = StoryNpcsAccess.mod(player);
        return mod != null && mod.getRuntimeSessions(player.getServer()).throttleRoleAction(
                player.getUUID(), now, ROLE_ACTION_MIN_INTERVAL_MILLIS);
    }

    private static void handleDialogueChoose(ServerPlayer player, ServerboundDialogueChoosePayload payload) {
        // Prevent dead or spectator player exploiting dialogue (VULN-12)
        if (!player.isAlive() || player.isSpectator()) {
            var service = StoryNpcsAccess.mod(player) != null ? StoryNpcsAccess.mod(player).getApplicationService() : null;
            if (service != null) service.closeDialogue(player.getUUID());
            sendCloseDialogue(player);
            return;
        }

        // Rate-limit incoming choice packets to prevent main-thread disk I/O DoS (VULN-13)
        var mod = StoryNpcsAccess.mod(player);
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
            service = mod.getApplicationService();
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

                // Choice is authorized by the opaque single-use token (P5-2), never by index
                DialogueView view = service.chooseDialogueOption(player.getUUID(), payload.choiceToken());
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

    public static void clearPlayer(net.minecraft.server.MinecraftServer server, java.util.UUID playerUuid) {
        var mod = StoryNpcsAccess.mod(server);
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
                        StoryNpcsAccess.require(player).getApplicationService().getActiveSession(player.getUUID())
                                .map(com.storynpcs.domain.dialogue.DialogueSession::getSessionId)
                                .orElse(new java.util.UUID(0L, 0L)),
                        view.optionTokens() != null ? view.optionTokens() : java.util.List.of()
                )
        );
    }

    public static void sendCloseDialogue(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ClientboundDialogueClosePayload.INSTANCE);
    }

    // ---- Trader / banker interaction ----

    /** Opens the trade screen for an NPC whose definition has a trader role. */
    public static void sendTradeOpen(ServerPlayer player, com.storynpcs.domain.npc.NpcDefinition npc) {
        var mod = StoryNpcsAccess.mod(player);
        if (mod == null || npc.getTrader() == null) return;
        var trader = com.storynpcs.domain.role.RoleSerde.copyTrader(npc.getTrader());
        var tradeState = mod.getTradeStateRepository();
        var server = player.getServer();
        if (tradeState != null) {
            for (var listing : trader.getListings()) {
                try {
                    // P7-1: the screen shows post-restock availability — a due
                    // boundary resets durable uses before they are read. The
                    // listing's own interval wins; the role default applies
                    // when the listing does not set one.
                    if (server != null) {
                        tradeState.restockIfDue(npc.getId().toString(), listing.getListingId(),
                                trader.effectiveRestockInterval(listing),
                                server.overworld().getGameTime());
                    }
                    listing.setUses(tradeState.getUsesOrMigrateLegacy(
                            npc.getId().toString(), listing.getListingId(),
                            listing.legacyListingIdForMigration().orElse(null)));
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
        var mod = StoryNpcsAccess.mod(player);
        if (mod == null || npc.getBanker() == null || mod.getBankRepository() == null) return;
        // The vault owner resolves from the authored banker role — never from a
        // client field — so a member can only reach a shared vault they are
        // authorized for, and cannot select an arbitrary vault owner.
        java.util.UUID vaultOwner = resolveBankVaultOwner(npc, player.getUUID());
        var vault = mod.getBankRepository().getOrCreate(vaultOwner);
        if (!vaultOwner.equals(player.getUUID())
                && !vault.canAccess(player.getUUID())
                && !player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal(
                    "§c[StoryNPCs] You are not a member of this shared vault."), true);
            return;
        }
        var sessionId = mod.getRuntimeSessions(player.getServer()).openRoleSession(
                player.getUUID(), "bank", npc.getId());
        PacketDistributor.sendToPlayer(player, new ClientboundBankOpenPayload(
                npc.getId().toString(),
                com.storynpcs.domain.role.RoleSerde.toJson(npc.getBanker()),
                com.storynpcs.domain.role.RoleSerde.toJson(vault), sessionId));
    }

    /**
     * Resolves the durable vault identity served by a banker NPC: the authored
     * {@code vaultOwnerUuid} when present, otherwise the interacting player.
     */
    private static java.util.UUID resolveBankVaultOwner(
            com.storynpcs.domain.npc.NpcDefinition npc, java.util.UUID playerUuid) {
        var banker = npc.getBanker();
        if (banker == null) return playerUuid;
        return banker.authoredVaultOwner().orElse(playerUuid);
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
        var mod = StoryNpcsAccess.mod(player);
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
        var mod = StoryNpcsAccess.mod(player);
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
        try {
            var listing = listings.get(idx);
            var npcId = requestedNpc;
            boolean ok = mod.getApplicationService()
                    .executeTrade(new com.storynpcs.service.TradeExecutionRequest(
                            "player", player.getUUID(), player.getUUID(), npcId, idx,
                            payload.requestId(), -1), listing)
                    .applied();
            if (ok) {
                player.sendSystemMessage(Component.literal("§aTraded: "
                        + com.storynpcs.domain.role.trader.TradeSummaries.describe(listing)), true);
            } else {
                player.sendSystemMessage(Component.literal(
                        "§cTrade failed — sold out, insufficient payment, full inventory, or faction requirement not met."), true);
            }
            sendTradeOpen(player, npc); // refresh listing availability/uses
        } catch (RuntimeException failure) {
            // A malformed or stale packet must fail closed here — an uncaught
            // throw escapes enqueueWork onto the server thread.
            System.err.println("Error processing trade for player " + player.getName().getString()
                    + ": " + failure.getMessage());
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] Trade request could not be processed."), true);
        }
    }

    private static void handleBankAction(ServerPlayer player, ServerboundBankActionPayload payload) {
        if (isRoleActionThrottled(player)) return;
        var mod = StoryNpcsAccess.mod(player);
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
        // journals and classify replays themselves. Unlock uses the in-memory
        // window for same-session dedupe; its durable journal still classifies
        // any replay that reaches the service (e.g. after a relog).
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
        // Bound client-controlled indices before constructing canonical
        // requests: BankOperationRequest's contract throws on out-of-range
        // values, and an uncaught throw here escapes enqueueWork onto the
        // server thread (VULN-class: malformed packet crashes the server).
        int tab = payload.tab();
        int slot = payload.slot();
        if (!com.storynpcs.service.BankOperationRequest.isValidTab(tab)
                || !com.storynpcs.service.BankOperationRequest.isValidSlot(slot)) {
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] Malformed bank action — rejected."), true);
            return;
        }
        var service = mod.getApplicationService();
        var npcId = requestedNpc;
        // Authored shared-vault target — resolved server-side from the banker
        // definition; the packet never carries a vault owner.
        java.util.UUID vaultOwner = resolveBankVaultOwner(npc, player.getUUID());

        try {
            switch (payload.action()) {
            case "deposit_held" -> {
                var deposit = service.depositToBank(new com.storynpcs.service.BankOperationRequest(
                        "player", player.getUUID(), player.getUUID(), npcId,
                        com.storynpcs.service.BankOperationRequest.Action.DEPOSIT_HELD,
                        payload.tab(), -1, null, 0, payload.requestId(), -1, vaultOwner), bankRepo, null);
                if (!deposit.accepted()) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Deposit failed — " + deposit.code() + "."), true);
                    break;
                }
                player.sendSystemMessage(Component.literal("§aDeposit " + deposit.outcome().name().toLowerCase()
                        + " in " + banker.getBankName() + " (tab " + (payload.tab() + 1) + ")."), true);
            }
            case "withdraw" -> {
                var withdrawal = service.withdrawFromBank(new com.storynpcs.service.BankOperationRequest(
                        "player", player.getUUID(), player.getUUID(), npcId,
                        com.storynpcs.service.BankOperationRequest.Action.WITHDRAW_STACK,
                        payload.tab(), payload.slot(), null, 0, payload.requestId(), -1, vaultOwner), bankRepo);
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
                boolean ok = service.unlockBankTab(new com.storynpcs.service.BankOperationRequest(
                        "player", player.getUUID(), player.getUUID(), npcId,
                        com.storynpcs.service.BankOperationRequest.Action.UNLOCK_TAB,
                        payload.tab(), -1, null, 0, payload.requestId(), -1, vaultOwner), bankRepo, banker)
                        .applied();
                if (!ok) {
                    player.sendSystemMessage(Component.literal("§c[StoryNPCs] Tab unlock failed — max tabs reached or not enough emeralds."), true);
                    break;
                }
                player.sendSystemMessage(Component.literal("§aUnlocked a new vault tab in " + banker.getBankName() + "."), true);
            }
            default -> player.sendSystemMessage(Component.literal("§c[StoryNPCs] Unknown bank action."), true);
            }
            sendBankOpen(player, npc); // refresh vault view
        } catch (RuntimeException failure) {
            // Same fail-closed boundary as the trade handler: a malformed or
            // stale packet must never escape onto the server thread.
            System.err.println("Error processing bank action for player " + player.getName().getString()
                    + ": " + failure.getMessage());
            player.sendSystemMessage(Component.literal("§c[StoryNPCs] Bank action could not be processed."), true);
        }
    }

}
