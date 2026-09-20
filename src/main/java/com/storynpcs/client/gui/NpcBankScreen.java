package com.storynpcs.client.gui;

import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.network.ServerboundBankActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Comparator;
import java.util.List;

/**
 * Player-facing vault screen for a banker-role NPC. Opened server-side via
 * {@link com.storynpcs.network.ClientboundBankOpenPayload}; every action sends
 * {@link ServerboundBankActionPayload} and the server replies with a fresh open
 * packet, which re-renders this screen.
 */
public class NpcBankScreen extends Screen {

    private final String npcId;
    private final BankerRole banker;
    private final BankVault vault;
    private int currentTab = 0;
    private int scrollOffset = 0;

    public NpcBankScreen(String npcId, BankerRole banker, BankVault vault) {
        super(Component.literal(banker.getBankName()));
        this.npcId = npcId != null ? npcId : "";
        this.banker = banker;
        this.vault = vault;
    }

    private int listTop() { return 34; }
    private int listBottom() { return this.height - 34; }
    private int maxVisibleRows() { return Math.max(1, (listBottom() - listTop()) / 12); }
    private int unlockedTabs() { return Math.max(1, Math.min(vault.getUnlockedTabs(), Math.max(1, banker.getMaxTabs()))); }

    private List<BankVault.VaultItem> tabItems() {
        return vault.getTabItems(currentTab).stream()
                .sorted(Comparator.comparingInt(BankVault.VaultItem::getSlot))
                .toList();
    }

    @Override
    protected void init() {
        currentTab = Math.min(currentTab, unlockedTabs() - 1);

        // Tab row: [ < ] Tab X/Y [ > ] when multiple tabs are unlocked
        if (unlockedTabs() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"),
                            b -> { currentTab = Math.max(0, currentTab - 1); scrollOffset = 0; rebuildWidgets(); })
                    .bounds(12, 18, 16, 12).build());
            addRenderableWidget(Button.builder(Component.literal(">"),
                            b -> { currentTab = Math.min(unlockedTabs() - 1, currentTab + 1); scrollOffset = 0; rebuildWidgets(); })
                    .bounds(100, 18, 16, 12).build());
        }

        var items = tabItems();
        int maxScroll = Math.max(0, items.size() - maxVisibleRows());
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int rowW = Math.min(this.width - 24, 340);
        int wdW = 56;
        int visible = Math.min(items.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            var item = items.get(scrollOffset + i);
            int y = listTop() + i * 12;
            int tab = currentTab;
            int slot = item.getSlot();
            addRenderableWidget(Button.builder(Component.literal("Withdraw"),
                            b -> PacketDistributor.sendToServer(
                                    new ServerboundBankActionPayload(npcId, "withdraw", tab, slot)))
                    .bounds(12 + rowW - wdW, y, wdW, 11)
                    .build());
        }

        // Footer: deposit held + unlock + close
        int footerY = this.height - 16;
        addRenderableWidget(Button.builder(Component.literal("Deposit held"), b ->
                        PacketDistributor.sendToServer(
                                new ServerboundBankActionPayload(npcId, "deposit_held", currentTab, 0)))
                .bounds(12, footerY, 90, 12).build());

        if (unlockedTabs() < Math.max(1, banker.getMaxTabs())) {
            int cost = Math.max(0, banker.getTabUpgradeCost());
            String label = cost > 0
                    ? "Unlock tab (" + cost + " emeralds)"
                    : "Unlock tab (free)";
            addRenderableWidget(Button.builder(Component.literal(label), b ->
                            PacketDistributor.sendToServer(
                                    new ServerboundBankActionPayload(npcId, "unlock_tab", 0, 0)))
                    .bounds(108, footerY, Math.min(140, this.width - 180), 12).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(this.width - 72, footerY, 60, 12).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        String title = banker.getBankName();
        graphics.drawString(this.font, title, 12, 6, 0xFFFFFF);

        String tabLabel = "Tab " + (currentTab + 1) + "/" + unlockedTabs();
        graphics.drawString(this.font, tabLabel,
                unlockedTabs() > 1 ? 34 : 12, 20, 0xAAAAAA);

        var items = tabItems();
        int rowW = Math.min(this.width - 24, 340);
        int textW = rowW - 60;

        graphics.enableScissor(0, listTop() - 1, this.width, listBottom() + 1);
        int visible = Math.min(items.size() - scrollOffset, maxVisibleRows());
        for (int i = 0; i < visible; i++) {
            var item = items.get(scrollOffset + i);
            int y = listTop() + i * 12;
            String label = item.getCount() + "x " + itemName(item.getItemId());
            if (this.font.width(label) > textW) {
                label = this.font.plainSubstrByWidth(label, textW);
            }
            graphics.drawString(this.font, label, 12, y + 2, 0xFFFFFF);
        }
        graphics.disableScissor();

        if (items.isEmpty()) {
            graphics.drawString(this.font, "§7(empty — hold an item and press Deposit held)",
                    12, listTop() + 4, 0xAAAAAA);
        } else if (items.size() > maxVisibleRows()) {
            graphics.drawString(this.font, "§7scroll for more",
                    12, listBottom() + 2, 0xAAAAAA);
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
