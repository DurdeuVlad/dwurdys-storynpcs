package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.domain.panel.PlayerPanels.FactionPanelView;
import com.storynpcs.domain.panel.PlayerPanels.FactionRow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing faction standing panel (issue #150, InventoryTabFactions
 * parity): authored factions with the player's points and the standing the
 * authored thresholds classify. Read-only — points mutate only through
 * server-side mutations.
 *
 * <p>Migrated to the shared UI kit (issue #202): bounded {@link UiScreen}
 * surface + {@link SelectableList} rows.
 */
public class PlayerFactionPanelScreen extends UiScreen {

    private final FactionPanelView view;
    private final UUID sessionId;
    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();

    public PlayerFactionPanelScreen(FactionPanelView view, UUID sessionId) {
        super(Component.literal("Faction Standing"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<FactionRow> rows() {
        return view.factions() == null ? List.of() : view.factions();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " factions — rev " + view.revision()));
        SelectableList<FactionRow, String> list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(),
                contentBottom() - contentTop(), UiTheme.ROW_H,
                FactionRow::factionId, PlayerFactionPanelScreen::label,
                selection, scroll);
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private static Component label(FactionRow row) {
        String standingColor = switch (String.valueOf(row.standing())) {
            case "HOSTILE" -> "§c";
            case "FRIENDLY" -> "§a";
            default -> "§7";
        };
        return Component.literal("§f" + row.name() + standingColor + " " + row.standing()
                + " §8(" + row.points() + ")");
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "No factions exist on this server.");
        }
    }
}
