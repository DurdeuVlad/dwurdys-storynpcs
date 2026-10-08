package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.editor.NpcDisplayScreenModel;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
 *
 * <p>Migrated onto the shared chrome (#206): the three dense columns become
 * tabbed sections so the form fits the bounded panel even on small windows —
 * fields sync into the model live, so switching tabs never loses input.
 */
public class NpcDisplayScreen extends UiScreen {

    private static final String[] TABS = {"Appearance", "Model & Body", "Identity & Parts"};
    private static final int ROW_PITCH = UiTheme.BUTTON_H + 4;

    private final NpcDisplayScreenModel model;
    private int tab = 0;
    private long expectedRevision;
    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();

    public NpcDisplayScreen(NpcDefinition npc) {
        this(npc, 0L);
    }

    public NpcDisplayScreen(NpcDefinition npc, long expectedRevision) {
        super(Component.literal("Display — " + (npc.getId() != null ? npc.getId().toString() : "?")));
        this.model = new NpcDisplayScreenModel(npc);
        this.expectedRevision = Math.max(0L, expectedRevision);
    }

    public NpcDisplayScreenModel getModel() { return model; }

    @Override
    public boolean isPauseScreen() { return true; }

    public void onSaveResult(UUID requestId, boolean success, String message, long revision) {
        if (!saveRequestId.matchesCurrent(requestId)) return;
        model.setStatus(message, !success);
        syncStatus();
        // The response revision is authoritative on success AND on rejection —
        // never guess with ++.
        expectedRevision = Math.max(0L, revision);
        saveRequestId.acknowledge(requestId);
    }

    private String lastEchoed;

    @Override
    protected void onStatusExpired() {
        lastEchoed = null; // identical later results may echo again
    }

    private void syncStatus() {
        String msg = model.getStatusMessage();
        // Rebuilds re-run initContent — don't replay an identical echo.
        if (!msg.isEmpty() && !msg.equals(lastEchoed)) {
            lastEchoed = msg;
            echo(Component.literal(msg)
                    .withColor(model.isStatusError() ? UiTheme.DANGER : UiTheme.TEXT), 4000);
        }
    }

    @Override
    protected void initContent() {
        // Section tabs — model state is live-synced, so switching is lossless.
        int tabW = Math.max(50, (contentWidth() - UiTheme.PAD_S * (TABS.length - 1)) / TABS.length);
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            Button tabBtn = Button.builder(Component.literal(TABS[i]), b -> {
                tab = t;
                rebuildWidgets();
            }).bounds(contentLeft() + i * (tabW + UiTheme.PAD_S), contentTop(), tabW,
                    UiTheme.BUTTON_H).build();
            tabBtn.active = tab != i;
            addRenderableWidget(tabBtn);
        }

        // Two columns per tab keep the dense-editor feel inside the bounded band.
        int colW = Math.max(60, (contentWidth() - UiTheme.PAD_L) / 2);
        int y = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;
        int rightX = contentLeft() + colW + UiTheme.PAD_L;

        switch (tab) {
            case 0 -> { initAppearance(contentLeft(), y, colW, rightX); }
            case 1 -> { initModel(contentLeft(), y, colW, rightX); }
            case 2 -> { initIdentity(contentLeft(), y, colW, rightX); }
        }

        addFooterAction(Component.literal("Save"), b -> {
            if (model.apply() != null) {
                syncStatus();
                return; // error status already set by apply()
            }
            sendSave("Saving display definition...");
            syncStatus();
        });
        addFooterAction(Component.literal("Back"), b -> {
            // apply() is atomic — a validation failure commits nothing, so on
            // error we stay and surface it instead of silently losing edits.
            if (model.apply() != null) {
                syncStatus();
                return;
            }
            minecraft.setScreen(new NpcEditorScreen(model.getNpc(), expectedRevision));
        });
        syncStatus();
    }

    private void initAppearance(int x, int y, int w, int rightX) {
        int ly = y;
        addRenderableWidget(cycleButton(x, ly, w, "Skin Source",
                NpcDisplayScreenModel.SKIN_SOURCES[model.getSkinSourceIdx()],
                b -> model.cycleSkinSource(1)));
        ly += ROW_PITCH; field(x, ly, w, "Skin Texture", model.getSkinTexture(), model::setSkinTexture);
        ly += ROW_PITCH; field(x, ly, w, "Skin URL", model.getSkinUrl(), model::setSkinUrl);
        ly += ROW_PITCH; field(x, ly, w, "Skin Player", model.getSkinPlayer(), model::setSkinPlayer, 64);

        int ry = y;
        field(rightX, ry, w, "Cloak Texture", model.getCloakTexture(), model::setCloakTexture);
        ry += ROW_PITCH; field(rightX, ry, w, "Glow Texture", model.getGlowTexture(), model::setGlowTexture);
        ry += ROW_PITCH; addRenderableWidget(toggleButton(rightX, ry, w, "Overlay Glow", model.isOverlayGlowing(),
                b -> model.toggleOverlayGlowing()));
        ry += ROW_PITCH; addRenderableWidget(toggleButton(rightX, ry, w, "Show Layers", model.isShowLayers(),
                b -> model.toggleShowLayers()));
    }

    private void initModel(int x, int y, int w, int rightX) {
        int ly = y;
        addRenderableWidget(cycleButton(x, ly, w, "Visibility",
                NpcDisplayScreenModel.VISIBILITIES[model.getVisibilityIdx()],
                b -> model.cycleVisibility(1)));
        ly += ROW_PITCH; field(x, ly, w, "Model Type", model.getModelType(), model::setModelType, 256);
        ly += ROW_PITCH; field(x, ly, w, "Model ID", model.getModelId(), model::setModelId, 256);
        ly += ROW_PITCH; field(x, ly, w, "Model Size (1-30)", model.getModelSize(), model::setModelSize, 8);
        ly += ROW_PITCH; field(x, ly, w, "Tint (hex)", model.getTint(), model::setTint, 7);

        int ry = y;
        field(rightX, ry, w, "Scale X", model.getScaleX(), model::setScaleX, 10);
        ry += ROW_PITCH; field(rightX, ry, w, "Scale Y", model.getScaleY(), model::setScaleY, 10);
        ry += ROW_PITCH; field(rightX, ry, w, "Scale Z", model.getScaleZ(), model::setScaleZ, 10);
        ry += ROW_PITCH; addRenderableWidget(toggleButton(rightX, ry, w, "Living Animation", model.isLivingAnimation(),
                b -> model.toggleLivingAnimation()));
        ry += ROW_PITCH; addRenderableWidget(cycleButton(rightX, ry, w, "Variant",
                NpcDisplayScreenModel.VARIANTS[model.getVariantIdx()],
                b -> model.cycleVariant(1)));
    }

    private void initIdentity(int x, int y, int w, int rightX) {
        int ly = y;
        addRenderableWidget(toggleButton(x, ly, w, "Show Name", model.isShowName(),
                b -> model.toggleShowName()));
        ly += ROW_PITCH; addRenderableWidget(cycleButton(x, ly, w, "Name Mode",
                NpcDisplayScreenModel.NAME_MODES[model.getNameModeIdx()],
                b -> model.cycleNameMode(1)));
        ly += ROW_PITCH; addRenderableWidget(cycleButton(x, ly, w, "Hitbox",
                NpcDisplayScreenModel.HITBOX_MODES[model.getHitboxIdx()],
                b -> model.cycleHitbox(1)));
        ly += ROW_PITCH; addRenderableWidget(cycleButton(x, ly, w, "Stance",
                NpcDisplayScreenModel.ANIMATION_STANCES[model.getStanceIdx()],
                b -> model.cycleStance(1)));
        ly += ROW_PITCH; addRenderableWidget(cycleButton(x, ly, w, "Boss Bar",
                NpcDisplayScreenModel.BOSS_BAR_MODES[model.getBossBarModeIdx()],
                b -> model.cycleBossBarMode(1)));
        ly += ROW_PITCH; addRenderableWidget(cycleButton(x, ly, w, "Boss Bar Color",
                NpcDisplayScreenModel.BOSS_BAR_COLORS[model.getBossBarColorIdx()],
                b -> model.cycleBossBarColor(1)));

        int ry = y;
        addRenderableWidget(cycleButton(rightX, ry, w, "Part",
                NpcDisplayScreenModel.BODY_PARTS[model.getBodyPartIdx()]
                        + (model.isPartEnabled() ? " *" : ""),
                b -> model.cycleBodyPart(1)));
        ry += ROW_PITCH; addRenderableWidget(toggleButton(rightX, ry, w, "Part Enabled", model.isPartEnabled(),
                b -> model.setPartEnabled(!model.isPartEnabled())));
        ry += ROW_PITCH; field(rightX, ry, w, "Part Type", model.getPartType(), model::setPartType, 3);
        ry += ROW_PITCH; field(rightX, ry, w, "Part Color (hex)", model.getPartColor(), model::setPartColor, 7);
        ry += ROW_PITCH; addRenderableWidget(cycleButton(rightX, ry, w, "Part Anim",
                NpcDisplayScreenModel.PART_BEHAVIORS[model.getPartBehaviorIdx()],
                b -> model.cyclePartBehavior(1)));
    }

    // ── Widget helpers ──────────────────────────────────────────────────────

    private void field(int x, int y, int w, String label, String value,
                       java.util.function.Consumer<String> responder) {
        field(x, y, w, label, value, responder, 512);
    }

    private void field(int x, int y, int w, String label, String value,
                       java.util.function.Consumer<String> responder, int maxLength) {
        EditBox box = new EditBox(this.font, x, y, w, UiTheme.BUTTON_H, Component.literal(label));
        // Match the domain's boundedText bounds so the widget never visually
        // accepts what the model would later truncate or reject.
        box.setMaxLength(maxLength);
        box.setValue(value != null ? value : "");
        box.setHint(Component.literal(label));
        // Live-sync into the model so a cycle/toggle/tab rebuild never loses
        // text the user already typed into another field.
        box.setResponder(responder);
        addRenderableWidget(box);
    }

    private Button cycleButton(int x, int y, int w, String label, String value,
                               Button.OnPress onPress) {
        return Button.builder(Component.literal(label + ": " + value), b -> {
            onPress.onPress(b);
            rebuildWidgets();
        }).bounds(x, y, w, UiTheme.BUTTON_H).build();
    }

    private Button toggleButton(int x, int y, int w, String label, boolean value,
                                Button.OnPress onPress) {
        return Button.builder(
                Component.literal(label + ": " + (value ? "§aON" : "§cOFF")), b -> {
            onPress.onPress(b);
            rebuildWidgets();
        }).bounds(x, y, w, UiTheme.BUTTON_H).build();
    }

    private void sendSave(String pendingMessage) {
        NpcDefinition def = model.getNpc();
        if (def.getId() == null) {
            model.setStatus("Cannot save — NPC has no id.", true);
            return;
        }
        model.setStatus(pendingMessage, false);
        String submittedJson = NpcDefinitionSerde.toJson(def);
        UUID requestId = saveRequestId.forPayload(submittedJson);
        PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                def.getId().toString(), submittedJson, expectedRevision, requestId));
    }
}
