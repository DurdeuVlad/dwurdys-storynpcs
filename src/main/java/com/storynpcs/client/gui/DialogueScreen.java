package com.storynpcs.client.gui;

import com.storynpcs.client.model.DialogueScreenModel;
import com.storynpcs.network.ServerboundDialogueChoosePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class DialogueScreen extends Screen {

    private final DialogueScreenModel model;
    private boolean soundPlayed = false;

    public DialogueScreen(DialogueScreenModel model) {
        super(Component.literal(
                model.getNpcName().isEmpty() ? "StoryNPCs Dialogue" : model.getNpcName()));
        this.model = model;
    }

    public static DialogueScreen create(
            String dialogueId,
            String nodeId,
            String text,
            String sound,
            List<String> options,
            boolean isTerminal,
            String npcName,
            List<String> optionHints
    ) {
        DialogueScreenModel model = new DialogueScreenModel(
                dialogueId,
                nodeId,
                text,
                sound,
                options,
                isTerminal,
                index -> PacketDistributor.sendToServer(new ServerboundDialogueChoosePayload(index)),
                npcName,
                optionHints
        );
        return new DialogueScreen(model);
    }

    public DialogueScreenModel getModel() {
        return model;
    }

    @Override
    protected void init() {
        super.init();

        // Play the node's configured voice line — the field previously arrived but was never used.
        // init() also fires on window resize, so guard against replaying the same line.
        String soundId = model.getSound();
        if (!soundPlayed && soundId != null && !soundId.isBlank()) {
            soundPlayed = true;
            ResourceLocation rl = ResourceLocation.tryParse(soundId);
            if (rl != null) {
                Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                        rl, SoundSource.VOICE, 1.0F, 1.0F, RandomSource.create(), false, 0,
                        SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
            }
        }

        int panelWidth = Math.min(this.width - 40, 360);
        int panelHeight = Math.min(this.height - 40, 220);
        int startX = (this.width - panelWidth) / 2;
        int startY = (this.height - panelHeight) / 2;

        int optionsStartY = startY + panelHeight - (model.getOptionCount() * 24 + 10);

        for (int i = 0; i < model.getOptions().size(); i++) {
            final int optionIndex = i;
            String hint = model.getOptionHint(i);
            // Consequence hints render dimmed so choices are informed without visual noise
            Component label = hint.isEmpty()
                    ? Component.literal(String.format("%d. %s", i + 1, model.getOptions().get(i)))
                    : Component.literal(String.format("%d. %s", i + 1, model.getOptions().get(i)))
                            .append(Component.literal(" §8[" + hint + "]"));

            Button btn = Button.builder(label, b -> {
                model.chooseOption(optionIndex);
            })
            .bounds(startX + 10, optionsStartY + (i * 24), panelWidth - 20, 20)
            .build();

            this.addRenderableWidget(btn);
        }

        if (model.isTerminal()) {
            Button closeBtn = Button.builder(Component.literal("Close (Esc)"), b -> {
                model.close();
                this.onClose();
            })
            .bounds(startX + 10, startY + panelHeight - 25, panelWidth - 20, 20)
            .build();

            this.addRenderableWidget(closeBtn);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render dark backdrop
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int panelWidth = Math.min(this.width - 40, 360);
        int panelHeight = Math.min(this.height - 40, 220);
        int startX = (this.width - panelWidth) / 2;
        int startY = (this.height - panelHeight) / 2;

        // Draw dialogue panel background and border
        graphics.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0xDD1A1A1A);
        graphics.renderOutline(startX, startY, panelWidth, panelHeight, 0xFF4A4A4A);

        // Speaker header — the player should always know who is talking
        int textY = startY + 15;
        if (!model.getNpcName().isEmpty()) {
            graphics.drawString(this.font, Component.literal(model.getNpcName()),
                    startX + 15, startY + 12, 0xFFFACC15, false);
            graphics.fill(startX + 15, startY + 23, startX + panelWidth - 15, startY + 24, 0xFF4A4A4A);
            textY = startY + 30;
        }

        // Draw dialogue body text with word wrap
        graphics.drawWordWrap(this.font, Component.literal(model.getText()), startX + 15, textY, panelWidth - 30, 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (model.handleKeyPress(keyCode)) {
            if (model.isClosed()) {
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
