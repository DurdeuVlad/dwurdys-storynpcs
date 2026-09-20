package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.domain.quest.QuestSerde;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side state for {@link com.storynpcs.client.gui.QuestEditorScreen}.
 * Pure model — no Minecraft types — so the list/edit/row-edit flows are unit-testable.
 *
 * The screen owns the widget layout; every mutation lands here first so Save can
 * serialize the working copy and the dirty flag tracks unsaved changes.
 */
public final class QuestEditorScreenModel {

    public enum Mode { LIST, EDIT }

    /** Which list the row-editor modal is targeting. */
    public enum RowKind { NONE, OBJECTIVE, REWARD }

    private List<Quest> quests = new ArrayList<>();
    private Mode mode = Mode.LIST;

    private Quest editing;
    private boolean isNew;
    private boolean dirty;

    private String statusMessage = "";
    private boolean statusError;

    private int listScroll;
    private String listFilter = "";

    // Row-editor modal state
    private RowKind rowKind = RowKind.NONE;
    private int rowIndex = -1;

    // Delete two-click confirm
    private boolean deleteArmed;

    public void loadQuests(List<Quest> loaded) {
        this.quests = new ArrayList<>(loaded != null ? loaded : List.of());
        this.quests.sort((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())));
    }

    public List<Quest> getQuests() { return List.copyOf(quests); }
    public int questCount() { return quests.size(); }

    /** Substring filter text for the list view (issue #21). */
    public String getListFilter() { return listFilter; }
    public void setListFilter(String f) {
        this.listFilter = f != null ? f : "";
        this.listScroll = 0;
    }

    /** Quests matching the filter — case-insensitive substring on id or title. */
    public List<Quest> getFilteredQuests() {
        String needle = listFilter.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return getQuests();
        List<Quest> out = new ArrayList<>();
        for (Quest q : quests) {
            String id = q.getId() != null ? q.getId().toString() : "";
            String title = q.getTitle() != null ? q.getTitle() : "";
            if (id.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || title.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                out.add(q);
            }
        }
        return out;
    }

    public Mode getMode() { return mode; }
    public boolean isEditingNew() { return isNew; }
    public Quest getEditing() { return editing; }
    public boolean isDirty() { return dirty; }
    public RowKind getRowKind() { return rowKind; }
    public int getRowIndex() { return rowIndex; }
    public boolean isDeleteArmed() { return deleteArmed; }

    public int getListScroll() { return listScroll; }
    public void setListScroll(int scroll) {
        this.listScroll = Math.max(0, Math.min(scroll, Math.max(0, quests.size() - 1)));
    }

    public String getStatusMessage() { return statusMessage; }
    public boolean isStatusError() { return statusError; }

    public void setStatus(String message, boolean isError) {
        this.statusMessage = message != null ? message : "";
        this.statusError = isError;
    }

    /** LIST → EDIT for an existing quest. */
    public boolean beginEdit(NamespacedId id) {
        Quest found = null;
        for (Quest q : quests) {
            if (q.getId() != null && q.getId().equals(id)) { found = q; break; }
        }
        if (found == null) {
            setStatus("Quest not found: " + id, true);
            return false;
        }
        // Deserialize a working copy so Cancel leaves the synced snapshot untouched.
        this.editing = QuestSerde.fromJson(QuestSerde.toJson(found)).orElse(found);
        this.isNew = false;
        this.dirty = false;
        this.deleteArmed = false;
        this.rowKind = RowKind.NONE;
        this.mode = Mode.EDIT;
        setStatus("", false);
        return true;
    }

    /**
     * LIST → EDIT for a brand-new quest. Mirrors {@code createQuest}: scaffolds one
     * placeholder CUSTOM objective because the cross-reference validator requires
     * at least one objective per quest.
     */
    public boolean beginNew(NamespacedId id, String title) {
        if (id == null) {
            setStatus("Enter a quest id first (e.g. storynpcs:my_quest).", true);
            return false;
        }
        for (Quest q : quests) {
            if (id.equals(q.getId())) {
                setStatus("Quest '" + id + "' already exists — pick another id or edit it from the list.", true);
                return false;
            }
        }
        Quest q = new Quest(id, title != null && !title.isBlank() ? title.trim() : id.getPath());
        q.getObjectives().add(new QuestObjective("objective_1",
                QuestObjective.Type.CUSTOM, "describe_the_objective", 1));
        this.editing = q;
        this.isNew = true;
        this.dirty = true;
        this.deleteArmed = false;
        this.rowKind = RowKind.NONE;
        this.mode = Mode.EDIT;
        setStatus("", false);
        return true;
    }

    /** Back to the list; discards unsaved edits. */
    public void backToList() {
        this.mode = Mode.LIST;
        this.editing = null;
        this.rowKind = RowKind.NONE;
        this.deleteArmed = false;
        setStatus("", false);
    }

    private void touch() { dirty = true; }

    public void setTitle(String v) { if (editing != null) { editing.setTitle(v); touch(); } }

    /** New-quest id editing — rejects ids that already exist in the synced list. */
    public void setQuestId(String raw) {
        if (editing == null || !isNew) return;
        String v = raw != null ? raw.trim() : "";
        try {
            NamespacedId id = NamespacedId.of(v);
            editing.setId(id);
            touch();
        } catch (Exception ignored) { }
    }
    public void setDescription(String v) { if (editing != null) { editing.setDescription(v); touch(); } }
    public void setCategory(String v) { if (editing != null) { editing.setCategory(v); touch(); } }
    public void setRepeatType(Quest.RepeatType v) { if (editing != null) { editing.setRepeatType(v); touch(); } }

    /** Cycles the repeat type; returns the new value for button label updates. */
    public Quest.RepeatType cycleRepeatType() {
        if (editing == null) return null;
        Quest.RepeatType[] vals = Quest.RepeatType.values();
        Quest.RepeatType cur = editing.getRepeatType() != null ? editing.getRepeatType() : Quest.RepeatType.ONCE;
        Quest.RepeatType next = vals[(cur.ordinal() + 1) % vals.length];
        editing.setRepeatType(next);
        touch();
        return next;
    }

    /** Opens the row-editor modal for a new or existing row. index=-1 → new row. */
    public void beginRowEdit(RowKind kind, int index) {
        if (editing == null) return;
        this.rowKind = kind;
        this.rowIndex = index;
    }

    public void cancelRowEdit() {
        this.rowKind = RowKind.NONE;
        this.rowIndex = -1;
    }

    /**
     * Applies the row-editor modal values. For objectives, the row id is preserved
     * when editing and auto-generated (objective_N) when adding — matching the
     * command-side behavior in {@code addQuestObjective}.
     */
    public void applyRowEdit(String typeRaw, String target, int count) {
        if (editing == null || rowKind == RowKind.NONE) return;
        String t = target != null ? target.trim() : "";
        if (t.isEmpty()) {
            setStatus("Target must not be blank.", true);
            return;
        }
        if (rowKind == RowKind.OBJECTIVE) {
            QuestObjective.Type type;
            try {
                type = QuestObjective.Type.valueOf(typeRaw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                setStatus("Invalid objective type '" + typeRaw + "'.", true);
                return;
            }
            if (rowIndex >= 0 && rowIndex < editing.getObjectives().size()) {
                QuestObjective o = editing.getObjectives().get(rowIndex);
                o.setType(type);
                o.setTarget(t);
                o.setRequiredCount(count);
            } else {
                editing.getObjectives().add(new QuestObjective(nextObjectiveId(), type, t, count));
            }
        } else {
            QuestReward.Type type;
            try {
                type = QuestReward.Type.valueOf(typeRaw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                setStatus("Invalid reward type '" + typeRaw + "'.", true);
                return;
            }
            if (rowIndex >= 0 && rowIndex < editing.getRewards().size()) {
                QuestReward r = editing.getRewards().get(rowIndex);
                r.setType(type);
                r.setTarget(t);
                r.setAmount(count);
            } else {
                editing.getRewards().add(new QuestReward(type, t, count));
            }
        }
        cancelRowEdit();
        touch();
        setStatus("", false);
    }

    private String nextObjectiveId() {
        int n = 1;
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (QuestObjective o : editing.getObjectives()) existing.add(o.getId());
        while (existing.contains("objective_" + n)) n++;
        return "objective_" + n;
    }

    public void removeObjectiveRow(int index) {
        if (editing != null && index >= 0 && index < editing.getObjectives().size()) {
            editing.getObjectives().remove(index);
            touch();
        }
    }

    public void removeRewardRow(int index) {
        if (editing != null && index >= 0 && index < editing.getRewards().size()) {
            editing.getRewards().remove(index);
            touch();
        }
    }

    /**
     * Client-side pre-flight before sending the save packet — mirrors the
     * validator's hard requirements so obvious mistakes fail fast without a round-trip.
     * Returns an error string, or null if the working copy is saveable.
     */
    public String validateForSave() {
        if (editing == null) return "Nothing to save.";
        if (editing.getId() == null) return "Quest needs an id.";
        if (isNew && quests.stream().anyMatch(q -> editing.getId().equals(q.getId()))) {
            return "Quest '" + editing.getId() + "' already exists.";
        }
        if (editing.getTitle() == null || editing.getTitle().isBlank()) return "Quest needs a title.";
        if (editing.getObjectives().isEmpty()) return "Quest needs at least one objective.";
        return null;
    }

    /** Serializes the working copy for the save payload. Null when not editing. */
    public String saveJson() {
        return editing != null ? QuestSerde.toJson(editing) : null;
    }

    public void markSaved() { dirty = false; }

    /** Two-click delete: first call arms, second returns true. */
    public boolean confirmDeleteClick() {
        if (!deleteArmed) {
            deleteArmed = true;
            return false;
        }
        deleteArmed = false;
        return true;
    }

    /** Applies a server result: refresh the synced list, clear dirty on success. */
    public void onSaveResult(boolean success, String message, List<Quest> refreshed) {
        if (refreshed != null) {
            loadQuests(refreshed);
        }
        setStatus(message, !success);
        if (success) {
            markSaved();
            // If the quest we were editing was deleted, drop back to the list.
            if (editing != null) {
                boolean stillThere = quests.stream().anyMatch(q -> editing.getId().equals(q.getId()));
                if (!stillThere) {
                    backToList();
                    setStatus(message, false);
                }
            }
        }
    }
}
