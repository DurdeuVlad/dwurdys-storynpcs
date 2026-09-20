package com.storynpcs.client.gui;

import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TradeSummaries;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.network.ServerboundTradeExecutePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;

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
    private int scrollOffset = 0;

    public NpcTradeScreen(String npcId, String npcName, TraderRole trader, Map<String, Integer> factionScores) {
        super(Component.literal(trader.getMarketName()));
        this.npcId = npcId != null ? npcId : "";
        this.npcName = npcName != null ? npcName : "";
        this.trader = trader;
        this.factionScores = factionScores != null ? factionScores : Map.of();
    }

    private int listTop() { return 22; }
    private int listBottom() { return this.height - 20; }
    private int maxVisibleRows() { return Math.max(1, (listBottom() - listTop()) / 12); }

    @Override
    protected void init() {
        List<TradeListing> listings = trader.getListings();
        int maxScroll = Math.max(0, listings.size() - maxVisibleRows());
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int rowW = Math.min(this.width - 24, 340);
        int buyW = 44;
        int visible = Math.min(listings.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            int index = scrollOffset + i;
            int y = listTop() + i * 12;
            TradeListing listing = listings.get(index);
            int score = listing.getRequiredFaction() != null
                    ? factionScores.getOrDefault(listing.getRequiredFaction().toString(), 0)
                    : 0;
            boolean available = listing.isAvailable(score);
            Button buy = Button.builder(Component.literal("Buy"),
                            b -> PacketDistributor.sendToServer(
                                    new ServerboundTradeExecutePayload(npcId, index)))
                    .bounds(12 + rowW - buyW, y, buyW, 11)
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
        int rowW = Math.min(this.width - 24, 340);
        int textW = rowW - 48;

        graphics.enableScissor(0, listTop() - 1, this.width, listBottom() + 1);
        int visible = Math.min(listings.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            TradeListing listing = listings.get(scrollOffset + i);
            int y = listTop() + i * 12;
            String label = offerText(listing);
            if (this.font.width(label) > textW) {
                label = this.font.plainSubstrByWidth(label, textW);
            }
            graphics.drawString(this.font, label, 12, y + 2, 0xFFFFFF);

            int score = listing.getRequiredFaction() != null
                    ? factionScores.getOrDefault(listing.getRequiredFaction().toString(), 0)
                    : 0;
            String reason = TradeSummaries.unavailableReason(listing, score);
            if (reason != null) {
                graphics.drawString(this.font, reason, 12 + rowW - 48 - this.font.width(reason) - 4, y + 2, 0xFF5555);
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

    /** Player-facing row text: item display names where resolvable, raw ids otherwise. */
    private String offerText(TradeListing listing) {
        String offer = itemName(listing.getOfferItemId());
        String price = itemName(listing.getPriceItemId());
        String text = Math.max(1, listing.getOfferCount()) + "x " + offer
                + "  <-  " + Math.max(1, listing.getPriceCount()) + "x " + price;
        if (listing.getMaxUses() > 0) {
            text += "  (" + listing.getUses() + "/" + listing.getMaxUses() + " left)";
        }
        return text;
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
