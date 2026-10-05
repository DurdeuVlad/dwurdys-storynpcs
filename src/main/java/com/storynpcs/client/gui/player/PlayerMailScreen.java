package com.storynpcs.client.gui.player;

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
import java.util.UUID;

/**
 * Player-facing mailbox (issue #150, GuiMailbox parity): lists the durable
 * mailbox (newest first), reads a selected message, marks read / deletes, and
 * composes mail to another player. Every commit goes through
 * {@link ServerboundMailActionPayload} with the panel session id — the server
 * re-authorizes and refreshes the view on apply.
 */
public class PlayerMailScreen extends Screen {

    private final MailView view;
    private final UUID sessionId;
    private int scrollOffset = 0;
    private int selected = -1;
    private boolean composing = false;
    private EditBox recipientBox;
    private EditBox subjectBox;
    private EditBox bodyBox;

    public PlayerMailScreen(MailView view, UUID sessionId) {
        super(Component.literal("Mailbox"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<MailRow> rows() {
        return view.messages() == null ? List.of() : view.messages();
    }
    private int listTop() { return 34; }
    private int listBottom() { return this.height - 96; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 12); }

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
        addRenderableWidget(Button.builder(Component.literal("Read"),
                        b -> { if (selected >= 0) markRead(); })
                .bounds(cx - 190, by, 60, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"),
                        b -> { if (selected >= 0) deleteSelected(); })
                .bounds(cx - 126, by, 60, 14).build());
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
        addRenderableWidget(Button.builder(Component.literal("Send"), b -> sendMail())
                .bounds(cx - 190, 94, 60, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),
                        b -> { composing = false; rebuildWidgets(); })
                .bounds(cx - 126, 94, 60, 14).build());
    }

    private void markRead() {
        var row = rows().get(selected);
        if (row == null || row.id() == null) return;
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, ServerboundMailActionPayload.ACTION_MARK_READ, row.id()));
    }

    private void deleteSelected() {
        var row = rows().get(selected);
        if (row == null || row.id() == null) return;
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, ServerboundMailActionPayload.ACTION_DELETE, row.id()));
        selected = -1;
    }

    private void sendMail() {
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
            return;
        }
        graphics.drawCenteredString(this.font,
                "§7" + rows().size() + " message(s)", cx, 22, 0xAAAAAA);

        var rows = rows();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            MailRow row = rows.get(i);
            if (row == null) { y += 12; continue; }
            String prefix = row.read() ? "§7" : "§f";
            String line = prefix + (i == selected ? "> " : "") + row.sender()
                    + " §8— " + row.subject();
            graphics.drawString(this.font, line, cx - 190, y, 0xFFFFFF);
            y += 12;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "§7Your mailbox is empty.",
                    cx, y + 8, 0xAAAAAA);
        }

        // Reading pane for the selected message.
        if (selected >= 0 && selected < rows.size()) {
            var row = rows.get(selected);
            if (row != null) {
                int ry = listBottom() + 6;
                graphics.drawString(this.font, "§9From: §f" + row.sender()
                        + "   §9Subject: §f" + row.subject(), cx - 190, ry, 0xFFFFFF);
                ry += 11;
                for (String line : wrap(String.valueOf(row.body()), 62)) {
                    graphics.drawString(this.font, "§7" + line, cx - 190, ry, 0xCCCCCC);
                    ry += 10;
                    if (ry > this.height - 30) break;
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!composing) {
            int cx = this.width / 2;
            if (mouseX >= cx - 190 && mouseX <= cx + 170
                    && mouseY >= listTop() && mouseY <= listBottom()) {
                int index = scrollOffset + (int) ((mouseY - listTop()) / 12);
                if (index >= 0 && index < rows().size()) {
                    selected = index;
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
