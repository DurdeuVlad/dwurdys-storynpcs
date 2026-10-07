package com.storynpcs.client.gui;

import com.storynpcs.domain.support.NbtBookService;
import com.storynpcs.network.ServerboundNbtBookEditPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * NBT book viewer/editor screen (issue #148). Renders the flattened NBT view
 * the server computed; editable rows (the allowlisted keys) highlight on
 * hover and load the bottom edit box. Enter/Apply sends a
 * {@link ServerboundNbtBookEditPayload} — the server re-validates permission,
 * session, and allowlist, then resends a fresh view.
 */
public class NbtBookScreen extends Screen {

    private static final int ROW_H = 11;
    private static final int COLOR_PATH = 0xFFA1A1AA;
    private static final int COLOR_VALUE = 0xFFFFFFFF;
    private static final int COLOR_EDITABLE = 0xFF7DD3FC;
    private static final int COLOR_TITLE = 0xFFFDE68A;

    private final int entityId;
    private final String displayName;
    private final boolean canEdit;
    private final UUID sessionId;
    private List<NbtBookService.NbtEntry> entries;

    private int scroll;
    private int editIndex = -1;
    private EditBox editField;
    private Button applyButton;
    private String statusMessage = "";

    public NbtBookScreen(int entityId, String displayName, String entriesJson,
                         boolean canEdit, UUID sessionId) {
        super(Component.literal("NBT Book"));
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
        this.scroll = Math.min(this.scroll, Math.max(0, entries.size() - visibleRows()));
        if (this.editIndex >= entries.size()) {
            this.editIndex = -1;
            this.editField.setVisible(false);
            this.applyButton.visible = false;
        }
        this.statusMessage = "§aView refreshed.";
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new com.storynpcs.network.ServerboundToolSessionClosePayload(
                com.storynpcs.item.NbtBookItem.SESSION_KIND, sessionId));
        super.onClose();
    }

    @Override
    protected void init() {
        int bottomY = this.height - 28;
        this.editField = new EditBox(this.font, this.width / 2 - 160, bottomY, 240, 18,
                Component.literal("edit value"));
        this.editField.setVisible(false);
        this.editField.setMaxLength(256);
        this.editField.setResponder(value -> { });
        this.addRenderableWidget(this.editField);
        this.applyButton = Button.builder(Component.literal("Apply"), b -> applyEdit())
                .bounds(this.width / 2 + 84, bottomY - 1, 76, 20).build();
        this.applyButton.visible = false;
        this.addRenderableWidget(this.applyButton);
    }

    private void applyEdit() {
        if (editIndex < 0 || editIndex >= entries.size()) {
            return;
        }
        var entry = entries.get(editIndex);
        PacketDistributor.sendToServer(new ServerboundNbtBookEditPayload(
                sessionId, entityId, entry.path(), this.editField.getValue()));
        statusMessage = "§7Sent " + entry.path() + " edit…";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (canEdit && button == 0) {
            int idx = rowAt(mouseY);
            if (idx >= 0 && idx < entries.size() && entries.get(idx).editable()
                    && mouseX >= this.width / 2 - 170 && mouseX <= this.width / 2 + 170) {
                editIndex = idx;
                this.editField.setValue(stripQuotes(entries.get(idx).value()));
                this.editField.setVisible(true);
                this.applyButton.visible = true;
                this.setFocused(this.editField);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, entries.size() - visibleRows());
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY) * 3));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.editField.isVisible() && keyCode == 257) { // Enter
            applyEdit();
            return true;
        }
        // ESC must still close the screen while the edit box holds focus —
        // EditBox.keyPressed would swallow it otherwise.
        if (this.editField.isFocused() && keyCode != 256) {
            return this.editField.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int visibleRows() {
        return (this.height - 78) / ROW_H;
    }

    private int rowAt(double mouseY) {
        int top = 40;
        if (mouseY < top || mouseY >= top + visibleRows() * ROW_H) {
            return -1;
        }
        return scroll + (int) ((mouseY - top) / ROW_H);
    }

    private static String stripQuotes(String raw) {
        return raw != null && raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
                ? raw.substring(1, raw.length() - 1) : raw;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawString(this.font, "§6NBT Book — " + displayName, cx - 170, 12, COLOR_TITLE);
        graphics.drawString(this.font, canEdit
                ? "§7Click a highlighted row to edit · Enter applies"
                : "§7Read-only view (edits require operator level 2)", cx - 170, 24, COLOR_PATH);

        int top = 40;
        int bottom = top + visibleRows() * ROW_H;
        graphics.enableScissor(0, top, this.width, bottom);
        for (int i = scroll; i < entries.size() && top < bottom; i++, top += ROW_H) {
            var entry = entries.get(i);
            boolean hovered = canEdit && entry.editable() && rowAt(mouseY) == i;
            int pathColor = entry.editable() ? COLOR_EDITABLE : COLOR_PATH;
            String line = entry.path() + " = " + entry.value();
            if (hovered) {
                graphics.fill(cx - 172, top - 1, cx + 172, top + ROW_H - 1, 0x33415566);
            }
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(line, 344), cx - 170, top + 1, pathColor);
        }
        graphics.disableScissor();

        if (!statusMessage.isEmpty()) {
            graphics.drawString(this.font, statusMessage, cx - 170, this.height - 42, COLOR_PATH);
        }
        // Render widgets directly — super.render() would re-run the blur
        // background pass and smear the rows drawn above (#197).
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
