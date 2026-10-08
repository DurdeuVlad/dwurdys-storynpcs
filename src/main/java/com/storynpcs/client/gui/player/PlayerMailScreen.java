package com.storynpcs.client.gui.player;

import com.storynpcs.client.ui.PendingAck;
import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.domain.panel.PlayerPanels.MailRow;
import com.storynpcs.domain.panel.PlayerPanels.MailView;
import com.storynpcs.network.ServerboundMailActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Player-facing mailbox (issue #150, GuiMailbox parity): lists the durable
 * mailbox (newest first), reads a selected message, marks read / deletes, and
 * composes mail to another player. Every commit goes through
 * {@link ServerboundMailActionPayload} with the panel session id — the server
 * re-authorizes and refreshes the view on apply.
 *
 * <p>Refresh contract (issue #201): the view is a mutable slot — a re-issued
 * mail payload calls {@link #updateView} in place instead of replacing the
 * screen, preserving scroll and selection. Selection is keyed by row id via
 * {@link SelectableList}; serverbound actions arm a {@link PendingAck} that
 * disables the action buttons until the refresh (or a timeout) arrives.
 *
 * <p>UI-kit migration (issue #202): shared chrome, list widget, footer
 * actions, echo feedback.
 */
public class PlayerMailScreen extends UiScreen {

    /** Reserved strip under the list for the selected message's body. */
    private static final int DETAIL_H = 56;
    private static final int ECHO_MS = 3_000;

    private MailView view;
    private final UUID sessionId;
    private boolean composing = false;
    private EditBox recipientBox;
    private EditBox subjectBox;
    private EditBox bodyBox;
    private SelectableList<MailRow, String> list;
    // Owned at screen level so selection/scroll survive widget rebuilds
    // (window resize, compose ↔ list transitions).
    private final SelectionModel<String> mailSelection = new SelectionModel<>();
    private final ScrollState mailScroll = new ScrollState();
    private final PendingAck pending = new PendingAck();
    private boolean wasPending;

    public PlayerMailScreen(MailView view, UUID sessionId) {
        super(Component.literal("Mailbox"));
        this.view = view;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    /** Identity check for the refresh contract — this payload is for this session. */
    public boolean matches(UUID sessionId) {
        return Objects.equals(this.sessionId, sessionId);
    }

    /**
     * Replaces the view in place on a server-issued refresh: acknowledges any
     * pending action and pushes fresh rows into the list — the widget's
     * key-stable {@code SelectionModel} keeps the same message selected if it
     * still exists, and its scroll state re-clamps automatically.
     */
    public void updateView(MailView view) {
        if (view == null) return;
        this.view = view;
        pending.ack();
        wasPending = false; // acked — tick() must not report this as expiry
        if (list != null && !composing) {
            list.setRows(rows());
        }
        setStatus(Component.literal(rows().size() + " message(s)"));
    }

    private List<MailRow> rows() {
        return view.messages() == null ? List.of() : view.messages();
    }

    private boolean isPending() {
        return pending.pending(System.currentTimeMillis());
    }

    private void echo(String message) {
        echo(Component.literal(message), ECHO_MS);
    }

    @Override
    public void tick() {
        super.tick();
        // Pending label owns the footer status while armed; on expiry the
        // action buttons re-enable and the loss is reported — chat-only
        // server rejects never send a view payload.
        long now = System.currentTimeMillis();
        boolean nowPending = isPending();
        String pendingLabel = pending.label(now);
        if (pendingLabel != null) {
            overrideStatus(Component.literal("§e" + pendingLabel));
        }
        if (wasPending && !nowPending) {
            echo("No response from server — try again.");
        }
        wasPending = nowPending;
        applyPendingDisabled(nowPending);
    }

    /**
     * Re-apply the pending-disabled state right after a rebuild — footer
     * buttons come back enabled by default and tick() only runs once per
     * frame, so without this a rebuild opens a ~1-tick clickable window.
     */
    @Override
    protected void postInit() {
        applyPendingDisabled(isPending());
    }

    /** Index-stable by declaration: list mode [Read, Delete, Compose, Close]
     * disables 0..1; compose mode [Send, Back] disables 0. */
    private void applyPendingDisabled(boolean nowPending) {
        int disableUpTo = composing ? 1 : 2;
        for (int i = 0; i < Math.min(disableUpTo, footerButtons().size()); i++) {
            footerButtons().get(i).active = !nowPending;
        }
    }

    @Override
    protected void initContent() {
        int cw = contentWidth();
        if (composing) {
            list = null; // detached — updateView skips it until list mode returns
            initCompose();
            return;
        }
        setStatus(Component.literal(rows().size() + " message(s)"));
        int listH = Math.max(UiTheme.ROW_H,
                contentBottom() - contentTop() - DETAIL_H - UiTheme.PAD_S);
        list = new SelectableList<>(
                contentLeft(), contentTop(), cw, listH,
                UiTheme.ROW_H, MailRow::id, PlayerMailScreen::label,
                mailSelection, mailScroll);
        list.setRows(rows());
        addRenderableWidget(list);
        addFooterAction(Component.literal("Read"),
                b -> { if (selectedRow() != null) markRead(); else echo("Select a message first."); });
        addFooterAction(Component.literal("Delete"),
                b -> { if (selectedRow() != null) deleteSelected(); else echo("Select a message first."); });
        addFooterAction(Component.literal("Compose"),
                b -> { composing = true; rebuildWidgets(); });
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    private void initCompose() {
        int cw = contentWidth();
        int fieldW = Math.min(cw, 240);
        recipientBox = new EditBox(this.font, contentLeft(), contentTop(), fieldW, 14,
                Component.literal("To"));
        recipientBox.setHint(Component.literal("Player name"));
        recipientBox.setMaxLength(64);
        subjectBox = new EditBox(this.font, contentLeft(), contentTop() + 18, fieldW, 14,
                Component.literal("Subject"));
        subjectBox.setHint(Component.literal("Subject"));
        subjectBox.setMaxLength(128);
        bodyBox = new EditBox(this.font, contentLeft(), contentTop() + 36, cw, 14,
                Component.literal("Message"));
        bodyBox.setHint(Component.literal("Message body"));
        bodyBox.setMaxLength(2048);
        addRenderableWidget(recipientBox);
        addRenderableWidget(subjectBox);
        addRenderableWidget(bodyBox);
        addFooterAction(Component.literal("Send"), b -> sendMail());
        addFooterAction(Component.literal("Back"),
                b -> { composing = false; rebuildWidgets(); });
    }

    private MailRow selectedRow() {
        return list != null ? list.selectedRow() : null;
    }

    private void markRead() {
        MailRow row = selectedRow();
        if (row == null || row.id() == null) return;
        pending.begin("Marking read…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, ServerboundMailActionPayload.ACTION_MARK_READ, row.id()));
    }

    private void deleteSelected() {
        MailRow row = selectedRow();
        if (row == null || row.id() == null) return;
        pending.begin("Deleting…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, ServerboundMailActionPayload.ACTION_DELETE, row.id()));
    }

    private void sendMail() {
        if (recipientBox.getValue().trim().isEmpty()) {
            echo("Recipient name is required.");
            return;
        }
        pending.begin("Sending…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundMailActionPayload(
                sessionId, UUID.randomUUID(), ServerboundMailActionPayload.ACTION_SEND,
                recipientBox.getValue().trim(), subjectBox.getValue(), bodyBox.getValue()));
        composing = false;
        rebuildWidgets();
    }

    private static Component label(MailRow row) {
        return Component.literal((row.read() ? "§7" : "§f") + row.sender()
                + " §8— " + row.subject());
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (composing) return;
        if (rows().isEmpty()) {
            renderEmpty(graphics, "Your mailbox is empty.");
            return;
        }
        // Reading pane for the selected message in the reserved detail strip.
        int detailTop = contentTop() + Math.max(UiTheme.ROW_H,
                contentBottom() - contentTop() - DETAIL_H - UiTheme.PAD_S) + UiTheme.PAD_S;
        MailRow row = selectedRow();
        if (row == null) {
            graphics.drawString(this.font, "§8Select a message to read.",
                    contentLeft(), detailTop, UiTheme.TEXT_MUTED);
            return;
        }
        graphics.enableScissor(contentLeft(), detailTop, contentRight(), contentBottom());
        graphics.drawString(this.font, "§9From: §f" + row.sender()
                + "   §9Subject: §f" + row.subject(), contentLeft(), detailTop, UiTheme.TEXT);
        int ry = detailTop + 11;
        for (String line : wrap(String.valueOf(row.body()), 62)) {
            graphics.drawString(this.font, "§7" + line, contentLeft(), ry, UiTheme.TEXT_MUTED);
            ry += 10;
            if (ry > contentBottom() - 9) break;
        }
        graphics.disableScissor();
    }

    private static List<String> wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() + word.length() + 1 > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder();
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
