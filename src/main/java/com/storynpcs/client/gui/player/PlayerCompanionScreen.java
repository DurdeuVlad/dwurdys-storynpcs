package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.CompanionRow;
import com.storynpcs.domain.panel.PlayerPanels.CompanionView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing companion panel (issue #150, GuiNpcCompanion* parity):
 * server-issued read view of hired companions — health, active stage,
 * talent count, carry capacity, and wage-paused state. Read-only: companion
 * mutation (dismiss, stance, inventory) stays on the entity's canonical
 * interact paths; this screen fabricates no local state.
 */
public class PlayerCompanionScreen extends Screen {

    private final CompanionView view;
    private final UUID sessionId;
    private int scrollOffset = 0;

    public PlayerCompanionScreen(CompanionView view, UUID sessionId) {
        super(Component.literal("Companions"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private int rowCount() { return view.companions() == null ? 0 : view.companions().size(); }
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 28; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 24); }

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
        graphics.drawCenteredString(this.font, "Companions", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rowCount() + " hired", cx, 22, 0xAAAAAA);

        List<CompanionRow> rows = view.companions() == null ? List.of() : view.companions();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            CompanionRow row = rows.get(i);
            if (row == null) { y += 24; continue; }
            String state = row.paused() ? "§e[paused]" : "§a[active]";
            graphics.drawString(this.font,
                    "§f" + row.name() + " " + state
                            + " §7hp " + (int) row.health() + "/" + (int) row.maxHealth(),
                    cx - 190, y, 0xFFFFFF);
            graphics.drawString(this.font,
                    "§8stage " + (row.stageId() == null || row.stageId().isEmpty() ? "—" : row.stageId())
                            + " · talents " + row.talentCount()
                            + " · carry " + row.carryCapacity(),
                    cx - 190, y + 10, 0xAAAAAA);
            y += 24;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "§7No companions in your service.",
                    cx, y + 8, 0xAAAAAA);
        }
    }
}
