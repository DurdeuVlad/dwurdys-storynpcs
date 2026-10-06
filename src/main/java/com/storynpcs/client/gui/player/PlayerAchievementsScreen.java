package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.AchievementRow;
import com.storynpcs.domain.panel.PlayerPanels.AchievementView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing achievement view (issue #150, GuiAchievement parity). The
 * target's achievement page has no vanilla-achievement counterpart, so the
 * server reports earned progress — completed quests, friendly standings,
 * hired companions — as read-only rows. No commit path exists: nothing here
 * is a mutation.
 */
public class PlayerAchievementsScreen extends Screen {

    private final AchievementView view;
    private final UUID sessionId;
    private int scrollOffset = 0;

    public PlayerAchievementsScreen(AchievementView view, UUID sessionId) {
        super(Component.literal("Achievements"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private int rowCount() { return view.rows() == null ? 0 : view.rows().size(); }
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 28; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 14); }

    @Override
    protected void init() {
        int cx = this.width / 2;
        if (rowCount() > maxVisible()) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> scrollOffset = Math.max(0, scrollOffset - 1))
                    .bounds(cx + 176, listTop(), 14, 12).build());
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> scrollOffset = Math.min(rowCount() - maxVisible(), scrollOffset + 1))
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
        graphics.drawCenteredString(this.font, "Achievements", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rowCount() + " earned", cx, 22, 0xAAAAAA);

        List<AchievementRow> rows = view.rows() == null ? List.of() : view.rows();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            AchievementRow row = rows.get(i);
            if (row == null) { y += 14; continue; }
            graphics.drawString(this.font,
                    "§6" + row.title() + " §8" + row.detail(),
                    cx - 190, y, 0xFFFFFF);
            y += 14;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "§7Nothing earned yet — complete a quest or earn standing.",
                    cx, y + 8, 0xAAAAAA);
        }
    }
}
