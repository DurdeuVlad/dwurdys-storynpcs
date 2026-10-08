package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.FieldValidator;
import com.storynpcs.client.ui.widgets.FormRow;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.editor.TraderBankerAdminScreenModel;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin trader/banker configuration editor (issue #44) — reachable from
 * {@link NpcEditorScreen}. Distinct from the player-facing {@link NpcTradeScreen}
 * / {@link NpcBankScreen}. Mutations go through the same whole-definition save
 * payload -&gt; saveNpc path as {@link NpcRulesScreen}, so server-side
 * validation is identical to the command/editor path.
 *
 * <p>Migrated onto the shared chrome (#207): Trader/Banker tabs inside the
 * bounded panel, the listing table is a {@link SelectableList} with footer
 * actions (armed Remove), and the add/edit form uses {@link FormRow} with
 * per-field inline validation.
 */
public class TraderBankerAdminScreen extends UiScreen {

    private final TraderBankerAdminScreenModel model;
    private final SelectionModel<Integer> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();
    private final List<FormRow> fieldRows = new ArrayList<>();
    private SelectableList<ListingRow, Integer> listingList;
    /** Armed remove is bound to the selected listing index. */
    private int armedListing = -1;
    private long expectedRevision;
    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();
    private String lastEchoed;

    /** Selection identity is the absolute listing index. */
    private record ListingRow(int index, String label) {}

    public TraderBankerAdminScreen(NpcDefinition npc) {
        this(npc, 0L);
    }

    public TraderBankerAdminScreen(NpcDefinition npc, long expectedRevision) {
        super(Component.literal("Trade & Bank Admin — "
                + (npc.getId() != null ? npc.getId().toString() : "?")));
        this.model = new TraderBankerAdminScreenModel(npc);
        this.expectedRevision = Math.max(0L, expectedRevision);
    }

    public TraderBankerAdminScreenModel getModel() { return model; }

    public void onSaveResult(UUID requestId, boolean success, String message, long revision) {
        if (!saveRequestId.matchesCurrent(requestId)) return;
        model.setStatus(message, !success);
        syncStatus();
        // The response revision is authoritative on success AND on rejection.
        expectedRevision = Math.max(0L, revision);
        saveRequestId.acknowledge(requestId);
    }

    @Override
    protected void onStatusExpired() {
        lastEchoed = null; // identical later results may echo again
    }

    private void syncStatus() {
        String msg = model.getStatusMessage();
        if (!msg.isEmpty() && !msg.equals(lastEchoed)) {
            lastEchoed = msg;
            echo(Component.literal(msg)
                    .withColor(model.isStatusError() ? UiTheme.DANGER : UiTheme.TEXT), 4000);
        }
    }

    @Override
    protected void initContent() {
        fieldRows.clear();
        if (model.isEditingListing()) {
            initListingEdit();
        } else if (model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER) {
            initTabBar();
            initTraderList();
        } else {
            initTabBar();
            initBanker();
        }
    }

    // ── Tab bar (shared by both non-edit modes) ─────────────────────────────

    private void initTabBar() {
        boolean trader = model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER;
        int tabW = 90;
        Button traderBtn = Button.builder(Component.literal("Trader"), b -> {
            model.setTab(TraderBankerAdminScreenModel.Tab.TRADER);
            rebuildWidgets();
        }).bounds(contentLeft(), contentTop(), tabW, UiTheme.BUTTON_H).build();
        traderBtn.active = !trader;
        addRenderableWidget(traderBtn);
        Button bankerBtn = Button.builder(Component.literal("Banker"), b -> {
            model.setTab(TraderBankerAdminScreenModel.Tab.BANKER);
            rebuildWidgets();
        }).bounds(contentLeft() + tabW + UiTheme.PAD_S, contentTop(), tabW,
                UiTheme.BUTTON_H).build();
        bankerBtn.active = trader;
        addRenderableWidget(bankerBtn);
    }

    // ── Trader tab: listing list ─────────────────────────────────────────────

    private void initTraderList() {
        int listTop = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;
        listingList = new SelectableList<>(contentLeft(), listTop, contentWidth(),
                Math.max(1, contentBottom() - listTop), UiTheme.ROW_H,
                ListingRow::index,
                r -> Component.literal("§7[" + (r.index() + 1) + "] §f" + r.label()),
                selection, scroll);
        addRenderableWidget(listingList);
        refreshListings();

        addFooterAction(Component.literal("+ Add Listing"), b -> {
            model.beginAddListing();
            armedListing = -1;
            rebuildWidgets();
        });
        addFooterAction(Component.literal("Edit"), b -> {
            ListingRow row = listingList.selectedRow();
            if (row == null) {
                echo(Component.literal("Select a listing first."), 1600);
                return;
            }
            armedListing = -1;
            model.beginEditListing(row.index());
            rebuildWidgets();
        });
        addFooterAction(Component.literal(armedListing >= 0 ? "Sure?" : "Remove"), b -> {
            ListingRow row = listingList.selectedRow();
            if (row == null) {
                if (armedListing >= 0) {
                    armedListing = -1;
                    rebuildWidgets();
                }
                echo(Component.literal("Select a listing first."), 1600);
                return;
            }
            if (armedListing != row.index()) {
                armedListing = row.index();
                rebuildWidgets();
                return;
            }
            armedListing = -1;
            String err = model.removeListing(row.index());
            if (err == null) {
                sendSave("Removing listing...");
            }
            // Rebuild first — init() clears the status slot before the echo.
            rebuildWidgets();
            syncStatus();
        });
        addFooterAction(Component.literal("Back"), b ->
                minecraft.setScreen(new NpcEditorScreen(model.getNpc(), expectedRevision)));
        setStatus(Component.literal("Select a listing — Edit or Remove (asks to confirm).")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private void refreshListings() {
        List<TradeListing> listings = model.getListings();
        List<ListingRow> rows = new ArrayList<>(listings.size());
        for (int i = 0; i < listings.size(); i++) {
            rows.add(new ListingRow(i, model.describeListing(listings.get(i))));
        }
        listingList.setRows(rows);
    }

    // ── Add / edit a listing ────────────────────────────────────────────────

    private void initListingEdit() {
        int labelW = Math.min(72, contentWidth() / 4);
        int colW = Math.max(60, (contentWidth() - UiTheme.PAD_L) / 2);
        int leftX = contentLeft();
        int rightX = contentLeft() + colW + UiTheme.PAD_L;
        int y = contentTop();

        FieldValidator requiredWhole = wholeNumber(true);
        FieldValidator nsId = FieldValidator.namespacedId();
        FieldValidator optionalNsId = v -> v == null || v.isBlank() ? null : nsId.validate(v);
        FieldValidator requiredNsId = FieldValidator.all(FieldValidator.required("item id"), nsId);

        // Left column
        y = argRow(leftX, y, colW, labelW, "Listing ID", "optional",
                model::getListingIdField, model::setListingIdField, null);
        y = argRow(leftX, y, colW, labelW, "Offer item", "e.g. minecraft:emerald",
                model::getOfferItemIdField, model::setOfferItemIdField, requiredNsId);
        y = argRow(leftX, y, colW, labelW, "Offer count", "1-64",
                model::getOfferCountField, model::setOfferCountField, requiredWhole);
        y = argRow(leftX, y, colW, labelW, "Price item", "e.g. minecraft:diamond",
                model::getPriceItemIdField, model::setPriceItemIdField, requiredNsId);
        y = argRow(leftX, y, colW, labelW, "Price count", "1-64",
                model::getPriceCountField, model::setPriceCountField, requiredWhole);
        argRow(leftX, y, colW, labelW, "Max uses", "0 = unlimited",
                model::getMaxUsesField, model::setMaxUsesField, requiredWhole);

        // Right column
        y = contentTop();
        y = argRow(rightX, y, colW, labelW, "2nd item", "optional second input",
                model::getSecondaryPriceItemIdField, model::setSecondaryPriceItemIdField,
                optionalNsId);
        y = argRow(rightX, y, colW, labelW, "2nd count", "0",
                model::getSecondaryPriceCountField, model::setSecondaryPriceCountField,
                wholeNumber(true));
        y = argRow(rightX, y, colW, labelW, "Page", "0-99",
                model::getPageField, model::setPageField, wholeNumber(true));
        y = argRow(rightX, y, colW, labelW, "Restock ticks", "0 = role default",
                model::getRestockIntervalField, model::setRestockIntervalField,
                wholeNumber(true));
        y = argRow(rightX, y, colW, labelW, "Req. faction", "optional",
                model::getRequiredFactionField, model::setRequiredFactionField, optionalNsId);
        argRow(rightX, y, colW, labelW, "Req. points", "min points",
                model::getRequiredFactionPointsField, model::setRequiredFactionPointsField,
                wholeNumber(true));

        addFooterAction(Component.literal("Save Listing"), b -> {
            boolean invalid = false;
            for (FormRow row : fieldRows) {
                if (!row.validate()) invalid = true;
            }
            if (invalid) {
                echo(Component.literal("Fix the highlighted field(s).")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            String err = model.commitListing();
            if (err == null) {
                sendSave("Saving listing...");
            }
            rebuildWidgets();
            syncStatus();
        });
        addFooterAction(Component.literal("Cancel"), b -> {
            model.cancelEditListing();
            rebuildWidgets();
        });
        setStatus(Component.literal(model.isAddingListing()
                        ? "New listing — offer on the left is what you give, price is what you pay."
                        : "Editing listing — uses counter is preserved.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    private int argRow(int x, int y, int w, int labelW, String label, String hint,
                       java.util.function.Supplier<String> seed,
                       java.util.function.Consumer<String> responder,
                       FieldValidator check) {
        FormRow row = new FormRow(this.font, Component.literal(label), Component.literal(hint));
        row.editBox().setMaxLength(96);
        // Seed first: FormRow#setValidator validates immediately, and an
        // un-seeded row would push "" back into the model on every rebuild.
        row.setValue(seed.get());
        FieldValidator validator = check == null ? v -> null : check;
        row.setValidator(v -> { responder.accept(v); return validator.validate(v); });
        int nextY = row.layout(x, y, w, labelW);
        addRenderableWidget(row.editBox());
        fieldRows.add(row);
        return nextY;
    }

    private static FieldValidator wholeNumber(boolean required) {
        return v -> {
            if (v == null || v.isBlank()) {
                return required ? "Number required" : null;
            }
            try {
                Integer.parseInt(v.trim());
                return null;
            } catch (NumberFormatException e) {
                return "Not a whole number";
            }
        };
    }

    // ── Banker tab ───────────────────────────────────────────────────────────

    private void initBanker() {
        int labelW = Math.min(90, contentWidth() / 4);
        int w = Math.min(280, contentWidth());
        int y = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;

        y = argRow(contentLeft(), y, w, labelW, "Bank name", "bank name",
                model::getBankNameField, model::setBankNameField,
                FieldValidator.required("bank name"));
        y = argRow(contentLeft(), y, w, labelW, "Max tabs",
                "1-" + TraderBankerAdminScreenModel.MAX_TABS,
                model::getMaxTabsField, model::setMaxTabsField, wholeNumber(true));
        argRow(contentLeft(), y, w, labelW, "Tab upgrade cost", "0+",
                model::getTabUpgradeCostField, model::setTabUpgradeCostField,
                wholeNumber(true));

        addFooterAction(Component.literal("Save"), b -> {
            boolean invalid = false;
            for (FormRow row : fieldRows) {
                if (!row.validate()) invalid = true;
            }
            if (invalid) {
                echo(Component.literal("Fix the highlighted field(s).")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            String err = model.commitBankerConfig();
            if (err == null) {
                sendSave("Saving bank config...");
            }
            rebuildWidgets();
            syncStatus();
        });
        addFooterAction(Component.literal("Back"), b ->
                minecraft.setScreen(new NpcEditorScreen(model.getNpc(), expectedRevision)));
        setStatus(Component.literal("Bank tab config — Save persists via the NPC definition.")
                .withColor(UiTheme.TEXT_MUTED));
        syncStatus();
    }

    // ── Save plumbing (same payload as the NPC editor — saveNpc path) ───────

    private void sendSave(String pendingMessage) {
        NpcDefinition def = model.getNpc();
        if (def.getId() == null) {
            model.setStatus("Cannot save — NPC has no id.", true);
            return;
        }
        model.setStatus(pendingMessage, false);
        String submittedJson = NpcDefinitionSerde.toJson(def);
        UUID requestId = saveRequestId.forPayload(submittedJson);
        PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                def.getId().toString(), submittedJson, expectedRevision, requestId));
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // FormRow draws its label + error line here — the EditBox itself is a
        // widget and already rendered.
        for (FormRow row : fieldRows) {
            row.render(g, mouseX, mouseY);
        }
        if (model.isEditingListing()) {
            if (!model.isAddingListing()) {
                g.drawString(this.font, "§7Uses (read-only): " + model.currentUsesForEditingListing(),
                        contentLeft(), contentBottom() - 8, UiTheme.TEXT_MUTED);
            }
            return;
        }
        if (model.getTab() == TraderBankerAdminScreenModel.Tab.TRADER
                && model.getListings().isEmpty()) {
            renderEmpty(g, "No trade listings — add one below.");
        }
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
