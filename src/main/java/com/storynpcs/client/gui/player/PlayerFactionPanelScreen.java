package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.FactionPanelView;
import com.storynpcs.domain.panel.PlayerPanels.FactionRow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing faction standing panel (issue #150, InventoryTabFactions
 * parity): authored factions with the player's points and the standing the
 * authored thresholds classify. Read-only — points mutate only through
 * server-side mutations.
 */
public class PlayerFactionPanelScreen extends Screen {

    private final FactionPanelView view;
    private final UUID sessionId;
    private int scrollOffset = 0;

    public PlayerFactionPanelScreen(FactionPanelView view, UUID sessionId) {
        super(Component.literal("Factions"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private int rowCount() { return view.factions() == null ? 0 : view.factions().size(); }
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 28; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 12); }

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
        graphics.drawCenteredString(this.font, "Faction Standing", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rowCount() + " factions — rev " + view.revision(), cx, 22, 0xAAAAAA);

        List<FactionRow> rows = view.factions() == null ? List.of() : view.factions();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            FactionRow row = rows.get(i);
            if (row == null) { y += 12; continue; }
            String standingColor = switch (String.valueOf(row.standing())) {
                case "HOSTILE" -> "§c";
                case "FRIENDLY" -> "§a";
                default -> "§7";
            };
            graphics.drawString(this.font,
                    "§f" + row.name() + standingColor + " " + row.standing()
                            + " §8(" + row.points() + ")",
                    cx - 190, y, 0xFFFFFF);
            y += 12;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "§7No factions exist on this server.",
                    cx, y + 8, 0xAAAAAA);
        }
    }
}
