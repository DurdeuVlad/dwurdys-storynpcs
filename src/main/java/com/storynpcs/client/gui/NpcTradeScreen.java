package com.storynpcs.client.gui;

import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TradeSummaries;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.network.ServerboundTradeExecutePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player-facing trade screen for a trader-role NPC. Opened server-side via
 * {@link com.storynpcs.network.ClientboundTradeOpenPayload}; clicking Buy sends
 * {@link ServerboundTradeExecutePayload} and the server replies with a fresh
 * open packet (or a failure message), which re-renders this screen.
 */
public class NpcTradeScreen extends Screen {

    private final String npcId;
    private final String npcName;
    private final TraderRole trader;
    private final Map<String, Integer> factionScores;
    private final UUID sessionId;
    private final Map<Integer, UUID> requestIds = new java.util.HashMap<>();
    private int scrollOffset = 0;

    public NpcTradeScreen(String npcId, String npcName, TraderRole trader, Map<String, Integer> factionScores,
                          UUID sessionId) {
        super(Component.literal(trader.getMarketName()));
        this.npcId = npcId != null ? npcId : "";
        this.npcName = npcName != null ? npcName : "";
        this.trader = trader;
        this.factionScores = factionScores != null ? factionScores : Map.of();
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    /** Row height fits a 16px item icon with a pixel of margin either side. */
    private static final int ROW_H = 18;
    private static final int BUY_W = 44;
    private static final int ARROW_W = 20;

    private int rowWidth() { return Math.min(this.width - 24, 460); }
    private int listTop() { return 30; }
    private int listBottom() { return this.height - 20; }
    private int maxVisibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW_H); }

    @Override
    protected void init() {
        List<TradeListing> listings = trader.getListings();
        int maxScroll = Math.max(0, listings.size() - maxVisibleRows());
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int rowW = rowWidth();
        int visible = Math.min(listings.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            int index = scrollOffset + i;
            int y = listTop() + i * ROW_H;
            TradeListing listing = listings.get(index);
            int score = listing.getRequiredFaction() != null
                    ? factionScores.getOrDefault(listing.getRequiredFaction().toString(), 0)
                    : 0;
            boolean available = listing.isAvailable(score);
            Button buy = Button.builder(Component.literal("Buy"),
                            b -> PacketDistributor.sendToServer(
                                    new ServerboundTradeExecutePayload(npcId, index, sessionId,
                                            requestIds.computeIfAbsent(index, ignored -> UUID.randomUUID()))))
                    .bounds(12 + rowW - BUY_W, y + 3, BUY_W, 12)
                    .build();
            buy.active = available;
            addRenderableWidget(buy);
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(12, this.height - 16, 60, 12)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        String title = trader.getMarketName() + " — " + npcName;
        int titleW = this.font.width(title);
        if (titleW > this.width - 24) {
            title = this.font.plainSubstrByWidth(title, this.width - 24);
        }
        graphics.drawString(this.font, title, 12, 8, 0xFFFFFF);

        List<TradeListing> listings = trader.getListings();
        int rowW = rowWidth();
        int buyX = 12 + rowW - BUY_W;
        // Buy-left / sell-right columns (#197): the trader buys the price on
        // the left, sells the offer on the right, each with an item icon.
        int buyColW = (rowW - BUY_W - 8 - ARROW_W) / 2;
        int sellColX = 12 + buyColW + ARROW_W;
        int sellColW = buyX - 4 - sellColX;

        graphics.drawString(this.font, "§7Trader buys", 12, listTop() - 9, 0xAAAAAA);
        graphics.drawString(this.font, "§7Trader sells", sellColX, listTop() - 9, 0xAAAAAA);

        graphics.enableScissor(0, listTop() - 1, this.width, listBottom() + 1);
        int visible = Math.min(listings.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            TradeListing listing = listings.get(scrollOffset + i);
            int y = listTop() + i * ROW_H;

            // Left column — the price: what the trader buys from the player.
            int px = 12;
            px += itemLabel(graphics, px, y, buyColW,
                    listing.getPriceItemId(), Math.max(1, listing.getPriceCount()));
            String secondaryId = listing.getSecondaryPriceItemId();
            if (secondaryId != null && !secondaryId.isBlank()
                    && listing.getSecondaryPriceCount() > 0 && px + 12 < 12 + buyColW) {
                graphics.drawString(this.font, "+", px + 2, y + 5, 0xAAAAAA);
                px += 10;
                itemLabel(graphics, px, y, 12 + buyColW - px,
                        secondaryId, Math.max(1, listing.getSecondaryPriceCount()));
            }
            graphics.drawString(this.font, "->", 12 + buyColW + 6, y + 5, 0xAAAAAA);

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
            graphics.drawString(this.font, offerLabel, sx, y + 5, 0xFFFFFF);

            if (reason != null) {
                graphics.drawString(this.font, reason, buyX - 4 - this.font.width(reason), y + 5, 0xFF5555);
            }
        }
        graphics.disableScissor();

        if (listings.isEmpty()) {
            graphics.drawString(this.font, "§7(no stock — an admin can add listings via /storynpcs npc trade add)",
                    12, listTop() + 4, 0xAAAAAA);
        } else if (listings.size() > maxVisibleRows()) {
            graphics.drawString(this.font, "§7scroll for more (" + (scrollOffset + 1) + "-" +
                    Math.min(listings.size(), scrollOffset + maxVisibleRows()) + "/" + listings.size() + ")",
                    12 + 66, this.height - 12, 0xAAAAAA);
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
        graphics.drawString(this.font, label, x + used, y + 5, 0xFFFFFF);
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
        int maxScroll = Math.max(0, trader.getListings().size() - maxVisibleRows());
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
