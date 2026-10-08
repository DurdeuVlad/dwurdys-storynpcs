package com.storynpcs.client;

import com.storynpcs.client.gui.DialogueEditorScreen;
import com.storynpcs.client.gui.DialogueScreen;
import com.storynpcs.client.gui.NpcEditorScreen;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.client.render.StoryNpcRenderer;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.entity.StoryNpcRegistry;
import com.storynpcs.network.ClientboundDialogueEditorOpenPayload;
import com.storynpcs.network.ClientboundDialogueOpenPayload;
import com.storynpcs.network.ClientboundNpcEditorOpenPayload;
import com.storynpcs.network.ClientboundNpcSaveResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class StoryNpcsClient {

    private StoryNpcsClient() {}

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(StoryNpcRegistry.STORY_NPC.get(), StoryNpcRenderer::new);
        event.registerEntityRenderer(StoryNpcRegistry.NPC_PROJECTILE.get(),
                com.storynpcs.client.render.NpcProjectileRenderer::new);
        event.registerEntityRenderer(StoryNpcRegistry.NPC_CHAIR_MOUNT.get(),
                net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(StoryNpcRegistry.NPC_FAKE_LIVING.get(),
                com.storynpcs.client.render.FakeLivingRenderer::new);
    }

    /** Opens the NBT book viewer/editor when the server sends the open payload (#148). */
    public static void openNbtBook(com.storynpcs.network.ClientboundNbtBookOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            // A post-edit refresh must update the open screen, not replace it —
            // replacing would drop scroll position and the in-flight edit row.
            if (mc.screen instanceof com.storynpcs.client.gui.NbtBookScreen open
                    && open.matches(payload.entityId(), payload.sessionId())) {
                open.updateEntries(payload.entriesJson());
                return;
            }
            mc.setScreen(new com.storynpcs.client.gui.NbtBookScreen(
                    payload.entityId(), payload.displayName(), payload.entriesJson(),
                    payload.canEdit(), payload.sessionId()));
        });
    }

    public static void openDialogue(ClientboundDialogueOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            DialogueScreen screen = DialogueScreen.create(
                    payload.dialogueId(),
                    payload.nodeId(),
                    payload.text(),
                    payload.sound(),
                    payload.options(),
                    payload.isTerminal(),
                    payload.npcName(),
                    payload.optionHints(),
                    payload.sessionId(),
                    payload.optionTokens()
            );
            mc.setScreen(screen);
        });
    }

    /**
     * VULN-49: Opens the dialogue editor GUI when the server sends ClientboundDialogueEditorOpenPayload.
     * The payload carries the full graph as JSON — the client has no registry on dedicated servers.
     * Save sends the exported graph back to the server for validation + atomic YAML persistence.
     */
    public static void openDialogueEditor(ClientboundDialogueEditorOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            var graph = com.storynpcs.domain.dialogue.DialogueGraphSerde.fromJson(payload.graphJson()).orElse(null);
            long[] expectedRevision = {payload.revision()};
            PayloadBoundRequestId requestIds = new PayloadBoundRequestId();
            com.storynpcs.editor.DialogueEditorScreenModel model = new com.storynpcs.editor.DialogueEditorScreenModel(
                    graph,
                    g -> {
                        String submittedJson = com.storynpcs.domain.dialogue.DialogueGraphSerde.toJson(g);
                        java.util.UUID requestId = requestIds.forPayload(submittedJson);
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.storynpcs.network.ServerboundDialogueSavePayload(
                                        g.getId() != null ? g.getId().toString() : payload.dialogueId(),
                                        submittedJson, expectedRevision[0], requestId));
                    });
            if (graph == null) {
                model.setStatusMessage("Warning: server sent no graph data — opened empty editor.");
            }
            mc.setScreen(new DialogueEditorScreen(model, expectedRevision, requestIds));
        });
    }

    /**
     * Applies the server's save verdict to the open editor (status bar) so the UI
     * reflects what actually happened instead of a local "saved" assumption.
     */
    public static void handleDialogueSaveResult(com.storynpcs.network.ClientboundDialogueSaveResultPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof DialogueEditorScreen editor) {
                editor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        payload.revision());
            }
        });
    }

    public static void openNpcEditor(ClientboundNpcEditorOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            var defOpt = NpcDefinitionSerde.fromJson(payload.npcJson());
            if (defOpt.isPresent()) {
                mc.setScreen(new NpcEditorScreen(defOpt.get(), payload.revision()));
            } else {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to parse NPC data for editor."));
                }
            }
        });
    }

    public static void handleNpcSaveResult(ClientboundNpcSaveResultPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof NpcEditorScreen editor) {
                editor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        payload.revision());
            } else if (mc.screen instanceof com.storynpcs.client.gui.NpcRulesScreen rulesEditor) {
                rulesEditor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        payload.revision());
            } else if (mc.screen instanceof com.storynpcs.client.gui.NpcDisplayScreen displayEditor) {
                displayEditor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        payload.revision());
            } else if (mc.screen instanceof com.storynpcs.client.gui.TraderBankerAdminScreen traderEditor) {
                traderEditor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        payload.revision());
            }
        });
    }

    public static void openQuestEditor(com.storynpcs.network.ClientboundQuestEditorOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            java.util.List<com.storynpcs.domain.quest.Quest> quests =
                    com.storynpcs.domain.quest.QuestSerde.fromJsonList(payload.questsJson());
            mc.setScreen(new com.storynpcs.client.gui.QuestEditorScreen(quests, payload.questId(),
                    payload.revision(), com.storynpcs.editor.EditorRevisions.parse(payload.revisionsJson())));
        });
    }

    public static void handleQuestSaveResult(com.storynpcs.network.ClientboundQuestSaveResultPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof com.storynpcs.client.gui.QuestEditorScreen editor) {
                editor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        com.storynpcs.domain.quest.QuestSerde.fromJsonList(payload.questsJson()),
                        payload.revision());
            }
        });
    }

    public static void openFactionEditor(com.storynpcs.network.ClientboundFactionEditorOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            java.util.List<com.storynpcs.domain.faction.Faction> factions =
                    com.storynpcs.domain.faction.FactionSerde.fromJsonList(payload.factionsJson());
            mc.setScreen(new com.storynpcs.client.gui.FactionEditorScreen(factions, payload.factionId(),
                    payload.revision(), com.storynpcs.editor.EditorRevisions.parse(payload.revisionsJson())));
        });
    }

    public static void handleFactionSaveResult(com.storynpcs.network.ClientboundFactionSaveResultPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof com.storynpcs.client.gui.FactionEditorScreen editor) {
                editor.onSaveResult(payload.requestId(), payload.success(), payload.message(),
                        com.storynpcs.domain.faction.FactionSerde.fromJsonList(payload.factionsJson()),
                        payload.revision());
            }
        });
    }

    /** Opens the trade screen for a trader-role NPC (server pushes role + faction scores as JSON). */
    public static void openTrade(com.storynpcs.network.ClientboundTradeOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            var trader = com.storynpcs.domain.role.RoleSerde.traderFromJson(payload.traderJson()).orElse(null);
            var scores = com.storynpcs.domain.role.RoleSerde.scoresFromJson(payload.factionScoresJson());
            if (trader == null) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to parse trader data."));
                }
                return;
            }
            // Refresh contract (#201/#204): a re-issued trade view for the
            // open screen updates it in place — scroll and selection survive
            // a buy, and the pending action is acknowledged.
            if (mc.screen instanceof com.storynpcs.client.gui.NpcTradeScreen open
                    && open.matches(payload.sessionId())) {
                open.updateView(trader, scores, payload.requestId());
                return;
            }
            mc.setScreen(new com.storynpcs.client.gui.NpcTradeScreen(
                    payload.npcId(), payload.npcName(), trader, scores, payload.sessionId()));
        });
    }

    /**
     * Opens a player-facing panel (issue #150). The payload's {@code panel}
     * discriminator selects the screen; the view JSON deserializes into the
     * matching {@code PlayerPanels} record. Unknown panels fail closed.
     */
    public static void openPlayerPanel(
            com.storynpcs.network.ClientboundPlayerPanelPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            // Refresh contract (issue #201): a re-issued mail view for an
            // already-open screen updates it in place — scroll, selection,
            // and compose state survive; pending actions are acknowledged.
            if (com.storynpcs.service.PlayerPanelViews.PANEL_MAIL.equals(payload.panel())
                    && mc.screen instanceof com.storynpcs.client.gui.player.PlayerMailScreen open
                    && open.matches(payload.sessionId())) {
                com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                com.storynpcs.domain.panel.PlayerPanels.MailView.class)
                        .ifPresent(v -> open.updateView(v, payload.requestId()));
                return;
            }
            net.minecraft.client.gui.screens.Screen screen = switch (payload.panel()) {
                case com.storynpcs.service.PlayerPanelViews.PANEL_QUEST_LOG ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.QuestLogView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerQuestLogScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_FACTIONS ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.FactionPanelView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerFactionPanelScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_MAIL ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.MailView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerMailScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_TRANSPORT ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.TransportView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerTransportScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_COMPANIONS ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.CompanionView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerCompanionScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_FOLLOWER_HIRE ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.HireView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerFollowerHireScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_ACHIEVEMENTS ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.AchievementView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerAchievementsScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                case com.storynpcs.service.PlayerPanelViews.PANEL_CARPENTRY ->
                        com.storynpcs.domain.role.RoleSerde.fromJson(payload.viewJson(),
                                        com.storynpcs.domain.panel.PlayerPanels.CarpentryView.class)
                                .<net.minecraft.client.gui.screens.Screen>map(v ->
                                        new com.storynpcs.client.gui.player.PlayerCarpentryScreen(
                                                v, payload.sessionId()))
                                .orElse(null);
                default -> null;
            };
            if (screen == null) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "§c[StoryNPCs] Panel data could not be read."));
                }
                return;
            }
            mc.setScreen(screen);
        });
    }

    /**
     * Opens an authored custom-GUI layout (issue #150). The layout JSON
     * deserializes through the authored schema; a malformed layout fails
     * closed with a chat notice rather than rendering a partial tree.
     */
    public static void openCustomGui(
            com.storynpcs.network.ClientboundCustomGuiOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            var layout = com.storynpcs.domain.role.RoleSerde.fromJson(payload.layoutJson(),
                            com.storynpcs.creator.gui.CustomGuiLayout.class)
                    .orElse(null);
            if (layout == null || layout.getRoot() == null) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "§c[StoryNPCs] Custom GUI layout could not be read."));
                }
                return;
            }
            mc.setScreen(new com.storynpcs.client.gui.player.CustomGuiScreen(
                    layout, payload.sessionId()));
        });
    }

    /** Opens the bank screen for a banker-role NPC (server pushes role + the player's vault as JSON). */
    public static void openBank(com.storynpcs.network.ClientboundBankOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            var banker = com.storynpcs.domain.role.RoleSerde.bankerFromJson(payload.bankerJson()).orElse(null);
            var vault = com.storynpcs.domain.role.RoleSerde.vaultFromJson(payload.vaultJson()).orElse(null);
            if (banker == null || vault == null) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("§c[StoryNPCs] Failed to parse bank data."));
                }
                return;
            }
            if (mc.screen instanceof com.storynpcs.client.gui.NpcBankScreen open
                    && open.matches(payload.sessionId())) {
                open.updateView(banker, vault, payload.requestId());
                return;
            }
            mc.setScreen(new com.storynpcs.client.gui.NpcBankScreen(
                    payload.npcId(), banker, vault, payload.sessionId()));
        });
    }

    /** Opens the authoring hub — a stateless navigation shell (P10-1). */
    public static void openHub() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new com.storynpcs.client.gui.AuthoringHubScreen()));
    }

    public static void closeDialogue() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof DialogueScreen) {
                mc.setScreen(null);
            }
        });
    }
}
