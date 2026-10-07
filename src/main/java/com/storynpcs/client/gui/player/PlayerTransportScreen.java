package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.domain.panel.PlayerPanels.TransportRow;
import com.storynpcs.domain.panel.PlayerPanels.TransportView;
import com.storynpcs.network.ServerboundTransportSelectPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing transport picker (issue #150, GuiTransportSelection parity):
 * lists only destinations the server marked visible, flags unlocked ones, and
 * sends {@link ServerboundTransportSelectPayload} on selection — the server
 * re-evaluates lock state, fee, and safety before moving the player.
 */
public class PlayerTransportScreen extends UiScreen {

    private final TransportView view;
    private final UUID sessionId;
    private int scrollOffset = 0;

    public PlayerTransportScreen(TransportView view, UUID sessionId) {
        super(Component.literal("Transport"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<TransportRow> rows() {
        return view.destinations() == null ? List.of() : view.destinations();
    }
    private int maxVisible() {
        return Math.max(1, (contentBottom() - contentTop()) / UiTheme.ROW_H);
    }
    /** Right edge of the clickable row band — leaves the scroll column clear. */
    private int rowRight() { return contentRight() - 18; }

    @Override
    protected void initContent() {
        scrollOffset = Math.min(scrollOffset, Math.max(0, rows().size() - maxVisible()));
        setStatus(Component.literal(rows().size() + " destination(s)"));
        if (rows().size() > maxVisible()) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> scrollOffset = Math.max(0, scrollOffset - 1))
                    .bounds(contentRight() - 14, contentTop(), 14, 12).build());
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> scrollOffset = Math.min(rows().size() - maxVisible(), scrollOffset + 1))
                    .bounds(contentRight() - 14, contentBottom() - 12, 14, 12).build());
        }
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var rows = rows();
        int y = contentTop();
        graphics.enableScissor(contentLeft(), contentTop(),
                rowRight(), contentTop() + maxVisible() * UiTheme.ROW_H);
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            TransportRow row = rows.get(i);
            if (row == null) { y += UiTheme.ROW_H; continue; }
            String status = row.unlocked()
                    ? "§a" + (row.fee() > 0 ? row.fee() + "e" : "free")
                    : "§8locked";
            graphics.drawString(this.font,
                    (row.unlocked() ? "§f" : "§8") + row.name() + " §8["
                            + status + "§8]",
                    contentLeft(), y, UiTheme.TEXT);
            y += UiTheme.ROW_H;
        }
        graphics.disableScissor();
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "§7No transport destinations available.",
                    panelX + panelW / 2, y + UiTheme.PAD_M, UiTheme.TEXT_MUTED);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= contentLeft() && mouseX < rowRight()
                && mouseY >= contentTop()
                && mouseY < contentTop() + maxVisible() * UiTheme.ROW_H) {
            int index = scrollOffset + (int) ((mouseY - contentTop()) / UiTheme.ROW_H);
            var rows = rows();
            if (index < rows.size()) {
                var row = rows.get(index);
                if (row != null && row.unlocked()) {
                    PacketDistributor.sendToServer(new ServerboundTransportSelectPayload(
                            sessionId, UUID.randomUUID(), row.id()));
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
