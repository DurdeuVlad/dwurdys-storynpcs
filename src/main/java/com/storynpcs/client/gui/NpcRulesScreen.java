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
    /** Armed remove is bound to a specific rule index, not a bare flag —
        clicking a different row re-arms for that row. */
    private int armedRule = -1;
    private EditBox filterField;
    private boolean filterHadFocus;
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

    private String lastEchoed;

    @Override
    protected void onStatusExpired() {
        // Allow an identical subsequent message to echo again.
        lastEchoed = null;
    }

    private void syncStatus() {
        String msg = model.getStatusMessage();
        // Transient echo — the base help line restores after it expires.
        // Rebuilds re-run initContent — don't replay an identical echo.
        if (!msg.isEmpty() && !msg.equals(lastEchoed)) {
            lastEchoed = msg;
            echo(Component.literal(msg)
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
        if (filterHadFocus || !model.getListFilter().isEmpty()) {
            filterField.setFocused(true);
            this.setFocused(filterField);
            filterField.moveCursorToEnd(false);
        }
        filterField.setResponder(v -> {
            // A keystroke implies focus — remember it so rebuilds keep it.
            filterHadFocus = true;
            model.setListFilter(v);
            armedRule = -1;
            refreshRules();
        });
        addRenderableWidget(filterField);

        ruleList = new SelectableList<>(contentLeft(), contentTop() + UiTheme.ROW_H + 4,
                contentWidth(),
                Math.max(1, contentBottom() - contentTop() - UiTheme.ROW_H - 4),
                UiTheme.ROW_H, RuleRow::ruleIndex,
                r -> Component.literal("§7[" + (r.ruleIndex() + 1) + "] §f" + r.label()),
                selection, scroll);
        addRenderableWidget(ruleList);
        refreshRules();

        addFooterAction(Component.literal("+ Add Rule"), b -> {
            model.beginAdd();
            armedRule = -1;
            rebuildWidgets();
        });
        addFooterAction(Component.literal(armedRule >= 0 ? "Sure?" : "Remove"), b -> {
            RuleRow row = ruleList.selectedRow();
            if (row == null) {
                if (armedRule >= 0) {
                    // Clear the stale "Sure?" label — nothing is selected.
                    armedRule = -1;
                    rebuildWidgets();
                }
                echo(Component.literal("Select a rule first."), 1600);
                return;
            }
            if (armedRule != row.ruleIndex()) {
                armedRule = row.ruleIndex();
                rebuildWidgets();
                return;
            }
            armedRule = -1;
            String err = model.removeRule(row.ruleIndex());
            if (err == null) {
                sendSave("Removing rule...");
            } else {
                model.setStatus(err, true);
            }
            // Rebuild first — init() clears the status slot, so an echo sent
            // before the rebuild would never render.
            rebuildWidgets();
            syncStatus();
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
        int argW = Math.max(40, contentRight() - argX);

        addRenderableWidget(cycleBtn(
                () -> "Trigger: " + NpcRulesScreenModel.TRIGGERS[model.getTriggerIdx()],
                contentLeft(), y, pickW, () -> model.cycleTrigger(1)));
        y += UiTheme.BUTTON_H + 4;

        addRenderableWidget(cycleBtn(
                () -> "If: " + NpcRulesScreenModel.CONDITIONS[model.getCondIdx()],
                contentLeft(), y, pickW, () -> { model.cycleCondition(1); rebuildWidgets(); }));

        int argFieldX = argX;
        int argFieldW = argW;
        // faction_standing needs its standing picker *beside* the faction
        // field, not on top of it — offset the field right of the button.
        if (model.condNeedsStanding()) {
            int standingW = Math.max(20, Math.min(90, argW - 44));
            addRenderableWidget(cycleBtn(
                    () -> NpcRulesScreenModel.STANDINGS[model.getStandingIdx()],
                    argX, y, standingW, () -> model.cycleStanding(1)));
            argFieldX = argX + standingW + UiTheme.PAD_XS;
            argFieldW = Math.max(20, argW - standingW - UiTheme.PAD_XS);
        }
        if (model.condNeedsFaction()) {
            argRows.add(argRow(argFieldX, y, argFieldW, "faction id",
                    model::getCondFaction, model::setCondFaction,
                    FieldValidator.all(FieldValidator.required("faction id"),
                            FieldValidator.namespacedId())));
        }
        if (model.condNeedsThreshold()) {
            addRenderableWidget(cycleBtn(
                    () -> NpcRulesScreenModel.OPERATORS[model.getCondOpIdx()],
                    argX, y, 30, () -> model.cycleCondOp(1)));
            argRows.add(argRow(argX + 34, y, Math.max(20, argW - 34), "value",
                    model::getCondThreshold, model::setCondThreshold,
                    number(true, model.condIdxUsesWholeNumber())));
        }
        // Leave room beneath the If row for a FormRow error line (~9px).
        y += UiTheme.ROW_H + 10;

        addRenderableWidget(cycleBtn(
                () -> "Do: " + NpcRulesScreenModel.ACTIONS[model.getActionIdx()],
                contentLeft(), y, pickW, () -> { model.cycleAction(1); rebuildWidgets(); }));

        if (model.actNeedsText()) {
            argRows.add(argRow(argX, y, argW, "message text",
                    model::getActText, model::setActText,
                    FieldValidator.required("message text")));
        }
        if (model.actNeedsAmount()) {
            argRows.add(argRow(argX, y, argW, "amount (def 100)",
                    model::getActAmount, model::setActAmount, number(false, false)));
        }
        if (model.actNeedsRadiusMessage()) {
            int half = (argW - UiTheme.PAD_S) / 2;
            argRows.add(argRow(argX, y, half, "radius",
                    model::getActRadius, model::setActRadius, number(false, false)));
            argRows.add(argRow(argX + half + UiTheme.PAD_S, y, half, "alert message",
                    model::getActMessage, model::setActMessage,
                    FieldValidator.required("alert message")));
        }
        if (model.actNeedsFractionDialogue()) {
            int half = (argW - UiTheme.PAD_S) / 2;
            argRows.add(argRow(argX, y, half, "heal frac (0.5)",
                    model::getActFraction, model::setActFraction, number(false, false)));
            argRows.add(argRow(argX + half + UiTheme.PAD_S, y, half, "yield line",
                    model::getActDialogue, model::setActDialogue));
        }
        if (model.actNeedsStance()) {
            addRenderableWidget(cycleBtn(
                    () -> NpcRulesScreenModel.STANCES[model.getStanceIdx()],
                    argX, y, Math.min(110, argW), () -> model.cycleStance(1)));
        }
        if (model.actNeedsFactionDelta()) {
            int half = (argW - UiTheme.PAD_S) / 2;
            argRows.add(argRow(argX, y, half, "faction id",
                    model::getActFaction, model::setActFaction,
                    FieldValidator.all(FieldValidator.required("faction id"),
                            FieldValidator.namespacedId())));
            argRows.add(argRow(argX + half + UiTheme.PAD_S, y, half, "delta",
                    model::getActDelta, model::setActDelta, number(true, true)));
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
            // Rebuild first — init() clears the status slot; the post-rebuild
            // syncStatus lands the echo (success or commit error) visibly.
            rebuildWidgets();
            syncStatus();
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
                           java.util.function.Supplier<String> seed,
                           java.util.function.Consumer<String> responder) {
        return argRow(x, y, w, hint, seed, responder, v -> null);
    }

    private FormRow argRow(int x, int y, int w, String hint,
                           java.util.function.Supplier<String> seed,
                           java.util.function.Consumer<String> responder,
                           FieldValidator check) {
        FormRow row = new FormRow(this.font, Component.empty(), Component.literal(hint));
        row.editBox().setMaxLength(96);
        // Seed from the model first — rebuilds (picker cycle, resize, failed
        // commit) recreate the widget, and an un-seeded row would clobber the
        // typed value back into the model when the validator fires.
        row.setValue(seed.get());
        // Sync through the validator slot — overriding the EditBox responder
        // would detach FormRow's per-edit validation. commitAdd remains the
        // authoritative gate (ranges, blank defaults).
        row.setValidator(v -> { responder.accept(v); return check.validate(v); });
        row.layout(x, y, w, 0);
        addRenderableWidget(row.editBox());
        return row;
    }

    /** Numeric check mirroring the model's parse* paths; blank may be legal. */
    private static FieldValidator number(boolean required, boolean whole) {
        return v -> {
            if (v == null || v.isBlank()) {
                return required ? "Number required" : null;
            }
            try {
                if (whole) {
                    Integer.parseInt(v.trim());
                } else if (!Double.isFinite(Double.parseDouble(v.trim()))) {
                    return "Not a finite number";
                }
                return null;
            } catch (NumberFormatException e) {
                return whole ? "Not a whole number" : "Not a number";
            }
        };
    }

    private Button cycleBtn(java.util.function.Supplier<String> label,
                            int x, int y, int w, Runnable onClick) {
        return Button.builder(Component.literal(label.get()), b -> {
            onClick.run();
            b.setMessage(Component.literal(label.get()));
        }).bounds(x, y, Math.max(20, w), UiTheme.BUTTON_H).build();
    }

    // ── Save plumbing (same payload as the NPC editor — saveNpc path) ───────

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
