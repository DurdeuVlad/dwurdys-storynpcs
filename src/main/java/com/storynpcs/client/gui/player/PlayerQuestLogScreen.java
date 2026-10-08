package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.domain.panel.PlayerPanels.QuestLogView;
import com.storynpcs.domain.panel.PlayerPanels.QuestRow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing quest log (issue #150, GuiQuestLog parity): renders the
 * server-issued {@link QuestLogView} — status, objective counts, and pending
 * (recoverable) completions. Read-only: the client never mutates progression.
 *
 * <p>Migrated to the shared UI kit (issue #202): {@link SelectableList} +
 * objectives detail strip for the selected quest.
 */
public class PlayerQuestLogScreen extends UiScreen {

    /** Reserved strip under the list for the selected quest's objectives. */
    private static final int DETAIL_H = 46;

    private final QuestLogView view;
    private final UUID sessionId;
    private SelectableList<QuestRow, String> list;
    private final SelectionModel<String> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();

    public PlayerQuestLogScreen(QuestLogView view, UUID sessionId) {
        super(Component.literal("Quest Log"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<QuestRow> rows() {
        return view.quests() == null ? List.of() : view.quests();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " tracked — rev " + view.revision()));
        int listH = Math.max(UiTheme.ROW_H,
                contentBottom() - contentTop() - DETAIL_H - UiTheme.PAD_S);
        list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(), listH,
                UiTheme.ROW_H, QuestRow::questId, PlayerQuestLogScreen::label,
                selection, scroll);
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private static Component label(QuestRow row) {
        String statusColor = switch (String.valueOf(row.status())) {
            case "COMPLETED" -> "§a";
            case "FAILED" -> "§c";
            default -> "§e";
        };
        return Component.literal(statusColor + row.title() + " §8[" + row.status()
                + (row.pendingCompletion() ? " PENDING" : "") + "]");
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "No quests tracked yet.");
            return;
        }
        // Selected quest objectives in the reserved detail strip.
        int detailTop = contentTop() + Math.max(UiTheme.ROW_H,
                contentBottom() - contentTop() - DETAIL_H - UiTheme.PAD_S) + UiTheme.PAD_S;
        QuestRow row = list != null ? list.selectedRow() : null;
        if (row == null) {
            graphics.drawString(this.font, "§8Select a quest for objectives.",
                    contentLeft(), detailTop, UiTheme.TEXT_MUTED);
            return;
        }
        if (row.objectives() == null) return;
        graphics.enableScissor(contentLeft(), detailTop, contentRight(), contentBottom());
        int dy = detailTop;
        for (String objective : row.objectives()) {
            graphics.drawString(this.font, "§8• " + objective,
                    contentLeft(), dy, UiTheme.TEXT_MUTED);
            dy += 10;
            if (dy > contentBottom() - 10) break;
        }
        graphics.disableScissor();
    }
}
