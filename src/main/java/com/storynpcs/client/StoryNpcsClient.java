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
                    payload.isTerminal()
            );
            mc.setScreen(screen);
        });
    }

    /**
     * VULN-49: Opens the dialogue editor GUI when the server sends ClientboundDialogueEditorOpenPayload.
     * Previously /storynpcs dialogue edit only sent a chat message and the screen was never opened.
     */
    public static void openDialogueEditor(ClientboundDialogueEditorOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            DialogueEditorScreen screen = new DialogueEditorScreen(payload.dialogueId());
            mc.setScreen(screen);
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