package com.storynpcs.client.gui;

import com.storynpcs.client.ui.AttemptIds;
import com.storynpcs.client.ui.PendingAck;
import com.storynpcs.client.ui.RowKeys;
import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TradeSummaries;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.network.ServerboundTradeExecutePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Player-facing trade screen for a trader-role NPC. Opened server-side via
 * {@link com.storynpcs.network.ClientboundTradeOpenPayload}; Buy sends
 * {@link ServerboundTradeExecutePayload} and the server replies with a fresh
 * open packet (or a failure message), which refreshes this screen in place.
 *
 * <p>UI-kit migration (issue #204): shared {@link UiScreen} chrome, the
 * buy-left / sell-right two-column listing from #197 retained with item
 * icons, row selection with hover highlight, and a footer Buy action with a
 * {@link PendingAck} pending state — armed on send, cleared when the
 * refreshed payload arrives (or the timeout reports the loss).
 */
public class NpcTradeScreen extends UiScreen {

    /** Row height fits a 16px item icon with a pixel of margin either side. */
    private static final int ROW_H = 18;
    private static final int ARROW_W = 20;
    private static final int ECHO_MS = 3_000;

    private final String npcId;
    private final String npcName;
    private TraderRole trader;
    private Map<String, Integer> factionScores;
    private final UUID sessionId;
    /** One request id per unacknowledged attempt — cleared on ack (#204 review). */
    private final AttemptIds requestIds = new AttemptIds();
    private final PendingAck pending = new PendingAck();
    private boolean wasPending;
    private int scrollOffset = 0;
    private int selected = -1;
    /** Stable listing identity so a refresh/reorder can't retarget the selection. */
    private String selectedKey;

    public NpcTradeScreen(String npcId, String npcName, TraderRole trader, Map<String, Integer> factionScores,
                          UUID sessionId) {
        super(Component.literal(titleOf(trader, npcName)));
        this.npcId = npcId != null ? npcId : "";
        this.npcName = npcName != null ? npcName : "";
        this.trader = trader;
        this.factionScores = factionScores != null ? factionScores : Map.of();
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    private static String titleOf(TraderRole trader, String npcName) {
        String market = trader == null ? "Trade" : String.valueOf(trader.getMarketName());
        return npcName == null || npcName.isBlank() ? market : market + " — " + npcName;
    }

    /** Identity check for the refresh contract — this payload is for this session. */
    public boolean matches(UUID sessionId) {
        return Objects.equals(this.sessionId, sessionId);
    }

    /** In-place refresh: new listings + scores without losing scroll or selection. */
    public void updateView(TraderRole trader, Map<String, Integer> scores) {
        if (trader == null) return;
        this.trader = trader;
        this.factionScores = scores != null ? scores : Map.of();
        pending.ack();
        wasPending = false;
        // The server answered: any cached request id now points at a resolved
        // journal record — the next attempt on a listing must mint a fresh one.
        requestIds.ack();
        int maxScroll = Math.max(0, listings().size() - maxVisibleRows());
        scrollOffset = Math.min(scrollOffset, maxScroll);
        selected = selectedKey == null ? -1
                : RowKeys.indexOf(listings(), NpcTradeScreen::listingKey, selectedKey);
        applyPendingDisabled(isPending());
        setStatus(Component.literal(listings().size() + " listing(s)"));
    }

    private List<TradeListing> listings() {
        var l = trader == null ? null : trader.getListings();
        return l == null ? List.of() : l;
    }

    /** Stable per-listing key: authored id, else the full contract composite. */
    private static String listingKey(TradeListing l) {
        String id = l.getListingId();
        if (id != null && !id.isBlank()) return id;
        String faction = l.getRequiredFaction() == null ? "" : l.getRequiredFaction().toString();
        return l.getOfferItemId() + "x" + l.getOfferCount() + "<-"
                + l.getPriceItemId() + "x" + l.getPriceCount()
                + "+" + l.getSecondaryPriceItemId() + "x" + l.getSecondaryPriceCount()
                + "|u" + l.getMaxUses() + "|f" + faction + ":" + l.getRequiredFactionPoints();
    }

    // ---- geometry (content band) ----

    private int rowsTop() { return contentTop() + 12; }
    private int maxVisibleRows() { return Math.max(1, (contentBottom() - rowsTop()) / ROW_H); }
    private int buyColW() { return (contentWidth() - ARROW_W) / 2; }
    private int sellColX() { return contentLeft() + buyColW() + ARROW_W; }
    private int sellColW() { return contentRight() - sellColX(); }

    private boolean isPending() {
        return pending.pending(System.currentTimeMillis());
    }

    private void echo(String message) {
        echo(Component.literal(message), ECHO_MS);
    }

    @Override
    protected void initContent() {
        int maxScroll = Math.max(0, listings().size() - maxVisibleRows());
        scrollOffset = Math.min(scrollOffset, maxScroll);
        setStatus(Component.literal(listings().size() + " listing(s)"));
        addFooterAction(Component.literal("Buy"), b -> buySelected());
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    @Override
    protected void postInit() {
        applyPendingDisabled(isPending());
    }

    private void applyPendingDisabled(boolean nowPending) {
        // Footer order: [mutation actions…], Close last — never disable Close.
        int actionCount = Math.max(0, footerButtons().size() - 1);
        for (int i = 0; i < actionCount; i++) {
            footerButtons().get(i).active = !nowPending;
        }
    }

    private void buySelected() {
        if (isPending()) return;
        if (selected < 0 || selected >= listings().size()) {
            echo("Select a listing first.");
            return;
        }
        TradeListing listing = listings().get(selected);
        int score = listing.getRequiredFaction() != null
                ? factionScores.getOrDefault(listing.getRequiredFaction().toString(), 0)
                : 0;
        if (!listing.isAvailable(score)) {
            echo("§c" + String.valueOf(TradeSummaries.unavailableReason(listing, score)));
            return;
        }
        pending.begin("Buying…", System.currentTimeMillis());
        PacketDistributor.sendToServer(new ServerboundTradeExecutePayload(npcId, selected, sessionId,
                requestIds.idFor(String.valueOf(selected))));
        applyPendingDisabled(true);
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
            echo("No response from server — try again.");
        }
        wasPending = nowPending;
        applyPendingDisabled(nowPending);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        List<TradeListing> listings = listings();
        int left = contentLeft();
        int buyColW = buyColW();
        int sellColX = sellColX();
        int sellColW = sellColW();

        graphics.drawString(this.font, "§7Trader buys", left, contentTop() + 2, UiTheme.TEXT_MUTED);
        graphics.drawString(this.font, "§7Trader sells", sellColX, contentTop() + 2, UiTheme.TEXT_MUTED);

        int visible = Math.min(listings.size() - scrollOffset, maxVisibleRows());
        int bandBottom = rowsTop() + visible * ROW_H;
        graphics.enableScissor(contentLeft(), rowsTop(), contentRight(), bandBottom);
        for (int i = 0; i < visible; i++) {
            int index = scrollOffset + i;
            TradeListing listing = listings.get(index);
            int y = rowsTop() + i * ROW_H;

            boolean isSel = index == selected;
            boolean hover = mouseX >= contentLeft() && mouseX < contentRight()
                    && mouseY >= y && mouseY < y + ROW_H;
            if (isSel) {
                graphics.fill(contentLeft(), y, contentRight(), y + ROW_H, UiTheme.ROW_SELECTED);
            } else if (hover) {
                graphics.fill(contentLeft(), y, contentRight(), y + ROW_H, UiTheme.ROW_HOVER);
            }

            // Left column — the price: what the trader buys from the player.
            int px = left;
            px += itemLabel(graphics, px, y, buyColW,
                    listing.getPriceItemId(), Math.max(1, listing.getPriceCount()));
            String secondaryId = listing.getSecondaryPriceItemId();
            if (secondaryId != null && !secondaryId.isBlank()
                    && listing.getSecondaryPriceCount() > 0 && px + 12 < left + buyColW) {
                graphics.drawString(this.font, "+", px + 2, y + 5, UiTheme.TEXT_MUTED);
                px += 10;
                itemLabel(graphics, px, y, left + buyColW - px,
                        secondaryId, Math.max(1, listing.getSecondaryPriceCount()));
            }
            graphics.drawString(this.font, "->", left + buyColW + 6, y + 5, UiTheme.TEXT_MUTED);

            // Right column — the offer: what the trader sells to the player.
            int score = listing.getRequiredFaction() != null
                    ? factionScores.getOrDefault(listing.getRequiredFaction().toString(), 0)
                    : 0;
            String reason = TradeSummaries.unavailableReason(listing, score);
            int reasonW = reason != null ? this.font.width(reason) + 8 : 0;

            int sx = sellColX;
            ItemStack offerStack = itemStack(listing.getOfferItemId());
            if (offerStack != null && sellColW - reasonW >= 18) {
                graphics.renderItem(offerStack, sx, y + 1);
                sx += 18;
            }
            String offerLabel = Math.max(1, listing.getOfferCount()) + "x " + itemName(listing.getOfferItemId());
            if (listing.getMaxUses() > 0) {
                offerLabel += " §7(" + listing.getUses() + "/" + listing.getMaxUses() + " left)";
            }
            offerLabel = this.font.plainSubstrByWidth(offerLabel,
                    Math.max(0, sellColX + sellColW - reasonW - sx));
            graphics.drawString(this.font, offerLabel, sx, y + 5,
                    isSel ? UiTheme.ACCENT : UiTheme.TEXT);

            if (reason != null) {
                graphics.drawString(this.font, reason,
                        contentRight() - this.font.width(reason), y + 5, UiTheme.DANGER);
            }
        }
        graphics.disableScissor();

        if (listings.isEmpty()) {
            renderEmpty(graphics, "No stock — an admin can add listings via /storynpcs npc trade add.");
        } else if (listings.size() > maxVisibleRows()) {
            graphics.drawString(this.font, "§7scroll for more (" + (scrollOffset + 1) + "-"
                            + Math.min(listings.size(), scrollOffset + maxVisibleRows()) + "/" + listings.size() + ")",
                    contentLeft(), contentBottom() - 9, UiTheme.TEXT_MUTED);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int bandBottom = Math.min(rowsTop() + maxVisibleRows() * ROW_H, contentBottom());
        if (button == 0
                && mouseX >= contentLeft() && mouseX < contentRight()
                && mouseY >= rowsTop() && mouseY < bandBottom) {
            int index = scrollOffset + (int) ((mouseY - rowsTop()) / ROW_H);
            if (index >= 0 && index < listings().size()) {
                if (index == selected) {
                    buySelected(); // click-again activates
                } else {
                    selected = index;
                    selectedKey = listingKey(listings().get(index));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            if (getFocused() == null && selected >= 0) {
                buySelected();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        int max = listings().size() - 1;
        int delta = switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> 1;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> -1;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> maxVisibleRows();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> -maxVisibleRows();
            default -> 0;
        };
        if (delta != 0 && max >= 0) {
            selected = selected < 0
                    ? (delta > 0 ? 0 : max)
                    : Math.max(0, Math.min(max, selected + delta));
            if (selected >= 0) {
                selectedKey = listingKey(listings().get(selected));
            }
            ensureSelectedVisible();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void ensureSelectedVisible() {
        if (selected < 0) return;
        if (selected < scrollOffset) {
            scrollOffset = selected;
        } else if (selected >= scrollOffset + maxVisibleRows()) {
            scrollOffset = selected - maxVisibleRows() + 1;
        }
    }

    /**
     * Renders an item icon + "Nx name" clipped to {@code maxW}; returns the
     * width consumed so a second price item can follow on the same row.
     */
    private int itemLabel(GuiGraphics graphics, int x, int y, int maxW, String itemId, int count) {
        if (maxW <= 0 || itemId == null || itemId.isBlank()) {
            return 0;
        }
        int used = 0;
        ItemStack stack = itemStack(itemId);
        if (stack != null && maxW >= 18) {
            graphics.renderItem(stack, x, y + 1);
            used += 18;
        }
        String label = this.font.plainSubstrByWidth(count + "x " + itemName(itemId),
                Math.max(0, maxW - used));
        graphics.drawString(this.font, label, x + used, y + 5, UiTheme.TEXT);
        return used + this.font.width(label);
    }

    private static String itemName(String itemId) {
        ItemStack stack = itemStack(itemId);
        return stack != null ? stack.getHoverName().getString() : itemId;
    }

    private static ItemStack itemStack(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return null;
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl)
                .map(ItemStack::new)
                .orElse(null);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, listings().size() - maxVisibleRows());
        int next = scrollOffset + (scrollY < 0 ? 1 : -1);
        if (next >= 0 && next <= maxScroll) {
            scrollOffset = next;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
