package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.domain.panel.PlayerPanels.TransportRow;
import com.storynpcs.domain.panel.PlayerPanels.TransportView;
import com.storynpcs.network.ServerboundTransportSelectPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing transport picker (issue #150, GuiTransportSelection parity):
 * lists only destinations the server marked visible, flags unlocked ones, and
 * sends {@link ServerboundTransportSelectPayload} on activation — the server
 * re-evaluates lock state, fee, and safety before moving the player.
 *
 * <p>UI-kit migration (issue #202): {@link SelectableList} gives the row
 * highlight, wheel/keyboard nav, and scrollbar; activating an unlocked row
 * (click-on-selected / Enter / Space) commits and closes — arrow-key
 * navigation only moves selection and can never charge the player.
 */
public class PlayerTransportScreen extends UiScreen {

    private final TransportView view;
    private final UUID sessionId;
    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();

    public PlayerTransportScreen(TransportView view, UUID sessionId) {
        super(Component.literal("Transport"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<TransportRow> rows() {
        return view == null || view.destinations() == null ? List.of() : view.destinations();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " destination(s)"));
        SelectableList<TransportRow, String> list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(),
                contentBottom() - contentTop(), UiTheme.ROW_H,
                TransportRow::id, PlayerTransportScreen::label, selection, scroll);
        list.setRows(rows());
        // Click selects; click-again or Enter activates (fee guard — a
        // stray arrow press must never charge the player).
        list.setOnActivate(this::commit);
        addRenderableWidget(list);
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private static Component label(TransportRow row) {
        String status = row.unlocked()
                ? "§a" + (row.fee() > 0 ? row.fee() + "e" : "free")
                : "§8locked";
        return Component.literal((row.unlocked() ? "§f" : "§8") + row.name()
                + " §8[" + status + "§8]");
    }

    private void commit(TransportRow row) {
        if (row == null) return;
        if (!row.unlocked()) {
            echo(Component.literal("That destination is locked."), 2_000);
            return;
        }
        PacketDistributor.sendToServer(new ServerboundTransportSelectPayload(
                sessionId, UUID.randomUUID(), row.id()));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        onClose();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "No transport destinations available.");
        }
    }
}
