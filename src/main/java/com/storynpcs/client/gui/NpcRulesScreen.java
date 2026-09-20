package com.storynpcs.client.gui;

import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.rule.BehaviorRule;
import com.storynpcs.editor.NpcRulesScreenModel;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules sub-screen of {@link NpcEditorScreen} (issue #26) — per-NPC behavior
 * rule authoring mirroring /storynpcs npc rule add|list|remove. Mutations go
 * through the existing whole-definition save payload → saveNpc, so server-side
 * validation is identical to the command path.
 */
public class NpcRulesScreen extends Screen {

    private static final int ROW_H = 12;
    private static final int COLOR_OK = 0xFF4ADE80;
    private static final int COLOR_ERR = 0xFFF87171;
    private static final int COLOR_LABEL = 0xFFA1A1AA;

    private final NpcRulesScreenModel model;
    private final List<Button> ruleRowButtons = new ArrayList<>();
    private int listScroll;
    private int armedRemove = -1; // two-click remove confirm (row index)
    private EditBox filterField;

    // Add-form arg fields (created per picker state)
    private EditBox condFactionField;
    private EditBox condThresholdField;
    private EditBox actTextField;
    private EditBox actAmountField;
    private EditBox actRadiusField;
    private EditBox actMessageField;
    private EditBox actFractionField;
    private EditBox actDialogueField;
    private EditBox actFactionField;
    private EditBox actDeltaField;

    public NpcRulesScreen(NpcDefinition npc) {
        super(Component.literal("NPC Rules"));
        this.model = new NpcRulesScreenModel(npc);
    }

    public NpcRulesScreenModel getModel() { return model; }

    public void onSaveResult(boolean success, String message) {
        model.setStatus(message, !success);
    }

    @Override
    protected void init() {
        super.init();
        if (model.isAddMode()) {
            initAddWidgets();
        } else {
            initListWidgets();
        }
    }

    // ── List mode ───────────────────────────────────────────────────────────

    private void initListWidgets() {
        int bottom = this.height - 26;
        int top = 22;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);
        var visible = model.filteredRuleIndices();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, visible.size() - maxRows)));

        // Search/filter (issue #21) — typing refreshes the visible rows live.
        // The EditBox is recreated on every rebuildWidgets(), so the responder is
        // attached LAST: setValue/moveCursorToEnd fire onValueChange and would
        // re-enter the responder mid-init otherwise (infinite recursion). Typing
        // refreshes only the row buttons — a full rebuild would drop focus and
        // swallow subsequent keystrokes.
        boolean hadFocus = filterField != null
                && (filterField.isFocused() || this.getFocused() == filterField);
        filterField = new EditBox(this.font, this.width - 168, 4, 106, 14, Component.literal("Filter"));
        filterField.setHint(Component.literal("filter…"));
        filterField.setMaxLength(48);
        filterField.setValue(model.getListFilter());
        if (hadFocus || !model.getListFilter().isEmpty()) {
            filterField.setFocused(true);
            this.setFocused(filterField);
            filterField.moveCursorToEnd(false);
        }
        filterField.setResponder(v -> {
            model.setListFilter(v);
            listScroll = 0;
            armedRemove = -1;
            refreshRuleRowButtons();
        });
        addRenderableWidget(filterField);

        refreshRuleRowButtons();

        addRenderableWidget(Button.builder(Component.literal("§a+ Add Rule"), b -> {
            model.beginAdd();
            armedRemove = -1;
            rebuildWidgets();
        }).bounds(12, this.height - 22, 90, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b ->
                minecraft.setScreen(new NpcEditorScreen(model.getNpc())))
                .bounds(this.width - 56, 4, 46, 14).build());
    }

    private void refreshRuleRowButtons() {
        for (Button b : ruleRowButtons) {
            removeWidget(b);
        }
        ruleRowButtons.clear();
        var visible = model.filteredRuleIndices();
        int maxRows = Math.max(1, (this.height - 26 - 22) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, visible.size() - maxRows)));
        for (int i = 0; i < maxRows && i + listScroll < visible.size(); i++) {
            int idx = visible.get(i + listScroll);
            int ry = 22 + i * ROW_H;
            boolean armed = armedRemove == idx;
            Button btn = Button.builder(
                    Component.literal(armed ? "§cSure?" : "§c✕"), b -> {
                        if (armedRemove != idx) {
                            armedRemove = idx;
                            rebuildWidgets();
                        } else {
                            armedRemove = -1;
                            String err = model.removeRule(idx);
                            if (err == null) {
                                sendSave("Removing rule...");
                            }
                            rebuildWidgets();
                        }
                    }).bounds(this.width - 34, ry, 30, 11).build();
            ruleRowButtons.add(btn);
            addRenderableWidget(btn);
        }
    }

    // ── Add mode ────────────────────────────────────────────────────────────

    private void initAddWidgets() {
        int y = 22;

        addRenderableWidget(cycleBtn("Trigger: " + NpcRulesScreenModel.TRIGGERS[model.getTriggerIdx()],
                12, y, () -> model.cycleTrigger(1)));
        y += 18;

        addRenderableWidget(cycleBtn("If: " + NpcRulesScreenModel.CONDITIONS[model.getCondIdx()],
                12, y, () -> { model.cycleCondition(1); rebuildWidgets(); }));

        int argX = 150;
        if (model.condNeedsFaction()) {
            condFactionField = argField(argX, y);
            condFactionField.setHint(Component.literal("faction id"));
            condFactionField.setResponder(model::setCondFaction);
        }
        if (model.condNeedsStanding()) {
            addRenderableWidget(cycleBtn(NpcRulesScreenModel.STANDINGS[model.getStandingIdx()],
                    argX + 96, y, 70, () -> model.cycleStanding(1)));
        }
        if (model.condNeedsThreshold()) {
            addRenderableWidget(cycleBtn(NpcRulesScreenModel.OPERATORS[model.getCondOpIdx()],
                    argX, y, 26, () -> model.cycleCondOp(1)));
            condThresholdField = argField(argX + 30, y);
            condThresholdField.setHint(Component.literal("value"));
            condThresholdField.setResponder(model::setCondThreshold);
        }
        y += 18;

        addRenderableWidget(cycleBtn("Do: " + NpcRulesScreenModel.ACTIONS[model.getActionIdx()],
                12, y, () -> { model.cycleAction(1); rebuildWidgets(); }));

        if (model.actNeedsText()) {
            actTextField = argField(argX, y);
            actTextField.setHint(Component.literal("message text"));
            actTextField.setResponder(model::setActText);
        }
        if (model.actNeedsAmount()) {
            actAmountField = argField(argX, y);
            actAmountField.setHint(Component.literal("amount (def 100)"));
            actAmountField.setResponder(model::setActAmount);
        }
        if (model.actNeedsRadiusMessage()) {
            actRadiusField = argField(argX, y);
            actRadiusField.setHint(Component.literal("radius"));
            actRadiusField.setResponder(model::setActRadius);
            actMessageField = argField(argX + 74, y);
            actMessageField.setHint(Component.literal("alert message"));
            actMessageField.setResponder(model::setActMessage);
        }
        if (model.actNeedsFractionDialogue()) {
            actFractionField = argField(argX, y);
            actFractionField.setHint(Component.literal("heal frac (0.5)"));
            actFractionField.setResponder(model::setActFraction);
            actDialogueField = argField(argX + 74, y);
            actDialogueField.setHint(Component.literal("yield line"));
            actDialogueField.setResponder(model::setActDialogue);
        }
        if (model.actNeedsStance()) {
            addRenderableWidget(cycleBtn(NpcRulesScreenModel.STANCES[model.getStanceIdx()],
                    argX, y, 90, () -> model.cycleStance(1)));
        }
        if (model.actNeedsFactionDelta()) {
            actFactionField = argField(argX, y);
            actFactionField.setHint(Component.literal("faction id"));
            actFactionField.setResponder(model::setActFaction);
            actDeltaField = argField(argX + 74, y);
            actDeltaField.setHint(Component.literal("delta"));
            actDeltaField.setResponder(model::setActDelta);
        }

        int footer = this.height - 22;
        addRenderableWidget(Button.builder(Component.literal("§aAdd & Save"), b -> {
            String err = model.commitAdd();
            if (err == null) {
                sendSave("Adding rule...");
            }
            rebuildWidgets();
        }).bounds(12, footer, 80, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            model.cancelAdd();
            rebuildWidgets();
        }).bounds(98, footer, 50, 16).build());
    }

    private Button cycleBtn(String label, int x, int y, Runnable onClick) {
        return cycleBtn(label, x, y, 132, onClick);
    }

    private Button cycleBtn(String label, int x, int y, int w, Runnable onClick) {
        return Button.builder(Component.literal(label), b -> onClick.run())
                .bounds(x, y, w, 15).build();
    }

    private EditBox argField(int x, int y) {
        EditBox box = new EditBox(this.font, x, y, 70, 14, Component.literal("arg"));
        box.setMaxLength(96);
        addRenderableWidget(box);
        return box;
    }

    // ── Save plumbing (same payload as the NPC editor — saveNpc path) ───────

    private void sendSave(String pendingMessage) {
        model.setStatus(pendingMessage, false);
        NpcDefinition def = model.getNpc();
        PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                def.getId().toString(), NpcDefinitionSerde.toJson(def)));
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xE0101014);
        g.renderOutline(0, 0, this.width, this.height, 0xFF3F3F46);

        if (model.isAddMode()) {
            renderAdd(g);
        } else {
            renderList(g, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        var npc = model.getNpc();
        g.drawString(this.font, this.font.plainSubstrByWidth(
                "§6Rules — §e" + (npc.getId() != null ? npc.getId() : "?"), this.width - 176),
                12, 8, 0xFFFFFFFF);

        var rules = model.getRules();
        var visible = model.filteredRuleIndices();
        int top = 22;
        int bottom = this.height - 26;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);

        if (rules.isEmpty()) {
            g.drawString(this.font, "§7No behavior rules — add one below.", 12, top + 4, COLOR_LABEL);
        } else if (visible.isEmpty()) {
            g.drawString(this.font, "§7No rules match the filter.", 12, top + 4, COLOR_LABEL);
        }
        g.enableScissor(0, top, this.width, bottom);
        for (int i = 0; i < maxRows && i + listScroll < visible.size(); i++) {
            int idx = visible.get(i + listScroll);
            int ry = top + i * ROW_H;
            boolean hover = mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) {
                g.fill(4, ry, this.width - 4, ry + ROW_H, 0x33FFFFFF);
            }
            String line = "§7[" + (idx + 1) + "] §f" + model.describe(rules.get(idx));
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.width - 48),
                    10, ry + 2, hover ? 0xFFFFFFFF : 0xFFD4D4D8);
        }
        g.disableScissor();
        if (visible.size() > maxRows) {
            g.drawString(this.font, "§7(scroll — " + visible.size() + " rules)", 108, this.height - 20, COLOR_LABEL);
        }
        drawStatus(g, this.height - 10);
    }

    private void renderAdd(GuiGraphics g) {
        g.drawString(this.font, "§6Add Rule — §e" + model.getNpc().getId(), 12, 8, 0xFFFFFFFF);
        drawStatus(g, this.height - 38);
    }

    private void drawStatus(GuiGraphics g, int y) {
        if (!model.getStatusMessage().isEmpty()) {
            // x=108 keeps the line clear of the "+ Add Rule" button (x12..102)
            String msg = this.font.plainSubstrByWidth(model.getStatusMessage(), this.width - 116);
            g.drawString(this.font, msg, 108, y, model.isStatusError() ? COLOR_ERR : COLOR_OK);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (!model.isAddMode()) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(dy));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
