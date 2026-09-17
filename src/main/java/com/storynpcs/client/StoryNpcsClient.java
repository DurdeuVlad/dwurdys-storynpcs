package com.storynpcs.client;

import com.storynpcs.client.gui.DialogueScreen;
import com.storynpcs.network.ClientboundDialogueOpenPayload;
import net.minecraft.client.Minecraft;

public final class StoryNpcsClient {

    private StoryNpcsClient() {}

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

    public static void closeDialogue() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof DialogueScreen) {
                mc.setScreen(null);
            }
        });
    }
}