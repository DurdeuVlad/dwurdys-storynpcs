package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.domain.panel.PlayerPanels.HireRow;
import com.storynpcs.domain.panel.PlayerPanels.HireView;
import com.storynpcs.service.PlayerPanelViews;
import com.storynpcs.network.ServerboundPanelActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Follower-hire surface (issue #150, GuiNpcFollowerHire parity): nearby
 * unowned StoryNPCs with their authored wage preview. The Hire footer action
 * posts a session-bound commit for the selected row; the server re-verifies
 * reachability and ownership before the canonical {@code follower.owner.set}
 * mutation lands.
 *
 * <p>Migrated to the shared UI kit (issue #202): per-row buttons become
 * select-row → footer-commit, matching the kit's selection model.
 */
public class PlayerFollowerHireScreen extends UiScreen {

    private static final int ROW_H = 22;

    private final HireView view;
    private final UUID sessionId;
    private SelectableList<HireRow, String> list;
    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();

    public PlayerFollowerHireScreen(HireView view, UUID sessionId) {
        super(Component.literal("Hire Follower"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<HireRow> rows() {
        return view.candidates() == null ? List.of() : view.candidates();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " nearby candidates"));
        list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(),
                contentBottom() - contentTop(), ROW_H,
                HireRow::entityUuid,
                r -> Component.literal("§f" + r.name()),
                selection, scroll);
        list.setDetailRenderer(r -> Component.literal(
                "wage " + r.wageAmount() + " per " + (r.wageIntervalTicks() / 20) + "s"));
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Hire"), b -> hire());
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private void hire() {
        HireRow row = list != null ? list.selectedRow() : null;
        if (row == null) {
            echo(Component.literal("Select a candidate first."), 3000);
            return;
        }
        PacketDistributor.sendToServer(new ServerboundPanelActionPayload(
                sessionId, PlayerPanelViews.PANEL_FOLLOWER_HIRE,
                ServerboundPanelActionPayload.ACTION_HIRE, row.entityUuid()));
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "No hireable NPCs nearby.");
        }
    }
}
