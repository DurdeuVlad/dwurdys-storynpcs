package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.FieldValidator;
import com.storynpcs.client.ui.widgets.FormRow;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.editor.QuestEditorScreenModel;
import com.storynpcs.editor.QuestEditorScreenModel.Mode;
import com.storynpcs.editor.QuestEditorScreenModel.RowKind;
import com.storynpcs.network.ServerboundQuestDeletePayload;
import com.storynpcs.network.ServerboundQuestSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-game quest authoring surface (issue #24). LIST mode browses the synced
 * registry; EDIT mode mirrors the /storynpcs quest command surface — fields,
 * cycle buttons, and objective/reward rows — and routes every mutation through
 * {@link QuestEditorScreenModel} + the same server save/delete packets the
 * commands use.
 *
 * <p>Migrated onto the shared chrome (#208): the registry list and the
 * objectives/rewards region are {@link SelectableList}s (the latter a union
 * list with unselectable section headers and "+ add" sentinel rows), the edit
 * form uses {@link FormRow} with inline validation, and the row editor is a
 * content-mode swap rather than an overlay.
 */
public class QuestEditorScreen extends UiScreen {

    private final QuestEditorScreenModel model = new QuestEditorScreenModel();

    // LIST widgets
    private SelectableList<Quest, String> questList;
    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState listScroll = new ScrollState();
    private EditBox filterField;
    private boolean filterHadFocus;
    private EditBox newIdField;
    private EditBox newTitleField;

    // EDIT widgets
    private final List<FormRow> fieldRows = new ArrayList<>();
    private SelectableList<Row, String> rowList;
    private final SelectionModel<String> rowSelection = new SelectionModel<>();
    private final ScrollState rowScroll = new ScrollState();
    /** Armed remove is bound to the selected row key. */
    private String armedRow;

    // Row-editor (add/edit objective|reward) drafts — survive rebuilds.
    private String rowType;
    private String rowTarget = "";
    private String rowCount = "1";
    private int rowEditorFor = -2; // RowKind ordinal+index pair the drafts seed from
    private int rowEditorIdx = -2;

    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();
    private final PayloadBoundRequestId deleteRequestId = new PayloadBoundRequestId();
    /** Definition ids bound to in-flight save/delete requests — revision updates are id-scoped. */
    private String pendingSaveId;
    private String pendingDeleteId;
    private String lastEchoed;

    /** A union row: section headers carry a null key (render, never select),
     * data rows carry "o<i>"/"r<i>", and the "+ add" sentinels "add-o"/"add-r". */
    private record Row(String key, String label) {}

    public QuestEditorScreen(List<Quest> quests, String selectId) {
        this(quests, selectId, 0L, java.util.Map.of());
    }

    public QuestEditorScreen(List<Quest> quests, String selectId, long expectedRevision) {
        this(quests, selectId, expectedRevision, java.util.Map.of());
    }

    public QuestEditorScreen(List<Quest> quests, String selectId, long expectedRevision,
                             java.util.Map<String, Long> revisions) {
        super(Component.literal("Quest Editor"));
        model.loadExpectedRevisions(revisions);
        if (selectId != null && !selectId.isBlank()) {
            model.recordRevisionHint(selectId, Math.max(0L, expectedRevision));
        }
        model.loadQuests(quests);
        if (selectId != null && !selectId.isBlank()) {
            try {
                model.beginEdit(NamespacedId.of(selectId));
            } catch (Exception e) {
                model.setStatus("Malformed quest id: " + selectId, true);
            }
        }
    }

    public QuestEditorScreenModel getModel() { return model; }

    public void onSaveResult(UUID requestId, boolean success, String message, List<Quest> refreshed,
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
        // refresh the edit-fields if the server replaced our working copy view
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
        } else if (model.getRowKind() != RowKind.NONE) {
            initRowEditor();
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
            refreshQuestRows();
        });
        addRenderableWidget(filterField);

        int listTop = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;
        int barY = contentBottom() - UiTheme.BUTTON_H;
        questList = new SelectableList<>(contentLeft(), listTop, contentWidth(),
                Math.max(1, barY - listTop - UiTheme.PAD_S), UiTheme.ROW_H,
                q -> q.getId() != null ? q.getId().toString() : null,
                q -> Component.literal("§e" + q.getId() + " §7— §f"
                        + (q.getTitle() != null ? q.getTitle() : "")),
                selection, listScroll);
        questList.setOnActivate(q -> {
            if (q.getId() != null) {
                model.beginEdit(q.getId());
                rebuildWidgets();
            }
        });
        addRenderableWidget(questList);
        refreshQuestRows();

        // New-quest bar: id + title fields on a row above the footer.
        int idW = Math.max(60, Math.min(170, contentWidth() / 2));
        newIdField = new EditBox(this.font, contentLeft(), barY, idW,
                UiTheme.BUTTON_H, Component.literal("Quest id"));
        newIdField.setHint(Component.literal("storynpcs:quest_id"));
        newIdField.setMaxLength(64);
        addRenderableWidget(newIdField);

        int titleX = contentLeft() + idW + UiTheme.PAD_S;
        newTitleField = new EditBox(this.font, titleX, barY,
                Math.max(60, contentRight() - titleX - 80), UiTheme.BUTTON_H,
                Component.literal("Title"));
        newTitleField.setHint(Component.literal("Title (optional)"));
        newTitleField.setMaxLength(64);
        addRenderableWidget(newTitleField);

        addFooterAction(Component.literal("+ New"), b -> {
            String raw = newIdField.getValue().trim();
            NamespacedId id;
            try {
                id = NamespacedId.of(raw);
            } catch (Exception e) {
                echo(Component.literal("Malformed quest id: '" + raw + "'")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            if (model.beginNew(id, newTitleField.getValue().trim())) {
                newIdField.setValue("");
                rebuildWidgets();
            }
        });
        addFooterAction(Component.literal("Close"), b -> onClose());
        setStatus(Component.literal("Enter or double-click a quest to edit — new quests start below.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private void refreshQuestRows() {
        questList.setRows(model.getFilteredQuests());
    }

    // ── EDIT mode ───────────────────────────────────────────────────────────

    private void initEditMode() {
        Quest q = model.getEditing();
        int labelW = Math.min(64, contentWidth() / 5);
        int y = contentTop();

        if (model.isEditingNew()) {
            y = row(contentLeft(), y, contentWidth(), labelW, "ID",
                    "storynpcs:quest_id",
                    () -> q.getId() != null ? q.getId().toString() : "",
                    model::setQuestId,
                    FieldValidator.all(FieldValidator.required("quest id"),
                            FieldValidator.namespacedOrBare()));
        }

        // Title + Repeat on one row.
        int repeatW = Math.min(120, contentWidth() / 3);
        int nextY = row(contentLeft(), y, contentWidth() - repeatW - UiTheme.PAD_M, labelW,
                "Title", "quest title",
                () -> q.getTitle() != null ? q.getTitle() : "", model::setTitle,
                FieldValidator.required("title"));
        addRenderableWidget(cycleBtn(
                () -> "Repeat: " + (q.getRepeatType() != null ? q.getRepeatType()
                        : Quest.RepeatType.NORMAL),
                contentRight() - repeatW, y - 2, repeatW, () -> model.cycleRepeatType()));
        y = nextY;

        // Category + Description on one row.
        int catW = Math.min(170, contentWidth() / 3);
        nextY = row(contentLeft(), y, catW, labelW, "Category", "optional",
                () -> q.getCategory() != null ? q.getCategory() : "", model::setCategory, null);
        row(contentLeft() + catW + UiTheme.PAD_M, y,
                Math.max(60, contentRight() - contentLeft() - catW - UiTheme.PAD_M),
                Math.min(70, contentWidth() / 5), "Description", "optional",
                () -> q.getDescription() != null ? q.getDescription() : "",
                model::setDescription, null);
        y = nextY;

        // Union objectives+rewards list: headers unselectable, "+ add" rows
        // selectable-and-activatable, data rows activate into the row editor.
        rowList = new SelectableList<>(contentLeft(), y, contentWidth(),
                Math.max(1, contentBottom() - y - UiTheme.PAD_S), UiTheme.ROW_H,
                Row::key, r -> Component.literal(r.label()), rowSelection, rowScroll);
        rowList.setOnActivate(this::activateRow);
        addRenderableWidget(rowList);
        refreshRowList();

        addFooterAction(Component.literal("Save"), b -> save());
        addFooterAction(
                Component.literal(model.isDeleteArmed() ? "Sure?" : "Delete"), b -> {
                    Quest cur = model.getEditing();
                    if (cur == null || cur.getId() == null) return;
                    if (model.confirmDeleteClick()) {
                        long revision = model.expectedRevision();
                        pendingDeleteId = cur.getId().toString();
                        UUID requestId = deleteRequestId.forPayload(
                                "quest-delete\n" + cur.getId() + "\n" + revision);
                        PacketDistributor.sendToServer(new ServerboundQuestDeletePayload(
                                cur.getId().toString(), revision, requestId));
                        model.setStatus("Deleting...", false);
                    }
                    rebuildWidgets();
                    syncStatus();
                });
        addFooterAction(Component.literal(armedRow != null ? "Sure?" : "Remove row"), b -> {
            Row row = rowList.selectedRow();
            if (row == null || !isDataKey(row.key())) {
                if (armedRow != null) {
                    armedRow = null;
                    rebuildWidgets();
                }
                echo(Component.literal("Select an objective or reward row first.")
                        .withColor(UiTheme.TEXT_MUTED), 1600);
                return;
            }
            if (!row.key().equals(armedRow)) {
                armedRow = row.key();
                rebuildWidgets();
                return;
            }
            armedRow = null;
            if (row.key().startsWith("o")) {
                model.removeObjectiveRow(Integer.parseInt(row.key().substring(1)));
            } else {
                model.removeRewardRow(Integer.parseInt(row.key().substring(1)));
            }
            rebuildWidgets();
        });
        addFooterAction(Component.literal("Back"), b -> {
            model.backToList();
            rebuildWidgets();
        });
        setStatus(Component.literal("Select a row — Enter edits it; Remove row asks to confirm.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private boolean isDataKey(String key) {
        return key != null && (key.startsWith("o") || key.startsWith("r"))
                && !key.startsWith("add");
    }

    private void refreshRowList() {
        Quest q = model.getEditing();
        List<QuestObjective> objs = q.getObjectives();
        List<QuestReward> rews = q.getRewards();
        List<Row> rows = new ArrayList<>(objs.size() + rews.size() + 4);
        rows.add(new Row(null, "§bObjectives (" + objs.size() + ") §7— click a row to edit"));
        for (int i = 0; i < objs.size(); i++) {
            QuestObjective o = objs.get(i);
            rows.add(new Row("o" + i, "§f" + o.getType() + " §7" + o.getTarget()
                    + " §ex" + o.getRequiredCount()));
        }
        rows.add(new Row("add-o", "§a+ add objective"));
        rows.add(new Row(null, "§dRewards (" + rews.size() + ") §7— click a row to edit"));
        for (int i = 0; i < rews.size(); i++) {
            QuestReward r = rews.get(i);
            rows.add(new Row("r" + i, "§f" + r.getType() + " §7" + r.getTarget()
                    + " §ex" + r.getAmount()));
        }
        rows.add(new Row("add-r", "§a+ add reward"));
        rowList.setRows(rows);
    }

    private void activateRow(Row row) {
        String key = row.key();
        if ("add-o".equals(key)) {
            model.beginRowEdit(RowKind.OBJECTIVE, -1);
        } else if ("add-r".equals(key)) {
            model.beginRowEdit(RowKind.REWARD, -1);
        } else if (key != null && key.startsWith("o")) {
            model.beginRowEdit(RowKind.OBJECTIVE, Integer.parseInt(key.substring(1)));
        } else if (key != null && key.startsWith("r")) {
            model.beginRowEdit(RowKind.REWARD, Integer.parseInt(key.substring(1)));
        } else {
            return;
        }
        armedRow = null;
        rebuildWidgets();
    }

    private int row(int x, int y, int w, int labelW, String label, String hint,
                    java.util.function.Supplier<String> seed,
                    java.util.function.Consumer<String> responder, FieldValidator check) {
        FormRow formRow = new FormRow(this.font, Component.literal(label), Component.literal(hint));
        formRow.editBox().setMaxLength(256);
        formRow.setValue(seed.get());
        FieldValidator validator = check == null ? v -> null : check;
        formRow.setValidator(v -> { responder.accept(v); return validator.validate(v); });
        int nextY = formRow.layout(x, y, w, labelW);
        addRenderableWidget(formRow.editBox());
        fieldRows.add(formRow);
        return nextY;
    }

    // ── Row editor (add/edit objective|reward) ──────────────────────────────

    private void initRowEditor() {
        Quest q = model.getEditing();
        seedRowEditor(q);

        int w = Math.min(300, contentWidth());
        int x = contentLeft() + (contentWidth() - w) / 2;
        int labelW = Math.min(56, w / 4);
        int y = contentTop() + 14; // room for the heading line

        addRenderableWidget(cycleBtn(() -> "Type: " + rowType,
                x, y, w, () -> {
                    rowType = nextType(rowType, model.getRowKind());
                }));
        y += UiTheme.BUTTON_H + UiTheme.PAD_M;

        y = row(x, y, w, labelW, "Target", "e.g. minecraft:zombie",
                () -> rowTarget, v -> rowTarget = v,
                FieldValidator.required("target"));
        y = row(x, y, w / 2, labelW, "Count", "1+",
                () -> rowCount, v -> rowCount = v,
                v2 -> {
                    if (v2 == null || v2.isBlank()) return "Number required";
                    try {
                        return Integer.parseInt(v2.trim()) < 1 ? "Must be 1+" : null;
                    } catch (NumberFormatException e) {
                        return "Not a whole number";
                    }
                });

        boolean isObjective = model.getRowKind() == RowKind.OBJECTIVE;
        addFooterAction(Component.literal("OK"), b -> {
            boolean invalid = false;
            for (FormRow r : fieldRows) {
                if (!r.validate()) invalid = true;
            }
            if (invalid) {
                echo(Component.literal("Fix the highlighted field(s).")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            int count;
            try {
                count = Math.max(1, Integer.parseInt(rowCount.trim()));
            } catch (NumberFormatException e) {
                count = 1;
            }
            // Leaving the row editor: drafts must reseed on the next entry,
            // even for the same kind+index pair.
            rowEditorFor = -2;
            model.applyRowEdit(rowType, rowTarget, count);
            rebuildWidgets();
        });
        addFooterAction(Component.literal("Cancel"), b -> {
            rowEditorFor = -2;
            model.cancelRowEdit();
            rebuildWidgets();
        });
        setStatus(Component.literal(isObjective ? "Objective" : "Reward"
                        + " — type cycles the enum; target/count commit on OK.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private void seedRowEditor(Quest q) {
        int kindOrd = model.getRowKind().ordinal();
        int idx = model.getRowIndex();
        if (kindOrd == rowEditorFor && idx == rowEditorIdx) return;
        rowEditorFor = kindOrd;
        rowEditorIdx = idx;
        rowTarget = "";
        rowCount = "1";
        if (model.getRowKind() == RowKind.OBJECTIVE) {
            if (idx >= 0 && idx < q.getObjectives().size()) {
                QuestObjective o = q.getObjectives().get(idx);
                rowType = o.getType().name();
                rowTarget = o.getTarget() != null ? o.getTarget() : "";
                rowCount = String.valueOf(o.getRequiredCount());
            } else {
                rowType = QuestObjective.Type.KILL_ENTITY.name();
            }
        } else {
            if (idx >= 0 && idx < q.getRewards().size()) {
                QuestReward r = q.getRewards().get(idx);
                rowType = r.getType().name();
                rowTarget = r.getTarget() != null ? r.getTarget() : "";
                rowCount = String.valueOf(r.getAmount());
            } else {
                rowType = QuestReward.Type.ITEM.name();
            }
        }
    }

    private static String nextType(String current, RowKind kind) {
        String[] vals = kind == RowKind.OBJECTIVE
                ? java.util.Arrays.stream(QuestObjective.Type.values()).map(Enum::name).toArray(String[]::new)
                : java.util.Arrays.stream(QuestReward.Type.values()).map(Enum::name).toArray(String[]::new);
        int i = 0;
        for (int j = 0; j < vals.length; j++) if (vals[j].equals(current)) { i = j; break; }
        return vals[(i + 1) % vals.length];
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
        PacketDistributor.sendToServer(new ServerboundQuestSavePayload(
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
            List<Quest> quests = model.getFilteredQuests();
            if (quests.isEmpty()) {
                renderEmpty(g, model.questCount() > 0
                        ? "No quests match the filter."
                        : "No quests defined yet — create one below.");
            } else if (!model.getListFilter().isBlank()
                    && quests.size() < model.questCount()) {
                g.drawString(this.font, quests.size() + " of " + model.questCount(),
                        contentLeft(), contentTop() + 3, UiTheme.TEXT_MUTED);
            }
        } else if (model.getRowKind() != RowKind.NONE) {
            g.drawString(this.font, model.getRowKind() == RowKind.OBJECTIVE
                            ? "§bEdit Objective" : "§dEdit Reward",
                    contentLeft(), contentTop() + 2, UiTheme.ACCENT);
        }
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
