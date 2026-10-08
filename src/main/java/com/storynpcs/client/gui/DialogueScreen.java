package com.storynpcs.client.gui;

import com.storynpcs.client.model.DialogueScreenModel;
import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.entity.FakeLivingEntity;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.network.ServerboundDialogueChoosePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/**
 * Player dialogue screen for an NPC conversation node. Migrated onto the
 * shared {@link UiScreen} chrome (issue #205): the speaker name rides the
 * panel header, the node text word-wraps inside the content band, options are
 * kit buttons bottom-anchored above the footer, and a lit entity portrait —
 * the NPC under the crosshair at open time, carried across node transitions —
 * replaces the unlit silhouette bleed-through reported in audit D3.
 *
 * <p>Digit shortcuts 1–9, consequence hint tags and terminal-node Close are
 * preserved; Esc closes via {@link DialogueScreenModel#handleKeyPress}.
 */
public class DialogueScreen extends UiScreen {

    private static final int OPTION_H = 20;
    private static final int OPTION_GAP = 4;
    private static final int PORTRAIT_W = 84;

    private final DialogueScreenModel model;
    private final UUID sessionId;
    private final LivingEntity portrait;
    private boolean soundPlayed = false;

    public DialogueScreen(DialogueScreenModel model, UUID sessionId, LivingEntity portrait) {
        super(Component.literal(
                model.getNpcName().isEmpty() ? "Dialogue" : model.getNpcName()));
        this.model = model;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
        this.portrait = portrait;
    }

    public static DialogueScreen create(
            String dialogueId,
            String nodeId,
            String text,
            String sound,
            List<String> options,
            boolean isTerminal,
            String npcName,
            List<String> optionHints,
            UUID sessionId,
            List<String> optionTokens
    ) {
        return create(dialogueId, nodeId, text, sound, options, isTerminal, npcName,
                optionHints, sessionId, optionTokens, resolvePortrait(npcName));
    }

    public static DialogueScreen create(
            String dialogueId, String nodeId, String text, String sound,
            List<String> options, boolean isTerminal, String npcName,
            List<String> optionHints, UUID sessionId, List<String> optionTokens,
            LivingEntity portrait
    ) {
        final List<String> tokens = optionTokens != null ? optionTokens : List.of();
        DialogueScreenModel model = new DialogueScreenModel(
                dialogueId,
                nodeId,
                text,
                sound,
                options,
                isTerminal,
                // The server accepts only the opaque single-use token issued for
                // this option (P5-2) — never a raw index.
                index -> PacketDistributor.sendToServer(new ServerboundDialogueChoosePayload(
                        sessionId, UUID.randomUUID(),
                        index >= 0 && index < tokens.size() ? tokens.get(index) : "")),
                npcName,
                optionHints,
                optionTokens
        );
        return new DialogueScreen(model, sessionId, portrait);
    }

    /**
     * The NPC the player is looking at when the node opens, name-checked
     * against the payload so an unrelated entity never becomes the portrait.
     * Node transitions happen while the cursor is over the panel (not the
     * NPC), so a still-open dialogue screen donates its resolved portrait.
     */
    private static LivingEntity resolvePortrait(String npcName) {
        Minecraft mc = Minecraft.getInstance();
        if (npcName == null || npcName.isBlank() || mc.level == null || mc.player == null) {
            return null;
        }
        // Primary: the entity under the crosshair — the NPC the player just
        // right-clicked. The payload lands a tick or two after the interact,
        // so the pick can already be empty; fall back to the nearest matching
        // NPC within generous interact range.
        LivingEntity crosshair = mc.crosshairPickEntity instanceof LivingEntity living
                && isPortraitable(living) && npcName.equals(living.getName().getString())
                ? living : null;
        if (crosshair != null) {
            return crosshair;
        }
        LivingEntity nearest = null;
        double nearestDist = 64.0; // 8-block leash — beyond interact range
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && isPortraitable(living)
                    && npcName.equals(living.getName().getString())) {
                double dist = living.distanceToSqr(mc.player);
                if (dist < nearestDist) {
                    nearest = living;
                    nearestDist = dist;
                }
            }
        }
        if (nearest != null) {
            return nearest;
        }
        if (mc.screen instanceof DialogueScreen previous
                && previous.portrait != null
                && previous.model.getNpcName().equals(npcName)) {
            return previous.portrait;
        }
        return null;
    }

    private static boolean isPortraitable(LivingEntity entity) {
        return entity instanceof StoryNpcEntity || entity instanceof FakeLivingEntity;
    }

    public DialogueScreenModel getModel() {
        return model;
    }

    @Override
    protected void initContent() {
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

        int count = model.getOptionCount();
        int optionsTop = contentBottom() - count * (OPTION_H + OPTION_GAP);
        for (int i = 0; i < count; i++) {
            final int optionIndex = i;
            String hint = model.getOptionHint(i);
            // Consequence hints render dimmed so choices are informed without visual noise
            Component label = hint.isEmpty()
                    ? Component.literal(String.format("%d. %s", i + 1, model.getOptions().get(i)))
                    : Component.literal(String.format("%d. %s", i + 1, model.getOptions().get(i)))
                            .append(Component.literal(" §8[" + hint + "]"));

            addRenderableWidget(Button.builder(label, b -> model.chooseOption(optionIndex))
                    .bounds(contentLeft(), optionsTop + i * (OPTION_H + OPTION_GAP),
                            contentWidth(), OPTION_H)
                    .build());
        }

        // Esc always closes; the footer keeps the affordance visible on every
        // node (terminal nodes included) rather than only at the end.
        addFooterAction(Component.literal(model.isTerminal() ? "Close (Esc)" : "Close"),
                b -> { model.close(); onClose(); });
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int textLeft = contentLeft();
        int textWidth = contentWidth();
        if (portrait != null) {
            int railRight = contentLeft() + PORTRAIT_W;
            graphics.fill(contentLeft(), contentTop(), railRight,
                    contentBottom() - model.getOptionCount() * (OPTION_H + OPTION_GAP) - UiTheme.PAD_S,
                    UiTheme.FIELD_BG);
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                    contentLeft(), contentTop(), railRight,
                    Math.max(contentTop() + 1,
                            contentBottom() - model.getOptionCount() * (OPTION_H + OPTION_GAP) - UiTheme.PAD_S),
                    Math.max(10, (contentBottom() - contentTop()) / 4), 0.0625f,
                    mouseX, mouseY, portrait);
            textLeft = railRight + UiTheme.PAD_M;
            textWidth = contentRight() - textLeft;
        }
        graphics.drawWordWrap(this.font, Component.literal(model.getText()),
                textLeft, contentTop() + UiTheme.PAD_S, textWidth, UiTheme.TEXT);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // A focused widget owns Enter/Space (footer Close, option buttons);
        // otherwise they choose the hovered option via the model.
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) && getFocused() != null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
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
