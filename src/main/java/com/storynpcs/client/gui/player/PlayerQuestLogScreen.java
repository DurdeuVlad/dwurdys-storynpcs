package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.QuestLogView;
import com.storynpcs.domain.panel.PlayerPanels.QuestRow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing quest log (issue #150, GuiQuestLog parity): renders the
 * server-issued {@link QuestLogView} — status, objective counts, and pending
 * (recoverable) completions. Read-only: the client never mutates progression.
 * Designed for the minimum supported resolution (854x480 @ GUI scale 2 —
 * ~427x240 logical).
 */
public class PlayerQuestLogScreen extends Screen {

    private final QuestLogView view;
    private final UUID sessionId;
    private int scrollOffset = 0;
    private int selected = -1;

    public PlayerQuestLogScreen(QuestLogView view, UUID sessionId) {
        super(Component.literal("Quest Log"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private int rowCount() { return view.quests() == null ? 0 : view.quests().size(); }
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 28; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 12); }

    @Override
    protected void init() {
        int cx = this.width / 2;
        // Scroll controls only when the list overflows the visible area.
        if (rowCount() > maxVisible()) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> { scrollOffset = Math.max(0, scrollOffset - 1); })
                    .bounds(cx + 176, listTop(), 14, 12).build());
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> { scrollOffset = Math.min(rowCount() - maxVisible(), scrollOffset + 1); })
                    .bounds(cx + 176, listBottom() - 12, 14, 12).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"),
                        b -> onClose())
                .bounds(cx - 40, this.height - 24, 80, 16).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, "Quest Log", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rowCount() + " tracked — rev " + view.revision(), cx, 22, 0xAAAAAA);

        List<QuestRow> rows = view.quests() == null ? List.of() : view.quests();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            QuestRow row = rows.get(i);
            if (row == null) { y += 12; continue; }
            String statusColor = switch (String.valueOf(row.status())) {
                case "COMPLETED" -> "§a";
                case "FAILED" -> "§c";
                default -> "§e";
            };
            String line = statusColor + row.title() + " §8[" + row.status()
                    + (row.pendingCompletion() ? " PENDING" : "") + "]";
            graphics.drawString(this.font, line, cx - 190, y, 0xFFFFFF);
            y += 12;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "§7No quests tracked yet.", cx, y + 8, 0xAAAAAA);
        }

        // Selected quest detail (objective counts) under the list.
        if (selected >= 0 && selected < rows.size()) {
            var row = rows.get(selected);
            if (row != null && row.objectives() != null) {
                int dy = listBottom() - 2;
                for (String objective : row.objectives()) {
                    graphics.drawString(this.font, "§8• " + objective, cx - 190, dy, 0xCCCCCC);
                    dy += 10;
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = this.width / 2;
        if (button == 0 && mouseX >= cx - 190 && mouseX <= cx + 170
                && mouseY >= listTop() && mouseY <= listBottom()) {
            int index = scrollOffset + (int) ((mouseY - listTop()) / 12);
            if (index >= 0 && index < rowCount()) {
                selected = index;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
