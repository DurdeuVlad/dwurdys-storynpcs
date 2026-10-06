package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.CarpentryRow;
import com.storynpcs.domain.panel.PlayerPanels.CarpentryView;
import com.storynpcs.service.PlayerPanelViews;
import com.storynpcs.network.ServerboundPanelActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Carpentry bench (issue #150, GuiNpcCarpentryBench parity): authored recipes
 * with a Craft commit per row. The server re-verifies the recipe and consumes
 * the player's live inventory — this screen never predicts success.
 */
public class PlayerCarpentryScreen extends Screen {

    private static final int MAX_ROWS = 6;

    private final CarpentryView view;
    private final UUID sessionId;
    private int scrollOffset = 0;

    public PlayerCarpentryScreen(CarpentryView view, UUID sessionId) {
        super(Component.literal("Carpentry Bench"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private int rowCount() { return view.recipes() == null ? 0 : view.recipes().size(); }
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 28; }
    private int maxVisible() { return Math.max(1, Math.min(MAX_ROWS, (listBottom() - listTop()) / 24)); }

    @Override
    protected void init() {
        int cx = this.width / 2;
        List<CarpentryRow> rows = view.recipes() == null ? List.of() : view.recipes();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            CarpentryRow row = rows.get(i);
            if (row == null) { y += 24; continue; }
            addRenderableWidget(Button.builder(Component.literal("Craft"),
                            b -> PacketDistributor.sendToServer(new ServerboundPanelActionPayload(
                                    sessionId, PlayerPanelViews.PANEL_CARPENTRY,
                                    ServerboundPanelActionPayload.ACTION_CRAFT, row.id())))
                    .bounds(cx + 130, y - 2, 60, 14).build());
            y += 24;
        }
        if (rowCount() > maxVisible()) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> { scrollOffset = Math.max(0, scrollOffset - 1); rebuildWidgets(); })
                    .bounds(cx + 196, listTop(), 14, 12).build());
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> { scrollOffset = Math.min(rowCount() - maxVisible(), scrollOffset + 1);
                                rebuildWidgets(); })
                    .bounds(cx + 196, listBottom() - 12, 14, 12).build());
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
        graphics.drawCenteredString(this.font, "Carpentry Bench", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rowCount() + " recipes", cx, 22, 0xAAAAAA);

        List<CarpentryRow> rows = view.recipes() == null ? List.of() : view.recipes();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            CarpentryRow row = rows.get(i);
            if (row == null) { y += 24; continue; }
            graphics.drawString(this.font,
                    "§f" + row.outputItemId() + " x" + row.outputCount()
                            + (row.shapeless() ? " §8(shapeless)" : ""),
                    cx - 190, y, 0xFFFFFF);
            if (row.ingredientSummary() != null && !row.ingredientSummary().isEmpty()) {
                graphics.drawString(this.font,
                        "§8" + String.join(", ", row.ingredientSummary()),
                        cx - 190, y + 10, 0xAAAAAA);
            }
            y += 24;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "§7No carpentry recipes authored.", cx, y + 8, 0xAAAAAA);
        }
    }
}
