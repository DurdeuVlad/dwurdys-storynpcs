package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.domain.panel.PlayerPanels.CarpentryRow;
import com.storynpcs.domain.panel.PlayerPanels.CarpentryView;
import com.storynpcs.service.PlayerPanelViews;
import com.storynpcs.network.ServerboundPanelActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Carpentry bench (issue #150, GuiNpcCarpentryBench parity): authored recipes
 * with a Craft commit for the selected row. The server re-verifies the recipe
 * and consumes the player's live inventory — this screen never predicts
 * success.
 *
 * <p>Migrated to the shared UI kit (issue #202): per-row Craft buttons
 * become select-row → footer-commit.
 */
public class PlayerCarpentryScreen extends UiScreen {

    private static final int ROW_H = 22;

    private final CarpentryView view;
    private final UUID sessionId;
    private SelectableList<CarpentryRow, String> list;

    public PlayerCarpentryScreen(CarpentryView view, UUID sessionId) {
        super(Component.literal("Carpentry Bench"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<CarpentryRow> rows() {
        return view.recipes() == null ? List.of() : view.recipes();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " recipes"));
        list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(),
                contentBottom() - contentTop(), ROW_H,
                CarpentryRow::id,
                r -> Component.literal("§f" + r.outputItemId() + " x" + r.outputCount()
                        + (r.shapeless() ? " §8(shapeless)" : "")));
        list.setDetailRenderer(r -> r.ingredientSummary() == null || r.ingredientSummary().isEmpty()
                ? null
                : Component.literal(String.join(", ", r.ingredientSummary())));
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Craft"), b -> craft());
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private void craft() {
        CarpentryRow row = list != null ? list.selectedRow() : null;
        if (row == null) {
            echo(Component.literal("Select a recipe first."), 3000);
            return;
        }
        PacketDistributor.sendToServer(new ServerboundPanelActionPayload(
                sessionId, PlayerPanelViews.PANEL_CARPENTRY,
                ServerboundPanelActionPayload.ACTION_CRAFT, row.id()));
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "No carpentry recipes authored.");
        }
    }
}
