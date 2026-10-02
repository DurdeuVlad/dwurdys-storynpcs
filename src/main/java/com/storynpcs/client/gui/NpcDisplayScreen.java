package com.storynpcs.client.gui;

import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.editor.NpcDisplayScreenModel;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Display sub-screen of {@link NpcEditorScreen} (issue #58 — P3-1 display
 * editor coverage). Exposes the full {@link com.storynpcs.domain.npc.NpcDisplay}
 * contract that the main editor panel does not fit: skin source resolution,
 * cloak/glow overlays, visibility, model identity, scale, tint, name
 * visibility, hitbox and boss-bar state, and the animation stance. All
 * mutation routes through the existing whole-definition save payload →
 * saveNpc, so server-side validation is identical to the command path.
 */
public class NpcDisplayScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int FIELD_W = 160;
    private static final int COLOR_OK = 0xFF4ADE80;
    private static final int COLOR_ERR = 0xFFF87171;

    private final NpcDisplayScreenModel model;
    private long expectedRevision;
    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();

    public NpcDisplayScreen(NpcDefinition npc) {
        this(npc, 0L);
    }

    public NpcDisplayScreen(NpcDefinition npc, long expectedRevision) {
        super(Component.literal("NPC Display"));
        this.model = new NpcDisplayScreenModel(npc);
        this.expectedRevision = Math.max(0L, expectedRevision);
    }

    public NpcDisplayScreenModel getModel() { return model; }

    public void onSaveResult(UUID requestId, boolean success, String message, long revision) {
        if (!saveRequestId.matchesCurrent(requestId)) return;
        model.setStatus(message, !success);
        // The response revision is authoritative on success AND on rejection —
        // never guess with ++.
        expectedRevision = Math.max(0L, revision);
        saveRequestId.acknowledge(requestId);
    }

    @Override
    protected void init() {
        super.init();
        int colL = 16;
        int colM = colL + FIELD_W + 24;
        int colR = colM + FIELD_W + 24;

        // ── Left: skin & overlay ────────────────────────────────────────────
        int y = 26;
        addRenderableWidget(section(colL, y - 10, "§6Appearance"));
        addRenderableWidget(cycleButton(colL, y, "Skin Source",
                NpcDisplayScreenModel.SKIN_SOURCES[model.getSkinSourceIdx()],
                b -> model.cycleSkinSource(1)));
        y += ROW_H; field(colL, y, "Skin Texture", model.getSkinTexture(), model::setSkinTexture);
        y += ROW_H; field(colL, y, "Skin URL", model.getSkinUrl(), model::setSkinUrl);
        y += ROW_H; field(colL, y, "Skin Player", model.getSkinPlayer(), model::setSkinPlayer, 64);
        y += ROW_H; field(colL, y, "Cloak Texture", model.getCloakTexture(), model::setCloakTexture);
        y += ROW_H; field(colL, y, "Glow Texture", model.getGlowTexture(), model::setGlowTexture);
        y += ROW_H; addRenderableWidget(toggleButton(colL, y, "Overlay Glow", model.isOverlayGlowing(),
                b -> model.toggleOverlayGlowing()));
        y += ROW_H; addRenderableWidget(toggleButton(colL, y, "Show Layers", model.isShowLayers(),
                b -> model.toggleShowLayers()));

        // ── Middle: model & geometry ────────────────────────────────────────
        y = 26;
        addRenderableWidget(section(colM, y - 10, "§6Model & Body"));
        addRenderableWidget(cycleButton(colM, y, "Visibility",
                NpcDisplayScreenModel.VISIBILITIES[model.getVisibilityIdx()],
                b -> model.cycleVisibility(1)));
        y += ROW_H; field(colM, y, "Model Type", model.getModelType(), model::setModelType, 64);
        y += ROW_H; field(colM, y, "Model ID", model.getModelId(), model::setModelId, 256);
        y += ROW_H; field(colM, y, "Model Size (1-30)", model.getModelSize(), model::setModelSize, 8);
        y += ROW_H; field(colM, y, "Scale X", model.getScaleX(), model::setScaleX, 10);
        y += ROW_H; field(colM, y, "Scale Y", model.getScaleY(), model::setScaleY, 10);
        y += ROW_H; field(colM, y, "Scale Z", model.getScaleZ(), model::setScaleZ, 10);
        y += ROW_H; field(colM, y, "Tint (hex)", model.getTint(), model::setTint, 7);
        y += ROW_H; addRenderableWidget(toggleButton(colM, y, "Living Animation", model.isLivingAnimation(),
                b -> model.toggleLivingAnimation()));

        // ── Right: name, hitbox, boss bar, stance ───────────────────────────
        y = 26;
        addRenderableWidget(section(colR, y - 10, "§6Identity & Effects"));
        addRenderableWidget(toggleButton(colR, y, "Show Name", model.isShowName(),
                b -> model.toggleShowName()));
        y += ROW_H; addRenderableWidget(cycleButton(colR, y, "Name Mode",
                NpcDisplayScreenModel.NAME_MODES[model.getNameModeIdx()],
                b -> model.cycleNameMode(1)));
        y += ROW_H; addRenderableWidget(cycleButton(colR, y, "Hitbox",
                NpcDisplayScreenModel.HITBOX_MODES[model.getHitboxIdx()],
                b -> model.cycleHitbox(1)));
        y += ROW_H; addRenderableWidget(cycleButton(colR, y, "Stance",
                NpcDisplayScreenModel.ANIMATION_STANCES[model.getStanceIdx()],
                b -> model.cycleStance(1)));
        y += ROW_H; addRenderableWidget(cycleButton(colR, y, "Boss Bar",
                NpcDisplayScreenModel.BOSS_BAR_MODES[model.getBossBarModeIdx()],
                b -> model.cycleBossBarMode(1)));
        y += ROW_H; addRenderableWidget(cycleButton(colR, y, "Boss Bar Color",
                NpcDisplayScreenModel.BOSS_BAR_COLORS[model.getBossBarColorIdx()],
                b -> model.cycleBossBarColor(1)));

        // ── Bottom: actions ─────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            if (model.apply() != null) {
                return; // error status already set by apply()
            }
            sendSave("Saving display definition...");
        }).bounds(this.width - 130, this.height - 22, 56, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            // apply() is atomic — a validation failure commits nothing, so on
            // error we stay and surface it instead of silently losing edits.
            if (model.apply() != null) {
                return;
            }
            minecraft.setScreen(new NpcEditorScreen(model.getNpc(), expectedRevision));
        }).bounds(this.width - 66, this.height - 22, 56, 16).build());
    }

    // ── Widget helpers ──────────────────────────────────────────────────────

    private Button section(int x, int y, String label) {
        Button b = Button.builder(Component.literal(label), btn -> {})
                .bounds(x, y, FIELD_W, 10).build();
        b.active = false;
        return b;
    }

    private void field(int x, int y, String label, String value,
                       java.util.function.Consumer<String> responder) {
        field(x, y, label, value, responder, 512);
    }

    private void field(int x, int y, String label, String value,
                       java.util.function.Consumer<String> responder, int maxLength) {
        EditBox box = new EditBox(this.font, x, y, FIELD_W, 14, Component.literal(label));
        // Match the domain's boundedText bounds so the widget never visually
        // accepts what the model would later truncate or reject.
        box.setMaxLength(maxLength);
        box.setValue(value != null ? value : "");
        box.setHint(Component.literal(label));
        // Live-sync into the model so a cycle/toggle rebuild never loses
        // text the user already typed into another field.
        box.setResponder(responder);
        addRenderableWidget(box);
    }

    private Button cycleButton(int x, int y, String label, String value,
                               Button.OnPress onPress) {
        return Button.builder(Component.literal(label + ": " + value), b -> {
            onPress.onPress(b);
            rebuildWidgets();
        }).bounds(x, y, FIELD_W, 14).build();
    }

    private Button toggleButton(int x, int y, String label, boolean value,
                                Button.OnPress onPress) {
        return Button.builder(
                Component.literal(label + ": " + (value ? "§aON" : "§cOFF")), b -> {
            onPress.onPress(b);
            rebuildWidgets();
        }).bounds(x, y, FIELD_W, 14).build();
    }

    private void sendSave(String pendingMessage) {
        model.setStatus(pendingMessage, false);
        NpcDefinition def = model.getNpc();
        String submittedJson = NpcDefinitionSerde.toJson(def);
        UUID requestId = saveRequestId.forPayload(submittedJson);
        PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                def.getId().toString(), submittedJson, expectedRevision, requestId));
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xE0101014);
        g.renderOutline(0, 0, this.width, this.height, 0xFF3F3F46);
        var npc = model.getNpc();
        g.drawString(this.font, this.font.plainSubstrByWidth(
                "§6Display — §e" + (npc.getId() != null ? npc.getId() : "?"), this.width - 176),
                12, 8, 0xFFFFFFFF);
        if (!model.getStatusMessage().isEmpty()) {
            g.drawString(this.font, this.font.plainSubstrByWidth(
                    model.getStatusMessage(), this.width - 150), 12, this.height - 18,
                    model.isStatusError() ? COLOR_ERR : COLOR_OK);
        }
        super.render(g, mouseX, mouseY, partial);
    }
}
