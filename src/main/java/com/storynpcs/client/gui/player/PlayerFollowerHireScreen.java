package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.HireRow;
import com.storynpcs.domain.panel.PlayerPanels.HireView;
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
 * Follower-hire surface (issue #150, GuiNpcFollowerHire parity): nearby
 * unowned StoryNPCs with their authored wage preview. The Hire button posts
 * a session-bound commit; the server re-verifies reachability and ownership
 * before the canonical {@code follower.owner.set} mutation lands.
 */
public class PlayerFollowerHireScreen extends Screen {

    private static final int MAX_ROWS = 8;

    private final HireView view;
    private final UUID sessionId;
    private int scrollOffset = 0;

    public PlayerFollowerHireScreen(HireView view, UUID sessionId) {
        super(Component.literal("Hire Follower"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private int rowCount() { return view.candidates() == null ? 0 : view.candidates().size(); }
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 28; }
    private int maxVisible() { return Math.max(1, Math.min(MAX_ROWS, (listBottom() - listTop()) / 16)); }

    @Override
    protected void init() {
        int cx = this.width / 2;
        List<HireRow> rows = view.candidates() == null ? List.of() : view.candidates();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            HireRow row = rows.get(i);
            if (row == null) { y += 16; continue; }
            addRenderableWidget(Button.builder(Component.literal("Hire"),
                            b -> PacketDistributor.sendToServer(new ServerboundPanelActionPayload(
                                    sessionId, PlayerPanelViews.PANEL_FOLLOWER_HIRE,
                                    ServerboundPanelActionPayload.ACTION_HIRE, row.entityUuid())))
                    .bounds(cx + 130, y - 2, 60, 14).build());
            y += 16;
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
        graphics.drawCenteredString(this.font, "Hire Follower", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rowCount() + " nearby candidates", cx, 22, 0xAAAAAA);

        List<HireRow> rows = view.candidates() == null ? List.of() : view.candidates();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            HireRow row = rows.get(i);
            if (row == null) { y += 16; continue; }
            graphics.drawString(this.font,
                    "§f" + row.name() + " §8— wage " + row.wageAmount()
                            + " per " + (row.wageIntervalTicks() / 20) + "s",
                    cx - 190, y + 2, 0xFFFFFF);
            y += 16;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "§7No hireable NPCs nearby.", cx, y + 8, 0xAAAAAA);
        }
    }
}
