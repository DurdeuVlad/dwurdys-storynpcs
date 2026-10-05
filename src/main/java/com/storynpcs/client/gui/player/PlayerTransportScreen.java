package com.storynpcs.client.gui.player;

import com.storynpcs.domain.panel.PlayerPanels.TransportRow;
import com.storynpcs.domain.panel.PlayerPanels.TransportView;
import com.storynpcs.network.ServerboundTransportSelectPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
public class PlayerTransportScreen extends Screen {

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
    private int listTop() { return 36; }
    private int listBottom() { return this.height - 30; }
    private int maxVisible() { return Math.max(1, (listBottom() - listTop()) / 12); }

    @Override
    protected void init() {
        int cx = this.width / 2;
        if (rows().size() > maxVisible()) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> scrollOffset = Math.max(0, scrollOffset - 1))
                    .bounds(cx + 176, listTop(), 14, 12).build());
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> scrollOffset = Math.min(rows().size() - maxVisible(), scrollOffset + 1))
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
        graphics.drawCenteredString(this.font, "Transport", cx, 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§7" + rows().size() + " destination(s)", cx, 22, 0xAAAAAA);

        var rows = rows();
        int y = listTop();
        for (int i = scrollOffset; i < Math.min(rows.size(), scrollOffset + maxVisible()); i++) {
            TransportRow row = rows.get(i);
            if (row == null) { y += 12; continue; }
            String status = row.unlocked()
                    ? "§a" + (row.fee() > 0 ? row.fee() + "e" : "free")
                    : "§8locked";
            graphics.drawString(this.font,
                    (row.unlocked() ? "§f" : "§8") + row.name() + " §8["
                            + status + "§8]",
                    cx - 190, y, 0xFFFFFF);
            y += 12;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "§7No transport destinations available.", cx, y + 8, 0xAAAAAA);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = this.width / 2;
        if (button == 0 && mouseX >= cx - 190 && mouseX <= cx + 170
                && mouseY >= listTop() && mouseY <= listBottom()) {
            int index = scrollOffset + (int) ((mouseY - listTop()) / 12);
            var rows = rows();
            if (index >= 0 && index < rows.size()) {
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
