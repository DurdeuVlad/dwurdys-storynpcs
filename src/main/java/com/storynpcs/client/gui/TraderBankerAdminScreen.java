package com.storynpcs.client.gui;

import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.editor.TraderBankerAdminScreenModel;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin trader/banker configuration editor (issue #44) — reachable from
 * {@link NpcEditorScreen}. Distinct from the player-facing {@link NpcTradeScreen}
 * / {@link NpcBankScreen}, which remain unchanged. Mutations go through the same
 * whole-definition save payload -&gt; saveNpc path as {@link NpcRulesScreen}, so
 * server-side validation is identical to the command/editor path.
 */
public class TraderBankerAdminScreen extends Screen {

    private static final int ROW_H = 12;
    private static final int COLOR_OK = 0xFF4ADE80;
    private static final int COLOR_ERR = 0xFFF87171;
    private static final int COLOR_LABEL = 0xFFA1A1AA;

    private final TraderBankerAdminScreenModel model;
    private final List<Button> listingRowButtons = new ArrayList<>();
    private int listScroll;
    private int armedRemove = -1;
    private long expectedRevision;
    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();

    private EditBox listingIdField;
    private EditBox offerItemIdField;
    private EditBox offerCountField;
    private EditBox priceItemIdField;
    private EditBox priceCountField;
    private EditBox maxUsesField;
    private EditBox requiredFactionField;
    private EditBox requiredFactionPointsField;

    private EditBox bankNameField;
    private EditBox maxTabsField;
    private EditBox tabUpgradeCostField;

    public TraderBankerAdminScreen(NpcDefinition npc) {
        this(npc, 0L);
    }

    public TraderBankerAdminScreen(NpcDefinition npc, long expectedRevision) {
        super(Component.literal("Trade & Bank Admin"));
        this.model = new TraderBankerAdminScreenModel(npc);
        this.expectedRevision = Math.max(0L, expectedRevision);
    }

    public TraderBankerAdminScreenModel getModel() { return model; }

    public void onSaveResult(UUID requestId, boolean success, String message, long revision) {
        if (!saveRequestId.matchesCurrent(requestId)) return;
        model.setStatus(message, !success);
        expectedRevision = Math.max(0L, revision);
        saveRequestId.acknowledge(requestId);
    }

    @Override
    protected void init() {
        super.init();
        if (model.isEditingListing()) {
            initListingEditWidgets();
        } else if (model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER) {
            initTraderListWidgets();
        } else {
            initBankerWidgets();
        }
    }

    // ── Tab bar (shared by both non-edit modes) ─────────────────────────────

    private void initTabBar() {
        boolean trader = model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER;
        addRenderableWidget(Button.builder(Component.literal(trader ? "§e[Trader]" : "Trader"), b -> {
            model.setTab(TraderBankerAdminScreenModel.Tab.TRADER);
            rebuildWidgets();
        }).bounds(12, 4, 60, 14).build());
        addRenderableWidget(Button.builder(Component.literal(trader ? "Banker" : "§e[Banker]"), b -> {
            model.setTab(TraderBankerAdminScreenModel.Tab.BANKER);
            rebuildWidgets();
        }).bounds(74, 4, 60, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b ->
                minecraft.setScreen(new NpcEditorScreen(model.getNpc())))
                .bounds(this.width - 56, 4, 46, 14).build());
    }

    // ── Trader tab: listing list ─────────────────────────────────────────────

    private void initTraderListWidgets() {
        initTabBar();
        refreshListingRowButtons();

        addRenderableWidget(Button.builder(Component.literal("§a+ Add Listing"), b -> {
            model.beginAddListing();
            rebuildWidgets();
        }).bounds(12, this.height - 22, 100, 16).build());
    }

    private void refreshListingRowButtons() {
        for (Button b : listingRowButtons) removeWidget(b);
        listingRowButtons.clear();
        List<TradeListing> listings = model.getListings();
        int top = 22;
        int bottom = this.height - 26;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, listings.size() - maxRows)));

        for (int i = 0; i < maxRows && i + listScroll < listings.size(); i++) {
            int idx = i + listScroll;
            int ry = top + i * ROW_H;
            boolean armed = armedRemove == idx;

            Button editBtn = Button.builder(Component.literal("Edit"), b -> {
                armedRemove = -1;
                model.beginEditListing(idx);
                rebuildWidgets();
            }).bounds(this.width - 84, ry, 38, 11).build();
            listingRowButtons.add(editBtn);
            addRenderableWidget(editBtn);

            Button removeBtn = Button.builder(Component.literal(armed ? "§cSure?" : "§c✕"), b -> {
                if (armedRemove != idx) {
                    armedRemove = idx;
                    rebuildWidgets();
                } else {
                    armedRemove = -1;
                    String err = model.removeListing(idx);
                    if (err == null) sendSave("Removing listing...");
                    rebuildWidgets();
                }
            }).bounds(this.width - 42, ry, 30, 11).build();
            listingRowButtons.add(removeBtn);
            addRenderableWidget(removeBtn);
        }
    }

    // ── Trader tab: add/edit a listing ──────────────────────────────────────

    private void initListingEditWidgets() {
        int y = 22;
        int labelX = 12;
        int fieldX = 96;

        listingIdField = argField(fieldX, y, "listing id (optional)", model.getListingIdField());
        listingIdField.setResponder(model::setListingIdField);
        y += 16;

        offerItemIdField = argField(fieldX, y, "e.g. minecraft:emerald", model.getOfferItemIdField());
        offerItemIdField.setResponder(model::setOfferItemIdField);
        y += 16;

        offerCountField = argField(fieldX, y, "1-64", model.getOfferCountField());
        offerCountField.setResponder(model::setOfferCountField);
        y += 16;

        priceItemIdField = argField(fieldX, y, "e.g. minecraft:diamond", model.getPriceItemIdField());
        priceItemIdField.setResponder(model::setPriceItemIdField);
        y += 16;

        priceCountField = argField(fieldX, y, "1-64", model.getPriceCountField());
        priceCountField.setResponder(model::setPriceCountField);
        y += 16;

        maxUsesField = argField(fieldX, y, "0 = unlimited", model.getMaxUsesField());
        maxUsesField.setResponder(model::setMaxUsesField);
        y += 16;

        requiredFactionField = argField(fieldX, y, "faction id (optional)", model.getRequiredFactionField());
        requiredFactionField.setResponder(model::setRequiredFactionField);
        y += 16;

        requiredFactionPointsField = argField(fieldX, y, "min points", model.getRequiredFactionPointsField());
        requiredFactionPointsField.setResponder(model::setRequiredFactionPointsField);

        int footer = this.height - 22;
        addRenderableWidget(Button.builder(Component.literal("§aSave Listing"), b -> {
            String err = model.commitListing();
            if (err == null) sendSave("Saving listing...");
            rebuildWidgets();
        }).bounds(12, footer, 90, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            model.cancelEditListing();
            rebuildWidgets();
        }).bounds(108, footer, 60, 16).build());
    }

    private EditBox argField(int x, int y, String hint, String initial) {
        EditBox box = new EditBox(this.font, x, y, 160, 14, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setMaxLength(96);
        box.setValue(initial == null ? "" : initial);
        addRenderableWidget(box);
        return box;
    }

    // ── Banker tab ───────────────────────────────────────────────────────────

    private void initBankerWidgets() {
        initTabBar();
        int y = 26;

        addRenderableWidget(labeled("Bank name", 12, y));
        bankNameField = argField(96, y, "bank name", model.getBankNameField());
        bankNameField.setResponder(model::setBankNameField);
        y += 18;

        addRenderableWidget(labeled("Max tabs", 12, y));
        maxTabsField = argField(96, y, "1-" + TraderBankerAdminScreenModel.MAX_TABS, model.getMaxTabsField());
        maxTabsField.setResponder(model::setMaxTabsField);
        y += 18;

        addRenderableWidget(labeled("Tab upgrade cost", 12, y));
        tabUpgradeCostField = argField(96, y, "0+", model.getTabUpgradeCostField());
        tabUpgradeCostField.setResponder(model::setTabUpgradeCostField);
        y += 22;

        addRenderableWidget(Button.builder(Component.literal("§aSave"), b -> {
            String err = model.commitBankerConfig();
            if (err == null) sendSave("Saving bank config...");
            rebuildWidgets();
        }).bounds(12, y, 60, 16).build());
    }

    private Button labeled(String text, int x, int y) {
        // A disabled, unclickable button is used purely as a positioned label,
        // matching the plain-text-row convention the rest of this screen's
        // rendering (drawString) does not otherwise reuse for form labels.
        Button b = Button.builder(Component.literal(text), ignored -> {}).bounds(x, y, 80, 14).build();
        b.active = false;
        return b;
    }

    // ── Save plumbing (same payload as the NPC editor — saveNpc path) ───────

    private void sendSave(String pendingMessage) {
        model.setStatus(pendingMessage, false);
        NpcDefinition def = model.getNpc();
        String submittedJson = NpcDefinitionSerde.toJson(def);
        UUID requestId = saveRequestId.forPayload(submittedJson);
        PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                def.getId().toString(), submittedJson, expectedRevision, requestId));
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xE0101014);
        g.renderOutline(0, 0, this.width, this.height, 0xFF3F3F46);

        if (model.isEditingListing()) {
            renderListingEdit(g);
        } else if (model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER) {
            renderTraderList(g, mouseX, mouseY);
        } else {
            renderBanker(g);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private void renderTraderList(GuiGraphics g, int mouseX, int mouseY) {
        var npc = model.getNpc();
        g.drawString(this.font, "§6Trade Listings — §e" + (npc.getId() != null ? npc.getId() : "?"), 140, 8, 0xFFFFFFFF);

        var listings = model.getListings();
        int top = 22;
        int bottom = this.height - 26;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);

        if (listings.isEmpty()) {
            g.drawString(this.font, "§7No trade listings — add one below.", 12, top + 4, COLOR_LABEL);
        }
        g.enableScissor(0, top, this.width, bottom);
        for (int i = 0; i < maxRows && i + listScroll < listings.size(); i++) {
            int idx = i + listScroll;
            int ry = top + i * ROW_H;
            boolean hover = mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) g.fill(4, ry, this.width - 4, ry + ROW_H, 0x33FFFFFF);
            String line = "§7[" + (idx + 1) + "] §f" + model.describeListing(listings.get(idx));
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.width - 132),
                    10, ry + 2, hover ? 0xFFFFFFFF : 0xFFD4D4D8);
        }
        g.disableScissor();
        drawStatus(g, this.height - 10);
    }

    private void renderListingEdit(GuiGraphics g) {
        g.drawString(this.font, (model.isAddingListing() ? "§6Add Listing" : "§6Edit Listing") + " — §e"
                + model.getNpc().getId(), 12, 8, 0xFFFFFFFF);
        String[] labels = {"Listing ID", "Offer item", "Offer count", "Price item", "Price count",
                "Max uses", "Req. faction", "Req. points"};
        int y = 22;
        for (String label : labels) {
            g.drawString(this.font, label, 12, y + 3, COLOR_LABEL);
            y += 16;
        }
        if (!model.isAddingListing()) {
            g.drawString(this.font, "§7Uses (read-only): " + model.currentUsesForEditingListing(), 12, y + 4, COLOR_LABEL);
        }
        drawStatus(g, this.height - 8);
    }

    private void renderBanker(GuiGraphics g) {
        g.drawString(this.font, "§6Bank Configuration — §e" + model.getNpc().getId(), 140, 8, 0xFFFFFFFF);
        drawStatus(g, this.height - 10);
    }

    private void drawStatus(GuiGraphics g, int y) {
        if (!model.getStatusMessage().isEmpty()) {
            String msg = this.font.plainSubstrByWidth(model.getStatusMessage(), this.width - 24);
            g.drawString(this.font, msg, 12, y, model.isStatusError() ? COLOR_ERR : COLOR_OK);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (!model.isEditingListing() && model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(dy));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
