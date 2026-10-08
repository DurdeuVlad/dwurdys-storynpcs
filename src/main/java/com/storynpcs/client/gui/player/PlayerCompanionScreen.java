package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.domain.panel.PlayerPanels.CompanionRow;
import com.storynpcs.domain.panel.PlayerPanels.CompanionView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing companion panel (issue #150, GuiNpcCompanion* parity):
 * server-issued read view of hired companions — health, active stage,
 * talent count, carry capacity, and wage-paused state. Read-only: companion
 * mutation (dismiss, stance, inventory) stays on the entity's canonical
 * interact paths; this screen fabricates no local state.
 *
 * <p>Migrated to the shared UI kit (issue #202): two-line rows via
 * {@link SelectableList#setDetailRenderer}.
 */
public class PlayerCompanionScreen extends UiScreen {

    private static final int ROW_H = 24;

    private final CompanionView view;
    private final UUID sessionId;

    public PlayerCompanionScreen(CompanionView view, UUID sessionId) {
        super(Component.literal("Companions"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<CompanionRow> rows() {
        return view.companions() == null ? List.of() : view.companions();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " hired"));
        SelectableList<CompanionRow, String> list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(),
                contentBottom() - contentTop(), ROW_H,
                CompanionRow::entityUuid, PlayerCompanionScreen::label);
        list.setDetailRenderer(PlayerCompanionScreen::detail);
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private static Component label(CompanionRow row) {
        String state = row.paused() ? "§e[paused]" : "§a[active]";
        return Component.literal("§f" + row.name() + " " + state
                + " §7hp " + (int) row.health() + "/" + (int) row.maxHealth());
    }

    private static Component detail(CompanionRow row) {
        return Component.literal("stage "
                + (row.stageId() == null || row.stageId().isEmpty() ? "—" : row.stageId())
                + " · talents " + row.talentCount() + " · carry " + row.carryCapacity());
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "No companions in your service.");
        }
    }
}
