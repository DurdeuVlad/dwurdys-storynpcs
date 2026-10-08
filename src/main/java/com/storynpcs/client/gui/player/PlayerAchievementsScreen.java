package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.domain.panel.PlayerPanels.AchievementRow;
import com.storynpcs.domain.panel.PlayerPanels.AchievementView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Player-facing achievement view (issue #150, GuiAchievement parity). The
 * target's achievement page has no vanilla-achievement counterpart, so the
 * server reports earned progress — completed quests, friendly standings,
 * hired companions — as read-only rows. No commit path exists: nothing here
 * is a mutation.
 *
 * <p>Migrated to the shared UI kit (issue #202).
 */
public class PlayerAchievementsScreen extends UiScreen {

    private final AchievementView view;
    private final UUID sessionId;

    public PlayerAchievementsScreen(AchievementView view, UUID sessionId) {
        super(Component.literal("Achievements"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private List<AchievementRow> rows() {
        return view.rows() == null ? List.of() : view.rows();
    }

    @Override
    protected void initContent() {
        setStatus(Component.literal(rows().size() + " earned"));
        SelectableList<AchievementRow, String> list = new SelectableList<>(
                contentLeft(), contentTop(), contentWidth(),
                contentBottom() - contentTop(), UiTheme.ROW_H + 2,
                AchievementRow::id, r -> Component.literal("§6" + r.title() + " §8" + r.detail()));
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rows().isEmpty()) {
            renderEmpty(graphics, "Nothing earned yet — complete a quest or earn standing.");
        }
    }
}
