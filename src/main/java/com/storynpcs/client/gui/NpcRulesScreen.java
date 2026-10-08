package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.FieldValidator;
import com.storynpcs.client.ui.widgets.FormRow;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.editor.NpcRulesScreenModel;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rules sub-screen of {@link NpcEditorScreen} (issue #26) — per-NPC behavior
 * rule authoring mirroring /storynpcs npc rule add|list|remove. Mutations go
 * through the existing whole-definition save payload → saveNpc, so server-side
 * validation is identical to the command path.
 *
 * <p>Migrated onto the shared chrome (#206): the list is a {@link SelectableList},
 * remove is an armed footer action, and status echoes ride the footer's
 * contrast-safe line instead of dark-green text over the dimmed world (D2).
 */
public class NpcRulesScreen extends UiScreen {

    private final NpcRulesScreenModel model;
    private final SelectionModel<Integer> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();
    private SelectableList<RuleRow, Integer> ruleList;
    private boolean armedRemove = false;
    private EditBox filterField;
    private long expectedRevision;
    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();
    private final List<FormRow> argRows = new ArrayList<>();

    /** Selection identity is the underlying rule index, not the filtered position. */
    private record RuleRow(int ruleIndex, String label) {}

    public NpcRulesScreen(NpcDefinition npc) {
        this(npc, 0L);
    }

    public NpcRulesScreen(NpcDefinition npc, long expectedRevision) {
        super(Component.literal("Rules — " + (npc.getId() != null ? npc.getId().toString() : "?")));
        this.model = new NpcRulesScreenModel(npc);
        this.expectedRevision = Math.max(0L, expectedRevision);
    }

    public NpcRulesScreenModel getModel() { return model; }

    public void onSaveResult(UUID requestId, boolean success, String message, long revision) {
        if (!saveRequestId.matchesCurrent(requestId)) return;
        model.setStatus(message, !success);
        syncStatus();
        // The response revision is authoritative on success AND on rejection
        // (the server echoes the current token) — never guess with ++.
        expectedRevision = Math.max(0L, revision);
        saveRequestId.acknowledge(requestId);
    }

    private void syncStatus() {
        if (!model.getStatusMessage().isEmpty()) {
            // Transient echo — the base help line restores after it expires.
            echo(Component.literal(model.getStatusMessage())
                    .withColor(model.isStatusError() ? UiTheme.DANGER : UiTheme.TEXT), 4000);
        }
    }

    @Override
    protected void initContent() {
        argRows.clear();
        if (model.isAddMode()) {
            initAddWidgets();
        } else {
            initListWidgets();
        }
    }

    // ── List mode ───────────────────────────────────────────────────────────

    private void initListWidgets() {
        int filterW = Math.min(110, contentWidth() / 3);
        filterField = new EditBox(this.font, contentRight() - filterW, contentTop() - 2,
                filterW, UiTheme.BUTTON_H, Component.literal("Filter"));
        filterField.setHint(Component.literal("filter…"));
        filterField.setMaxLength(48);
        filterField.setValue(model.getListFilter());
        if (!model.getListFilter().isEmpty()) {
            filterField.setFocused(true);
            this.setFocused(filterField);
            filterField.moveCursorToEnd(false);
        }
        filterField.setResponder(v -> {
            model.setListFilter(v);
            armedRemove = false;
            refreshRules();
        });
        addRenderableWidget(filterField);

        ruleList = new SelectableList<>(contentLeft(), contentTop() + UiTheme.ROW_H + 4,
                contentWidth(), contentBottom() - contentTop() - UiTheme.ROW_H - 4,
                UiTheme.ROW_H, RuleRow::ruleIndex,
                r -> Component.literal("§7[" + (r.ruleIndex() + 1) + "] §f" + r.label()),
                selection, scroll);
        addRenderableWidget(ruleList);
        refreshRules();

        addFooterAction(Component.literal("+ Add Rule"), b -> {
            model.beginAdd();
            armedRemove = false;
            rebuildWidgets();
        });
        addFooterAction(Component.literal(armedRemove ? "Sure?" : "Remove"), b -> {
            RuleRow row = ruleList.selectedRow();
            if (row == null) {
                echo(Component.literal("Select a rule first."), 1600);
                return;
            }
            if (!armedRemove) {
                armedRemove = true;
                rebuildWidgets();
                return;
            }
            armedRemove = false;
            String err = model.removeRule(row.ruleIndex());
            if (err == null) {
                sendSave("Removing rule...");
            } else {
                model.setStatus(err, true);
            }
            syncStatus();
            rebuildWidgets();
        });
        addFooterAction(Component.literal("Back"), b ->
                minecraft.setScreen(new NpcEditorScreen(model.getNpc(), expectedRevision)));
        setStatus(Component.literal("Click a rule to select — Remove asks to confirm.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private void refreshRules() {
        var visible = model.filteredRuleIndices();
        var rules = model.getRules();
        List<RuleRow> rows = new ArrayList<>(visible.size());
        for (int idx : visible) {
            rows.add(new RuleRow(idx, model.describe(rules.get(idx))));
        }
        ruleList.setRows(rows);
    }

    // ── Add mode ────────────────────────────────────────────────────────────

    private void initAddWidgets() {
        int y = contentTop();
        int pickW = Math.min(150, contentWidth() * 2 / 5);
        int argX = contentLeft() + pickW + UiTheme.PAD_M;
        int argW = contentRight() - argX;

        addRenderableWidget(cycleBtn("Trigger: " + NpcRulesScreenModel.TRIGGERS[model.getTriggerIdx()],
                contentLeft(), y, pickW, () -> model.cycleTrigger(1)));
        y += UiTheme.BUTTON_H + 4;

        addRenderableWidget(cycleBtn("If: " + NpcRulesScreenModel.CONDITIONS[model.getCondIdx()],
                contentLeft(), y, pickW, () -> { model.cycleCondition(1); rebuildWidgets(); }));

        if (model.condNeedsFaction()) {
            argRows.add(argRow(argX, y, argW, "faction id", model::setCondFaction,
                    FieldValidator.all(FieldValidator.required("faction id"),
                            FieldValidator.namespacedId())));
        }
        if (model.condNeedsStanding()) {
            addRenderableWidget(cycleBtn(NpcRulesScreenModel.STANDINGS[model.getStandingIdx()],
                    argX, y, Math.min(90, argW), () -> model.cycleStanding(1)));
        }
        if (model.condNeedsThreshold()) {
            addRenderableWidget(cycleBtn(NpcRulesScreenModel.OPERATORS[model.getCondOpIdx()],
                    argX, y, 30, () -> model.cycleCondOp(1)));
            argRows.add(argRow(argX + 34, y, argW - 34, "value", model::setCondThreshold,
                    number(true)));
        }
        y += UiTheme.ROW_H + UiTheme.PAD_S;

        addRenderableWidget(cycleBtn("Do: " + NpcRulesScreenModel.ACTIONS[model.getActionIdx()],
                contentLeft(), y, pickW, () -> { model.cycleAction(1); rebuildWidgets(); }));

        if (model.actNeedsText()) {
            argRows.add(argRow(argX, y, argW, "message text", model::setActText,
                    FieldValidator.required("message text")));
        }
        if (model.actNeedsAmount()) {
            argRows.add(argRow(argX, y, argW, "amount (def 100)", model::setActAmount,
                    number(false)));
        }
        if (model.actNeedsRadiusMessage()) {
            int half = (argW - UiTheme.PAD_S) / 2;
            argRows.add(argRow(argX, y, half, "radius", model::setActRadius, number(false)));
            argRows.add(argRow(argX + half + UiTheme.PAD_S, y, half, "alert message",
                    model::setActMessage, FieldValidator.required("alert message")));
        }
        if (model.actNeedsFractionDialogue()) {
            int half = (argW - UiTheme.PAD_S) / 2;
            argRows.add(argRow(argX, y, half, "heal frac (0.5)", model::setActFraction,
                    number(false)));
            argRows.add(argRow(argX + half + UiTheme.PAD_S, y, half, "yield line",
                    model::setActDialogue));
        }
        if (model.actNeedsStance()) {
            addRenderableWidget(cycleBtn(NpcRulesScreenModel.STANCES[model.getStanceIdx()],
                    argX, y, Math.min(110, argW), () -> model.cycleStance(1)));
        }
        if (model.actNeedsFactionDelta()) {
            int half = (argW - UiTheme.PAD_S) / 2;
            argRows.add(argRow(argX, y, half, "faction id", model::setActFaction,
                    FieldValidator.all(FieldValidator.required("faction id"),
                            FieldValidator.namespacedId())));
            argRows.add(argRow(argX + half + UiTheme.PAD_S, y, half, "delta",
                    model::setActDelta, number(true)));
        }

        addFooterAction(Component.literal("Add & Save"), b -> {
            boolean invalid = false;
            for (FormRow row : argRows) {
                if (!row.validate()) invalid = true;
            }
            if (invalid) {
                echo(Component.literal("Fix the highlighted field(s).").withColor(UiTheme.DANGER), 3000);
                return;
            }
            String err = model.commitAdd();
            if (err == null) {
                sendSave("Adding rule...");
            }
            syncStatus();
            rebuildWidgets();
        });
        addFooterAction(Component.literal("Cancel"), b -> {
            model.cancelAdd();
            rebuildWidgets();
        });
        setStatus(Component.literal("Pick trigger / if / do — fill the arg fields inline.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private FormRow argRow(int x, int y, int w, String hint,
                           java.util.function.Consumer<String> responder) {
        return argRow(x, y, w, hint, responder, v -> null);
    }

    private FormRow argRow(int x, int y, int w, String hint,
                           java.util.function.Consumer<String> responder,
                           FieldValidator check) {
        FormRow row = new FormRow(this.font, Component.empty(), Component.literal(hint));
        row.editBox().setMaxLength(96);
        // The model owns the value; commitAdd remains the authoritative gate.
        // Sync through the validator slot — overriding the EditBox responder
        // would detach FormRow's per-edit validation.
        row.setValidator(v -> { responder.accept(v); return check.validate(v); });
        row.layout(x, y, w, 0);
        addRenderableWidget(row.editBox());
        return row;
    }

    /** Numeric check mirroring the model's parse* paths; blank may be legal. */
    private static FieldValidator number(boolean required) {
        return v -> {
            if (v == null || v.isBlank()) {
                return required ? "Number required" : null;
            }
            try {
                Double.parseDouble(v.trim());
                return null;
            } catch (NumberFormatException e) {
                return "Not a number";
            }
        };
    }

    private Button cycleBtn(String label, int x, int y, int w, Runnable onClick) {
        return Button.builder(Component.literal(label), b -> onClick.run())
                .bounds(x, y, Math.max(20, w), UiTheme.BUTTON_H).build();
    }

    // ── Save plumbing (same payload as the NPC editor — saveNpc path) ───────

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
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (model.isAddMode()) {
            for (FormRow row : argRows) {
                row.render(g, mouseX, mouseY);
            }
            return;
        }
        var rules = model.getRules();
        if (rules.isEmpty()) {
            renderEmpty(g, "No behavior rules — add one below.");
        } else if (model.filteredRuleIndices().isEmpty()) {
            renderEmpty(g, "No rules match the filter.");
        }
        if (ruleList != null && ruleList.scroll().maxOffset() > 0) {
            g.drawString(this.font, "§7" + model.filteredRuleIndices().size() + " rules",
                    contentLeft(), contentTop() + 2, UiTheme.TEXT_MUTED);
        }
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
