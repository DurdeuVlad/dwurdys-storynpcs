package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.faction.FactionSerde;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side state for {@link com.storynpcs.client.gui.FactionEditorScreen}.
 * Pure model — no Minecraft types — so list/edit/delete flows are unit-testable.
 * Mirrors {@link QuestEditorScreenModel}; threshold consistency (hostile &lt;
 * friendly) is validated client-side for instant feedback and again server-side
 * by {@code saveFaction} (single canonical rule).
 */
public final class FactionEditorScreenModel {

    public enum Mode { LIST, EDIT }

    private List<Faction> factions = new ArrayList<>();
    private Mode mode = Mode.LIST;

    private Faction editing;
    private boolean isNew;
    private boolean dirty;

    private String statusMessage = "";
    private boolean statusError;

    private int listScroll;
    private String listFilter = "";
    private boolean deleteArmed;

    public void loadFactions(List<Faction> loaded) {
        this.factions = new ArrayList<>(loaded != null ? loaded : List.of());
        this.factions.sort((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())));
    }

    public List<Faction> getFactions() { return List.copyOf(factions); }
    public int factionCount() { return factions.size(); }

    /** Substring filter text for the list view (issue #21). */
    public String getListFilter() { return listFilter; }
    public void setListFilter(String f) {
        this.listFilter = f != null ? f : "";
        this.listScroll = 0;
    }

    /** Factions matching the filter — case-insensitive substring on id or name. */
    public List<Faction> getFilteredFactions() {
        String needle = listFilter.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return getFactions();
        List<Faction> out = new ArrayList<>();
        for (Faction f : factions) {
            String id = f.getId() != null ? f.getId().toString() : "";
            String name = f.getName() != null ? f.getName() : "";
            if (id.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || name.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                out.add(f);
            }
        }
        return out;
    }

    public Mode getMode() { return mode; }
    public boolean isEditingNew() { return isNew; }
    public Faction getEditing() { return editing; }
    public boolean isDirty() { return dirty; }
    public boolean isDeleteArmed() { return deleteArmed; }

    public int getListScroll() { return listScroll; }
    public void setListScroll(int scroll) {
        this.listScroll = Math.max(0, Math.min(scroll, Math.max(0, factions.size() - 1)));
    }

    public String getStatusMessage() { return statusMessage; }
    public boolean isStatusError() { return statusError; }

    public void setStatus(String message, boolean isError) {
        this.statusMessage = message != null ? message : "";
        this.statusError = isError;
    }

    public boolean beginEdit(NamespacedId id) {
        Faction found = null;
        for (Faction f : factions) {
            if (f.getId() != null && f.getId().equals(id)) { found = f; break; }
        }
        if (found == null) {
            setStatus("Faction not found: " + id, true);
            return false;
        }
        this.editing = FactionSerde.fromJson(FactionSerde.toJson(found)).orElse(found);
        this.isNew = false;
        this.dirty = false;
        this.deleteArmed = false;
        this.mode = Mode.EDIT;
        setStatus("", false);
        return true;
    }

    public boolean beginNew(NamespacedId id, String name) {
        if (id == null) {
            setStatus("Enter a faction id first (e.g. storynpcs:my_faction).", true);
            return false;
        }
        for (Faction f : factions) {
            if (id.equals(f.getId())) {
                setStatus("Faction '" + id + "' already exists — pick another id or edit it from the list.", true);
                return false;
            }
        }
        this.editing = new Faction(id, name != null && !name.isBlank() ? name.trim() : id.getPath(),
                1000, 500, 1500);
        this.isNew = true;
        this.dirty = true;
        this.deleteArmed = false;
        this.mode = Mode.EDIT;
        setStatus("", false);
        return true;
    }

    public void backToList() {
        this.mode = Mode.LIST;
        this.editing = null;
        this.deleteArmed = false;
        setStatus("", false);
    }

    private void touch() { dirty = true; }

    public void setName(String v) { if (editing != null) { editing.setName(v); touch(); } }
    public void setDefaultPoints(int v) { if (editing != null) { editing.setDefaultPoints(v); touch(); } }
    public void setHostileThreshold(int v) { if (editing != null) { editing.setHostileThreshold(v); touch(); } }
    public void setFriendlyThreshold(int v) { if (editing != null) { editing.setFriendlyThreshold(v); touch(); } }

    /** New-faction id editing — rejects ids that already exist in the synced list. */
    public void setFactionId(String raw) {
        if (editing == null || !isNew) return;
        String v = raw != null ? raw.trim() : "";
        try {
            editing.setId(NamespacedId.of(v));
            touch();
        } catch (Exception ignored) { }
    }

    /**
     * Client-side pre-flight — mirrors the service's hard requirements so obvious
     * mistakes fail inline without a round-trip. Returns an error string or null.
     */
    public String validateForSave() {
        if (editing == null) return "Nothing to save.";
        if (editing.getId() == null) return "Faction needs an id.";
        if (isNew && factions.stream().anyMatch(f -> editing.getId().equals(f.getId()))) {
            return "Faction '" + editing.getId() + "' already exists.";
        }
        if (editing.getName() == null || editing.getName().isBlank()) return "Faction needs a name.";
        if (editing.getHostileThreshold() >= editing.getFriendlyThreshold()) {
            return "Hostile threshold (" + editing.getHostileThreshold()
                    + ") must be below friendly threshold (" + editing.getFriendlyThreshold() + ").";
        }
        return null;
    }

    public String saveJson() {
        return editing != null ? FactionSerde.toJson(editing) : null;
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

    public void onSaveResult(boolean success, String message, List<Faction> refreshed) {
        if (refreshed != null) {
            loadFactions(refreshed);
        }
        setStatus(message, !success);
        if (success) {
            markSaved();
            if (editing != null) {
                boolean stillThere = factions.stream().anyMatch(f -> editing.getId().equals(f.getId()));
                if (!stillThere) {
                    backToList();
                    setStatus(message, false);
                }
            }
        }
    }
}
