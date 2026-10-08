package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.FieldValidator;
import com.storynpcs.client.ui.widgets.FormRow;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.editor.FactionEditorScreenModel;
import com.storynpcs.editor.FactionEditorScreenModel.Mode;
import com.storynpcs.network.ServerboundFactionDeletePayload;
import com.storynpcs.network.ServerboundFactionSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * In-game faction authoring surface (issue #25). LIST mode browses the synced
 * registry; EDIT mode mirrors the /storynpcs faction command surface — name,
 * defaultPoints, hostileThreshold, friendlyThreshold — routed through
 * {@link FactionEditorScreenModel} and the same service methods as the commands.
 *
 * <p>Migrated onto the shared chrome (#208): the faction list is a
 * {@link SelectableList} keyed by faction id, the new-faction bar sits above
 * the footer, and the edit form uses {@link FormRow} with inline validation
 * matching {@link FactionEditorScreenModel#validateForSave()}.
 */
public class FactionEditorScreen extends UiScreen {

    private final FactionEditorScreenModel model = new FactionEditorScreenModel();

    // LIST widgets
    private SelectableList<Faction, String> factionList;
    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();
    private EditBox filterField;
    private boolean filterHadFocus;
    private EditBox newIdField;
    private EditBox newNameField;

    // EDIT widgets
    private final java.util.List<FormRow> fieldRows = new java.util.ArrayList<>();
    /** Raw text drafts for the int fields — survives rebuilds so mid-edit
     * (even unparseable) input is never lost on a widget rebuild. Reseeded
     * when a different faction enters edit mode. */
    private String draftId;
    private String draftName;
    private String draftDefaultPoints;
    private String draftHostile;
    private String draftFriendly;
    /** Working-copy identity the drafts were seeded from — beginEdit/beginNew
     * swap it, so drafts reseed on entry but never mid-edit. */
    private Faction draftsFor;

    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();
    private final PayloadBoundRequestId deleteRequestId = new PayloadBoundRequestId();
    /** Definition ids bound to in-flight save/delete requests — revision updates are id-scoped. */
    private String pendingSaveId;
    private String pendingDeleteId;
    private String lastEchoed;

    public FactionEditorScreen(List<Faction> factions, String selectId) {
        this(factions, selectId, 0L, java.util.Map.of());
    }

    public FactionEditorScreen(List<Faction> factions, String selectId, long expectedRevision) {
        this(factions, selectId, expectedRevision, java.util.Map.of());
    }

    public FactionEditorScreen(List<Faction> factions, String selectId, long expectedRevision,
                               java.util.Map<String, Long> revisions) {
        super(Component.literal("Faction Editor"));
        model.loadExpectedRevisions(revisions);
        if (selectId != null && !selectId.isBlank()) {
            model.recordRevisionHint(selectId, Math.max(0L, expectedRevision));
        }
        model.loadFactions(factions);
        if (selectId != null && !selectId.isBlank()) {
            try {
                model.beginEdit(NamespacedId.of(selectId));
            } catch (Exception e) {
                model.setStatus("Malformed faction id: " + selectId, true);
            }
        }
    }

    public FactionEditorScreenModel getModel() { return model; }

    public void onSaveResult(UUID requestId, boolean success, String message, List<Faction> refreshed,
                             long committedRevision) {
        boolean saveResponse = saveRequestId.matchesCurrent(requestId);
        boolean deleteResponse = deleteRequestId.matchesCurrent(requestId);
        if (!saveResponse && !deleteResponse) return;
        // The response revision is authoritative on success AND on rejection
        // (the server echoes the current token), so the row's next save is not
        // stuck on a stale expectation after a lost response.
        String subjectId = saveResponse ? pendingSaveId : pendingDeleteId;
        if (subjectId != null) {
            model.recordCommittedRevision(subjectId, committedRevision);
        }
        model.onSaveResult(success, message, refreshed);
        if (saveResponse) saveRequestId.acknowledge(requestId);
        if (deleteResponse) deleteRequestId.acknowledge(requestId);
        if (this.minecraft != null) rebuildWidgets();
    }

    @Override
    protected void onStatusExpired() {
        lastEchoed = null; // identical later results may echo again
    }

    private void syncStatus() {
        String msg = model.getStatusMessage();
        if (!msg.isEmpty() && !msg.equals(lastEchoed)) {
            lastEchoed = msg;
            echo(Component.literal(msg)
                    .withColor(model.isStatusError() ? UiTheme.DANGER : UiTheme.TEXT), 4000);
        }
    }

    @Override
    protected void initContent() {
        fieldRows.clear();
        if (model.getMode() == Mode.LIST) {
            initListMode();
        } else {
            initEditMode();
        }
    }

    // ── LIST mode ───────────────────────────────────────────────────────────

    private void initListMode() {
        filterField = new EditBox(this.font, contentRight() - 120, contentTop(), 120,
                UiTheme.BUTTON_H, Component.literal("Filter"));
        filterField.setHint(Component.literal("filter…"));
        filterField.setMaxLength(48);
        filterField.setValue(model.getListFilter());
        if (filterHadFocus || !model.getListFilter().isEmpty()) {
            filterField.setFocused(true);
            this.setFocused(filterField);
            filterField.moveCursorToEnd(false);
        }
        filterField.setResponder(v -> {
            filterHadFocus = true;
            model.setListFilter(v);
            refreshFactionRows();
        });
        addRenderableWidget(filterField);

        int listTop = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;
        int barY = contentBottom() - UiTheme.BUTTON_H;
        factionList = new SelectableList<>(contentLeft(), listTop, contentWidth(),
                Math.max(1, barY - listTop - UiTheme.PAD_S), UiTheme.ROW_H,
                f -> f.getId() != null ? f.getId().toString() : null,
                this::factionLabel, selection, scroll);
        factionList.setOnActivate(f -> {
            if (f.getId() != null) {
                model.beginEdit(f.getId());
                rebuildWidgets();
            }
        });
        addRenderableWidget(factionList);
        refreshFactionRows();

        // New-faction bar: id + name fields on a row above the footer.
        int idW = Math.max(60, Math.min(170, contentWidth() / 2));
        newIdField = new EditBox(this.font, contentLeft(), barY, idW,
                UiTheme.BUTTON_H, Component.literal("Faction id"));
        newIdField.setHint(Component.literal("storynpcs:faction_id"));
        newIdField.setMaxLength(64);
        addRenderableWidget(newIdField);

        int nameX = contentLeft() + idW + UiTheme.PAD_S;
        newNameField = new EditBox(this.font, nameX, barY,
                Math.max(60, contentRight() - nameX - 80), UiTheme.BUTTON_H,
                Component.literal("Name"));
        newNameField.setHint(Component.literal("Name (optional)"));
        newNameField.setMaxLength(64);
        addRenderableWidget(newNameField);

        addFooterAction(Component.literal("+ New"), b -> {
            String raw = newIdField.getValue().trim();
            NamespacedId id;
            try {
                id = NamespacedId.of(raw);
            } catch (Exception e) {
                echo(Component.literal("Malformed faction id: '" + raw + "'")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            if (model.beginNew(id, newNameField.getValue().trim())) {
                newIdField.setValue("");
                rebuildWidgets();
            }
        });
        addFooterAction(Component.literal("Close"), b -> onClose());
        setStatus(Component.literal("Enter or double-click a faction to edit — new factions start below.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private void refreshFactionRows() {
        factionList.setRows(model.getFilteredFactions());
    }

    private Component factionLabel(Faction f) {
        String line = "§e" + f.getId() + " §7— §f" + (f.getName() != null ? f.getName() : "")
                + " §7(hostile<" + f.getHostileThreshold() + " friendly>=" + f.getFriendlyThreshold() + ")";
        return Component.literal(line);
    }

    // ── EDIT mode ───────────────────────────────────────────────────────────

    private void initEditMode() {
        Faction f = model.getEditing();
        seedDrafts(f);

        int labelW = Math.min(96, contentWidth() / 4);
        int w = Math.min(320, contentWidth());
        int y = contentTop();

        if (model.isEditingNew()) {
            y = row(y, w, labelW, "ID", "storynpcs:faction_id",
                    () -> draftId, v -> { draftId = v; model.setFactionId(v); },
                    FieldValidator.all(FieldValidator.required("faction id"),
                            FieldValidator.namespacedOrBare()));
        }
        y = row(y, w, labelW, "Name", "display name",
                () -> draftName, v -> { draftName = v; model.setName(v); }, null);
        y = row(y, w, labelW, "Default Points", "start rep",
                () -> draftDefaultPoints,
                v -> { draftDefaultPoints = v; applyInt(v, model::setDefaultPoints); },
                intValidator(Integer.MIN_VALUE, Integer.MAX_VALUE));
        y = row(y, w, labelW, "Hostile below", "rep < this → attacked",
                () -> draftHostile,
                v -> { draftHostile = v; applyInt(v, model::setHostileThreshold); },
                intValidator(Integer.MIN_VALUE, Integer.MAX_VALUE));
        y = row(y, w, labelW, "Friendly at/above", "rep >= this → allied",
                () -> draftFriendly,
                v -> { draftFriendly = v; applyInt(v, model::setFriendlyThreshold); },
                intValidator(Integer.MIN_VALUE, Integer.MAX_VALUE));

        addFooterAction(Component.literal("Save"), b -> save());
        addFooterAction(
                Component.literal(model.isDeleteArmed() ? "Sure?" : "Delete"), b -> {
                    Faction cur = model.getEditing();
                    if (cur == null || cur.getId() == null) return;
                    if (model.confirmDeleteClick()) {
                        long revision = model.expectedRevision();
                        pendingDeleteId = cur.getId().toString();
                        UUID requestId = deleteRequestId.forPayload(
                                "faction-delete\n" + cur.getId() + "\n" + revision);
                        PacketDistributor.sendToServer(new ServerboundFactionDeletePayload(
                                cur.getId().toString(), revision, requestId));
                        model.setStatus("Deleting...", false);
                    }
                    rebuildWidgets();
                    syncStatus();
                });
        addFooterAction(Component.literal("Back"), b -> {
            model.backToList();
            rebuildWidgets();
        });
        setStatus(Component.literal("Below Hostile → attacked on sight; at/above Friendly → allied perks.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private void seedDrafts(Faction f) {
        if (f == draftsFor) return;
        draftsFor = f;
        draftId = f.getId() != null ? f.getId().toString() : "";
        draftName = f.getName() != null ? f.getName() : "";
        draftDefaultPoints = String.valueOf(f.getDefaultPoints());
        draftHostile = String.valueOf(f.getHostileThreshold());
        draftFriendly = String.valueOf(f.getFriendlyThreshold());
    }

    private int row(int y, int w, int labelW, String label, String hint,
                    java.util.function.Supplier<String> seed,
                    java.util.function.Consumer<String> responder, FieldValidator check) {
        FormRow formRow = new FormRow(this.font, Component.literal(label), Component.literal(hint));
        formRow.editBox().setMaxLength(96);
        formRow.setValue(seed.get());
        FieldValidator validator = check == null ? v -> null : check;
        formRow.setValidator(v -> { responder.accept(v); return validator.validate(v); });
        int nextY = formRow.layout(contentLeft(), y, w, labelW);
        addRenderableWidget(formRow.editBox());
        fieldRows.add(formRow);
        return nextY;
    }

    private static FieldValidator intValidator(int min, int max) {
        return v -> {
            if (v == null || v.isBlank()) return "Number required";
            try {
                int n = Integer.parseInt(v.trim());
                return n < min || n > max ? "Must be " + min + ".." + max : null;
            } catch (NumberFormatException e) {
                return "Not a whole number";
            }
        };
    }

    private static void applyInt(String v, java.util.function.IntConsumer apply) {
        try {
            apply.accept(Integer.parseInt(v.trim()));
        } catch (NumberFormatException ignored) { }
    }

    private void save() {
        boolean invalid = false;
        for (FormRow row : fieldRows) {
            if (!row.validate()) invalid = true;
        }
        if (invalid) {
            echo(Component.literal("Fix the highlighted field(s).")
                    .withColor(UiTheme.DANGER), 3000);
            return;
        }
        String err = model.validateForSave();
        if (err != null) {
            model.setStatus(err, true);
            syncStatus();
            return;
        }
        model.setStatus("Saving...", false);
        String submittedJson = model.saveJson();
        pendingSaveId = model.getEditing() != null && model.getEditing().getId() != null
                ? model.getEditing().getId().toString() : null;
        UUID requestId = saveRequestId.forPayload(submittedJson);
        PacketDistributor.sendToServer(new ServerboundFactionSavePayload(
                submittedJson, model.expectedRevision(), requestId));
        syncStatus();
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        for (FormRow row : fieldRows) {
            row.render(g, mouseX, mouseY);
        }
        if (model.getMode() == Mode.LIST) {
            List<Faction> factions = model.getFilteredFactions();
            if (factions.isEmpty()) {
                renderEmpty(g, model.factionCount() > 0
                        ? "No factions match the filter."
                        : "No factions defined yet — create one below.");
            } else if (!model.getListFilter().isBlank()
                    && factions.size() < model.factionCount()) {
                // Free space on the filter row, left of the box.
                g.drawString(this.font, factions.size() + " of " + model.factionCount(),
                        contentLeft(), contentTop() + 3, UiTheme.TEXT_MUTED);
            }
        }
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
