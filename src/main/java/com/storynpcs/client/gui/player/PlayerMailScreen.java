package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.PendingAck;
import com.storynpcs.client.ui.RowKeys;
import com.storynpcs.domain.panel.PlayerPanels.MailRow;
import com.storynpcs.domain.panel.PlayerPanels.MailView;
import com.storynpcs.network.ServerboundMailActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Player-facing mailbox (issue #150, GuiMailbox parity): lists the durable
 * mailbox (newest first), reads a selected message, marks read / deletes, and
 * composes mail to another player. Every commit goes through
 * {@link ServerboundMailActionPayload} with the panel session id — the server
 * re-authorizes and refreshes the view on apply.
 *
 * <p>Refresh contract (issue #201): the view is a mutable slot — a re-issued
 * mail payload calls {@link #updateView} in place instead of replacing the
 * screen, preserving scroll and selection. Selection is by row
 * {@code id}, so it survives inserts/removals; serverbound actions arm a
 * {@link PendingAck} that disables the action buttons until the refresh
 * (or a timeout) arrives — no silent no-ops.
 */
public class PlayerMailScreen extends Screen {

    private static final int STATUS_MS = 3_000;

    private MailView view;
    private final UUID sessionId;
    private int scrollOffset = 0;
    private String selectedId;
    private boolean composing = false;
    private EditBox recipientBox;
    private EditBox subjectBox;
    private EditBox bodyBox;
    private final PendingAck pending = new PendingAck();
    private boolean wasPending;
    private String statusMessage;
    private long statusUntilMs;

    public PlayerMailScreen(MailView view, UUID sessionId) {
        super(Component.literal("Mailbox"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    /** Identity check for the refresh contract — this payload is for this session. */
    public boolean matches(UUID sessionId) {
        return Objects.equals(this.sessionId, sessionId);
    }

    /**
     * Replaces the view in place on a server-issued refresh: acknowledges any
     * pending action, re-resolves the selection by row id, and clamps the
     * scroll offset to the new list size.
     */
    public void updateView(MailView view) {
        if (view == null) return;
        this.view = view;
        pending.ack();
        wasPending = false; // acked — tick() must not report this as an expiry
        int sel = selectedIndex();
        if (sel < 0) {
            selectedId = null;
        } else {
            // Keep the selection visually reachable after size changes.
            scrollOffset = Math.min(scrollOffset, sel);
            if (sel >= scrollOffset + maxVisible()) {
                scrollOffset = sel - maxVisible() + 1;
            }
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset,
                Math.max(0, rows().size() - maxVisible())));
        if (!composing) {
            rebuildWidgets();
        }
    }

    private List<MailRow> rows() {
        return view.messages() == null ? List.of() : view.messages();
    }
    private int listTop() { return 34; }
    private int listBottom() { return this.height - 96; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 12); }

    /** Index of the selected row in the current view, or -1. */
    private int selectedIndex() {
        return RowKeys.indexOf(rows(), MailRow::id, selectedId);
    }

    private boolean isPending() {
        return pending.pending(System.currentTimeMillis());
    }

    private void echo(String message) {
        statusMessage = message;
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    @Override
    public void tick() {
        super.tick();
        // Pending expiry re-enables the action buttons — without this a
        // chat-only server reject would leave them grayed forever.
        boolean nowPending = isPending();
        if (wasPending && !nowPending) {
            wasPending = false;
            echo("No response from server — try again.");
            rebuildWidgets();
        } else if (nowPending) {
            wasPending = true;
        }
    }

    private String activeStatus() {
        String pendingLabel = pending.label(System.currentTimeMillis());
        if (pendingLabel != null) return pendingLabel;
        if (statusMessage != null && System.currentTimeMillis() < statusUntilMs) {
            return statusMessage;
        }
        return null;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        if (composing) {
            initCompose(cx);
            return;
        }
        if (rows().size() > maxVisible()) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> scrollOffset = Math.max(0, scrollOffset - 1))
                    .bounds(cx + 176, listTop(), 14, 12).build());
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> scrollOffset = Math.min(rows().size() - maxVisible(), scrollOffset + 1))
                    .bounds(cx + 176, listBottom() - 12, 14, 12).build());
        }
        int by = this.height - 92;
        Button readBtn = Button.builder(Component.literal("Read"),
                        b -> { if (selectedIndex() >= 0) markRead(); else echo("Select a message first."); })
                .bounds(cx - 190, by, 60, 14).build();
        Button deleteBtn = Button.builder(Component.literal("Delete"),
                        b -> { if (selectedIndex() >= 0) deleteSelected(); else echo("Select a message first."); })
                .bounds(cx - 126, by, 60, 14).build();
        if (isPending()) {
            readBtn.active = false;
            deleteBtn.active = false;
        }
        addRenderableWidget(readBtn);
        addRenderableWidget(deleteBtn);
        addRenderableWidget(Button.builder(Component.literal("Compose"),
                        b -> { composing = true; rebuildWidgets(); })
                .bounds(cx - 62, by, 60, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Close"),
                        b -> onClose())
                .bounds(cx - 40, this.height - 22, 80, 14).build());
    }

    private void initCompose(int cx) {
        recipientBox = new EditBox(this.font, cx - 190, 40, 180, 14, Component.literal("To"));
        recipientBox.setHint(Component.literal("Player name"));
        recipientBox.setMaxLength(64);
        subjectBox = new EditBox(this.font, cx - 190, 58, 180, 14, Component.literal("Subject"));
        subjectBox.setHint(Component.literal("Subject"));
        subjectBox.setMaxLength(128);
        bodyBox = new EditBox(this.font, cx - 190, 76, 380, 14, Component.literal("Message"));
        bodyBox.setHint(Component.literal("Message body"));
        bodyBox.setMaxLength(2048);
        addRenderableWidget(recipientBox);
        addRenderableWidget(subjectBox);
        addRenderableWidget(bodyBox);
        Button sendBtn = Button.builder(Component.literal("Send"), b -> sendMail())
                .bounds(cx - 190, 94, 60, 14).build();
        sendBtn.active = !isPending();
        addRenderableWidget(sendBtn);
        addRenderableWidget(Button.builder(Component.literal("Back"),
                        b -> { composing = false; rebuildWidgets(); })
                .bounds(cx - 126, 94, 60, 14).build());
    }

    private void markRead() {
        int sel = selectedIndex();
        if (sel < 0) return;
        var row = rows().get(sel);
        if (row == null || row.id() == null) return;
        pending.begin("Marking read…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, ServerboundMailActionPayload.ACTION_MARK_READ, row.id()));
        rebuildWidgets();
    }

    private void deleteSelected() {
        int sel = selectedIndex();
        if (sel < 0) return;
        var row = rows().get(sel);
        if (row == null || row.id() == null) return;
        pending.begin("Deleting…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, ServerboundMailActionPayload.ACTION_DELETE, row.id()));
        selectedId = null;
        rebuildWidgets();
    }

    private void sendMail() {
        if (recipientBox.getValue().trim().isEmpty()) {
            echo("Recipient name is required.");
            rebuildWidgets();
            return;
        }
        pending.begin("Sending…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, UUID.randomUUID(), ServerboundMailActionPayload.ACTION_SEND,
                recipientBox.getValue().trim(), subjectBox.getValue(), bodyBox.getValue()));
        composing = false;
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font,
                composing ? "Compose Mail" : "Mailbox", cx, 10, 0xFFFFFF);
        if (composing) {
            renderStatus(graphics, cx);
            return;
        }
        graphics.drawCenteredString(this.font,
                "§7" + rows().size() + " message(s)", cx, 22, 0xAAAAAA);

        var rows = rows();
        int sel = selectedIndex();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            MailRow row = rows.get(i);
            if (row == null) { y += 12; continue; }
            String prefix = row.read() ? "§7" : "§f";
            String line = prefix + (i == sel ? "> " : "") + row.sender()
                    + " §8— " + row.subject();
            graphics.drawString(this.font, line, cx - 190, y, 0xFFFFFF);
            y += 12;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "§7Your mailbox is empty.",
                    cx, y + 8, 0xAAAAAA);
        }

        // Reading pane for the selected message.
        if (sel >= 0 && sel < rows.size()) {
            var row = rows.get(sel);
            if (row != null) {
                int ry = listBottom() + 6;
                graphics.drawString(this.font, "§9From: §f" + row.sender()
                        + "   §9Subject: §f" + row.subject(), cx - 190, ry, 0xFFFFFF);
                ry += 11;
                int bodyFloor = activeStatus() != null ? this.height - 52 : this.height - 30;
                for (String line : wrap(String.valueOf(row.body()), 62)) {
                    graphics.drawString(this.font, "§7" + line, cx - 190, ry, 0xCCCCCC);
                    ry += 10;
                    if (ry > bodyFloor) break;
                }
            }
        }
        renderStatus(graphics, cx);
    }

    private void renderStatus(GuiGraphics graphics, int cx) {
        String status = activeStatus();
        if (status != null) {
            graphics.drawCenteredString(this.font, "§e" + status,
                    cx, this.height - 40, 0xFFCC00);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!composing && button == 0) {
            int cx = this.width / 2;
            if (mouseX >= cx - 190 && mouseX <= cx + 170
                    && mouseY >= listTop() && mouseY <= listBottom()) {
                int index = scrollOffset + (int) ((mouseY - listTop()) / 12);
                if (index >= 0 && index < rows().size()) {
                    MailRow row = rows().get(index);
                    selectedId = row != null ? row.id() : null;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static List<String> wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() + word.length() + 1 > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder();
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
