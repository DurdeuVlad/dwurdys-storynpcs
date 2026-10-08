package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.domain.support.NbtBookService;
import com.storynpcs.network.ServerboundNbtBookEditPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/**
 * NBT book viewer/editor screen (issue #148). Renders the flattened NBT view
 * the server computed; editable rows (the allowlisted keys) are accent-tinted,
 * load the bottom edit box on select, and Enter/Apply sends a
 * {@link ServerboundNbtBookEditPayload} — the server re-validates permission,
 * session, and allowlist, then resends a fresh view.
 *
 * <p>Migrated onto the shared chrome (#208): the row list is a
 * {@link SelectableList} keyed by entry path — the editable/locked distinction
 * the old hand-rolled highlight encoded is now a muted-vs-accent label color
 * plus the standard selection band. The edit strip still sits above the
 * footer; read-only users get a view without it.
 */
public class NbtBookScreen extends UiScreen {

    private final int entityId;
    private final String displayName;
    private final boolean canEdit;
    private final UUID sessionId;
    private List<NbtBookService.NbtEntry> entries;

    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();
    private SelectableList<NbtBookService.NbtEntry, String> entryList;
    private EditBox editField;
    /** Path loaded into the edit strip — null keeps the strip hidden. */
    private String editingPath;

    public NbtBookScreen(int entityId, String displayName, String entriesJson,
                         boolean canEdit, UUID sessionId) {
        super(Component.literal("NBT Book — " + displayName));
        this.entityId = entityId;
        this.displayName = displayName;
        this.canEdit = canEdit;
        this.sessionId = sessionId;
        this.entries = NbtBookService.entriesFromJson(entriesJson);
    }

    /** True when this screen is the live view for the same entity session. */
    public boolean matches(int entityId, UUID sessionId) {
        return this.entityId == entityId && this.sessionId.equals(sessionId);
    }

    /** Refresh path: the server resent the view after an applied edit. */
    public void updateEntries(String entriesJson) {
        this.entries = NbtBookService.entriesFromJson(entriesJson);
        // Drop the edit strip only if the path vanished — selection keys are
        // path-stable, so the list keeps the user's place across the refresh.
        if (editingPath != null
                && entries.stream().noneMatch(e -> e.path().equals(editingPath))) {
            editingPath = null;
        }
        refreshRows();
        echo(Component.literal("View refreshed.").withColor(UiTheme.ACCENT), 2000);
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new com.storynpcs.network.ServerboundToolSessionClosePayload(
                com.storynpcs.item.NbtBookItem.SESSION_KIND, sessionId));
        super.onClose();
    }

    @Override
    protected void initContent() {
        int editY = contentBottom() - UiTheme.BUTTON_H;
        int listH = Math.max(1, (canEdit ? editY - UiTheme.PAD_S : contentBottom())
                - contentTop());
        entryList = new SelectableList<>(contentLeft(), contentTop(), contentWidth(),
                listH, UiTheme.ROW_H, NbtBookService.NbtEntry::path,
                this::entryLabel, selection, scroll);
        entryList.setOnSelect(e -> {
            if (canEdit && e.editable()) {
                editingPath = e.path();
                if (editField != null) {
                    editField.setValue(stripQuotes(e.value()));
                }
                rebuildWidgets();
            }
        });
        addRenderableWidget(entryList);

        if (canEdit) {
            editField = new EditBox(this.font, contentLeft(), editY,
                    Math.max(60, contentWidth() - 84), UiTheme.BUTTON_H,
                    Component.literal("edit value"));
            editField.setMaxLength(256);
            editField.setHint(Component.literal("select a highlighted row"));
            editField.setVisible(editingPath != null);
            if (editingPath != null) {
                editField.setValue(currentValueOf(editingPath));
            }
            addRenderableWidget(editField);

            addFooterAction(Component.literal("Apply"), b -> applyEdit());
        }
        addFooterAction(Component.literal("Close"), b -> onClose());
        setStatus(Component.literal(canEdit
                        ? "Click a highlighted row to edit — Enter applies."
                        : "Read-only view (edits require operator level 2).")
                .withColor(UiTheme.TEXT_MUTED));
        refreshRows();
    }

    private void refreshRows() {
        if (entryList != null) {
            entryList.setRows(entries);
        }
    }

    private String currentValueOf(String path) {
        for (var e : entries) {
            if (e.path().equals(path)) return stripQuotes(e.value());
        }
        return "";
    }

    /** Editable rows read accent-tinted; locked rows stay muted. */
    private Component entryLabel(NbtBookService.NbtEntry e) {
        String line = (e.editable() ? "§b" : "§7") + e.path() + " §8= §f" + e.value();
        return Component.literal(line);
    }

    private void applyEdit() {
        if (editingPath == null || editField == null) return;
        PacketDistributor.sendToServer(new ServerboundNbtBookEditPayload(
                sessionId, entityId, editingPath, this.editField.getValue()));
        echo(Component.literal("Sent " + editingPath + " edit…")
                .withColor(UiTheme.TEXT_MUTED), 2000);
    }

    private static String stripQuotes(String raw) {
        return raw != null && raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
                ? raw.substring(1, raw.length() - 1) : raw;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (entries.isEmpty()) {
            renderEmpty(g, "No NBT data for this entity.");
        }
    }

    /** Enter applies while the edit box holds focus; Esc still closes. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editField != null && editField.isVisible() && editField.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            applyEdit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
