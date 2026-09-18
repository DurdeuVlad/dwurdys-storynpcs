package com.storynpcs.client;

import com.storynpcs.client.gui.DialogueEditorScreen;
import com.storynpcs.client.gui.DialogueScreen;
import com.storynpcs.client.render.StoryNpcRenderer;
import com.storynpcs.entity.StoryNpcRegistry;
import com.storynpcs.network.ClientboundDialogueEditorOpenPayload;
import com.storynpcs.network.ClientboundDialogueOpenPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class StoryNpcsClient {

    private StoryNpcsClient() {}

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(StoryNpcRegistry.STORY_NPC.get(), StoryNpcRenderer::new);
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
                    payload.optionHints()
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
            com.storynpcs.editor.DialogueEditorScreenModel model = new com.storynpcs.editor.DialogueEditorScreenModel(
                    graph,
                    g -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.storynpcs.network.ServerboundDialogueSavePayload(
                                    g.getId() != null ? g.getId().toString() : payload.dialogueId(),
                                    com.storynpcs.domain.dialogue.DialogueGraphSerde.toJson(g))));
            if (graph == null) {
                model.setStatusMessage("Warning: server sent no graph data — opened empty editor.");
            }
            mc.setScreen(new DialogueEditorScreen(model));
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
                editor.getModel().onSaveResult(payload.success(), payload.message());
            }
        });
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