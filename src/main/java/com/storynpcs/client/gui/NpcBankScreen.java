package com.storynpcs.client.gui;

import com.storynpcs.client.ui.AttemptIds;
import com.storynpcs.client.ui.PendingAck;
import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.network.ServerboundBankActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Player-facing vault screen for a banker-role NPC. Opened server-side via
 * {@link com.storynpcs.network.ClientboundBankOpenPayload}; every action sends
 * {@link ServerboundBankActionPayload} and the server replies with a fresh
 * open packet, which refreshes this screen in place (tab + scroll preserved).
 *
 * <p>UI-kit migration (issue #204): shared {@link UiScreen} chrome, vault
 * tab row + per-row Withdraw inside the content band, and footer actions
 * (Deposit held / Unlock tab / Close) carrying a {@link PendingAck} pending
 * state so an action is never a silent no-op.
 */
public class NpcBankScreen extends UiScreen {

    private static final int ROW_H = 12;
    private static final int WD_W = 56;
    private static final int ECHO_MS = 3_000;

    private final String npcId;
    private BankerRole banker;
    private BankVault vault;
    private final UUID sessionId;
    private final AttemptIds requestIds = new AttemptIds();
    private final PendingAck pending = new PendingAck();
    private final List<Button> rowButtons = new ArrayList<>();
    private boolean wasPending;
    private int currentTab = 0;
    private int scrollOffset = 0;

    public NpcBankScreen(String npcId, BankerRole banker, BankVault vault, UUID sessionId) {
        super(Component.literal(banker == null ? "Bank" : banker.getBankName()));
        this.npcId = npcId != null ? npcId : "";
        this.banker = banker;
        this.vault = vault;
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    /** Identity check for the refresh contract — this payload is for this session. */
    public boolean matches(UUID sessionId) {
        return Objects.equals(this.sessionId, sessionId);
    }

    /**
     * In-place refresh: new banker/vault without losing the open tab or
     * scroll. {@code requestId} is the server's echo of the request this
     * refresh answers (issue #219) — pending is acked only on a match and only
     * the echoed attempt id is cleared, so an in-flight retry keeps its
     * journal-classifiable id.
     */
    public void updateView(BankerRole banker, BankVault vault, UUID requestId) {
        if (banker == null || vault == null) return;
        this.banker = banker;
        this.vault = vault;
        if (pending.ack(requestId)) {
            wasPending = false;
        }
        requestIds.ack(requestId);
        rebuildWidgets();
    }

    // ---- geometry ----

    private int rowsTop() { return contentTop() + 16; }
    private int maxVisibleRows() { return Math.max(1, (contentBottom() - rowsTop()) / ROW_H); }
    private int unlockedTabs() {
        return vault == null || banker == null ? 1
                : Math.max(1, Math.min(vault.getUnlockedTabs(), Math.max(1, banker.getMaxTabs())));
    }

    private List<BankVault.VaultItem> tabItems() {
        if (vault == null) return List.of();
        return vault.getTabItems(currentTab).stream()
                .sorted(Comparator.comparingInt(BankVault.VaultItem::getSlot))
                .toList();
    }

    private boolean isPending() {
        return pending.pending(System.currentTimeMillis());
    }

    @Override
    protected void initContent() {
        currentTab = Math.min(currentTab, unlockedTabs() - 1);
        rowButtons.clear();

        // Tab row: [ < ] Tab X/Y [ > ] when multiple tabs are unlocked.
        if (unlockedTabs() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"),
                            b -> { currentTab = Math.max(0, currentTab - 1); scrollOffset = 0; rebuildWidgets(); })
                    .bounds(contentLeft(), contentTop(), 16, 12).build());
            addRenderableWidget(Button.builder(Component.literal(">"),
                            b -> { currentTab = Math.min(unlockedTabs() - 1, currentTab + 1); scrollOffset = 0; rebuildWidgets(); })
                    .bounds(contentLeft() + 88, contentTop(), 16, 12).build());
        }

        var items = tabItems();
        int maxScroll = Math.max(0, items.size() - maxVisibleRows());
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int visible = Math.min(items.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            var item = items.get(scrollOffset + i);
            int y = rowsTop() + i * ROW_H;
            int tab = currentTab;
            int slot = item.getSlot();
            Button withdraw = Button.builder(Component.literal("Withdraw"),
                            b -> act("withdraw", tab, slot))
                    .bounds(contentRight() - WD_W, y, WD_W, 11)
                    .build();
            rowButtons.add(withdraw);
            addRenderableWidget(withdraw);
        }

        setStatus(Component.literal("Tab " + (currentTab + 1) + "/" + unlockedTabs()
                + " — " + items.size() + " item(s)"));

        addFooterAction(Component.literal("Deposit held"),
                b -> act("deposit_held", currentTab, 0));
        if (banker != null && unlockedTabs() < Math.max(1, banker.getMaxTabs())) {
            int cost = Math.max(0, banker.getTabUpgradeCost());
            addFooterAction(Component.literal(
                    cost > 0 ? "Unlock tab (" + cost + " emeralds)" : "Unlock tab (free)"),
                    b -> act("unlock_tab", 0, 0));
        }
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    @Override
    protected void postInit() {
        applyPendingDisabled(isPending());
    }

    private void act(String action, int tab, int slot) {
        if (isPending()) return;
        UUID requestId = requestIds.idFor(action + ":" + tab + ":" + slot);
        pending.begin(switch (action) {
            case "deposit_held" -> "Depositing…";
            case "unlock_tab" -> "Unlocking…";
            default -> "Working…";
        }, System.currentTimeMillis(), requestId);
        PacketDistributor.sendToServer(new ServerboundBankActionPayload(npcId, action, tab, slot,
                sessionId, requestId));
        applyPendingDisabled(true);
    }

    private void applyPendingDisabled(boolean nowPending) {
        for (Button b : rowButtons) b.active = !nowPending;
        // Footer order: Deposit held, [Unlock tab], Close — never disable Close.
        int actionCount = Math.max(0, footerButtons().size() - 1);
        for (int i = 0; i < actionCount; i++) {
            footerButtons().get(i).active = !nowPending;
        }
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        boolean nowPending = isPending();
        String label = pending.label(now);
        if (label != null) {
            overrideStatus(Component.literal("§e" + label));
        }
        if (wasPending && !nowPending) {
            echo(Component.literal("No response from server — try again."), ECHO_MS);
        }
        wasPending = nowPending;
        applyPendingDisabled(nowPending);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        String tabLabel = "Tab " + (currentTab + 1) + "/" + unlockedTabs();
        graphics.drawString(this.font, tabLabel,
                unlockedTabs() > 1 ? contentLeft() + 22 : contentLeft(),
                contentTop() + 3, UiTheme.TEXT_MUTED);

        var items = tabItems();
        int visible = Math.min(items.size() - scrollOffset, maxVisibleRows());
        int textW = contentWidth() - WD_W - UiTheme.PAD_M;
        graphics.enableScissor(contentLeft(), rowsTop(), contentRight() - WD_W - UiTheme.PAD_S,
                rowsTop() + visible * ROW_H);
        for (int i = 0; i < visible; i++) {
            var item = items.get(scrollOffset + i);
            int y = rowsTop() + i * ROW_H;
            String label = item.getCount() + "x " + itemName(item.getItemId());
            if (this.font.width(label) > textW) {
                label = this.font.plainSubstrByWidth(label, textW);
            }
            graphics.drawString(this.font, label, contentLeft(), y + 2, UiTheme.TEXT);
        }
        graphics.disableScissor();

        if (items.isEmpty()) {
            renderEmpty(graphics, "Empty — hold an item and press Deposit held.");
        } else if (items.size() > maxVisibleRows()) {
            graphics.drawString(this.font, "§7scroll for more",
                    contentLeft(), contentBottom() - 9, UiTheme.TEXT_MUTED);
        }
    }

    private static String itemName(String itemId) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (rl == null) return itemId;
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl)
                .map(i -> new net.minecraft.world.item.ItemStack(i).getHoverName().getString())
                .orElse(itemId);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, tabItems().size() - maxVisibleRows());
        int next = scrollOffset + (scrollY < 0 ? 1 : -1);
        if (next >= 0 && next <= maxScroll) {
            scrollOffset = next;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
