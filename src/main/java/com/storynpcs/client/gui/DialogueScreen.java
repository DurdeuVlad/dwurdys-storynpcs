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
    private static final int MIN_TEXT_H = 24;
    private static final int MIN_TEXT_W = 40;
    private static final UUID ZERO_SESSION = new UUID(0L, 0L);

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
                optionHints, sessionId, optionTokens, resolvePortrait(npcName, sessionId));
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
     * The NPC this node belongs to, resolved through progressively weaker
     * identity evidence. The payload's {@code npcName} is a speaker label
     * (per-node override → display name → graph title), so it does not always
     * equal the entity's name — name-matching tiers run first, then a tight
     * interact-range scan, then the previous screen's portrait when the same
     * session continues (a speaker change mid-dialogue must not drop it).
     */
    private static LivingEntity resolvePortrait(String npcName, UUID sessionId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return null;
        }
        // Strongest evidence first: a still-open screen of the same session
        // knows the speaker — a speaker change mid-dialogue must not drop the
        // portrait. Zero UUID marks sessionless (terminal) payloads.
        if (mc.screen instanceof DialogueScreen previous && previous.portrait != null
                && !ZERO_SESSION.equals(sessionId) && previous.sessionId.equals(sessionId)) {
            return previous.portrait;
        }
        boolean nameKnown = npcName != null && !npcName.isBlank();
        if (nameKnown && mc.crosshairPickEntity instanceof LivingEntity living
                && isPortraitable(living) && npcName.equals(living.getName().getString())) {
            return living;
        }
        if (!nameKnown) {
            return null;
        }
        // The payload lands a tick or two after the interact, so the crosshair
        // pick is often empty — scan for the named NPC instead.
        LivingEntity named = nearest(mc, e -> npcName.equals(e.getName().getString()), 64.0);
        if (named != null) {
            return named;
        }
        // Speaker-override nodes can't name-match; the NPC the player could
        // have interacted with is within reach regardless of what it's called.
        return nearest(mc, ignored -> true, 9.0); // 3-block entity interaction reach
    }

    private static LivingEntity nearest(Minecraft mc,
            java.util.function.Predicate<LivingEntity> predicate, double maxDistSq) {
        LivingEntity best = null;
        double bestDist = maxDistSq;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && isPortraitable(living) && predicate.test(living)) {
                double dist = living.distanceToSqr(mc.player);
                if (dist < bestDist) {
                    best = living;
                    bestDist = dist;
                }
            }
        }
        return best;
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

        // Clamp the option stack to the band: a long dialogue keeps digit
        // shortcuts for every option, but only the ones that fit render.
        int count = Math.min(model.getOptionCount(), maxVisibleOptions());
        int optionsTop = optionsTop();
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

    /** Options that fit the band while leaving room for at least a line of text. */
    private int maxVisibleOptions() {
        int usable = contentBottom() - contentTop() - MIN_TEXT_H;
        return Math.max(0, usable / (OPTION_H + OPTION_GAP));
    }

    private int optionsTop() {
        return contentBottom()
                - Math.min(model.getOptionCount(), maxVisibleOptions()) * (OPTION_H + OPTION_GAP);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int textLeft = contentLeft();
        int textWidth = contentWidth();
        // The rail only exists while the entity is present and the band is
        // wide enough to still hold text beside it.
        if (portrait != null && !portrait.isRemoved()
                && contentWidth() >= PORTRAIT_W + MIN_TEXT_W) {
            int railBottom = Math.max(contentTop() + 1, optionsTop() - UiTheme.PAD_S);
            int railRight = contentLeft() + PORTRAIT_W;
            graphics.fill(contentLeft(), contentTop(), railRight, railBottom, UiTheme.FIELD_BG);
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                    contentLeft(), contentTop(), railRight, railBottom,
                    Math.max(10, (railBottom - contentTop()) / 4), 0.0625f,
                    mouseX, mouseY, portrait);
            textLeft = railRight + UiTheme.PAD_M;
            textWidth = Math.max(1, contentRight() - textLeft);
        }
        graphics.drawWordWrap(this.font, Component.literal(model.getText()),
                textLeft, contentTop() + UiTheme.PAD_S, textWidth, UiTheme.TEXT);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // A focused widget owns Enter/Space (footer Close, option buttons);
        // otherwise they fall through to the model's hovered-option path.
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
