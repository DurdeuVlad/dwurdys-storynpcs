package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TraderRole;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TraderBankerAdminScreenModel} — the client-side builder
 * behind the trade/bank admin editor (issue #44).
 */
class TraderBankerAdminScreenModelTest {

    private NpcDefinition newNpc() {
        return new NpcDefinition(NamespacedId.of("storynpcs:test_merchant"), "Test Merchant");
    }

    @Test
    void newNpcWithNoTraderRoleHasEmptyListings() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        assertThat(model.getListings()).isEmpty();
    }

    @Test
    void addingAListingCreatesTraderRoleOnDemand() {
        var npc = newNpc();
        assertThat(npc.getTrader()).isNull();
        var model = new TraderBankerAdminScreenModel(npc);

        model.beginAddListing();
        model.setOfferItemIdField("minecraft:emerald");
        model.setOfferCountField("3");
        model.setPriceItemIdField("minecraft:diamond");
        model.setPriceCountField("1");
        String err = model.commitListing();

        assertThat(err).isNull();
        assertThat(npc.getTrader()).isNotNull();
        assertThat(model.getListings()).hasSize(1);
        var listing = model.getListings().get(0);
        assertThat(listing.getOfferItemId()).isEqualTo("minecraft:emerald");
        assertThat(listing.getOfferCount()).isEqualTo(3);
        assertThat(listing.getPriceItemId()).isEqualTo("minecraft:diamond");
        assertThat(listing.getListingId()).isNotBlank(); // auto-assigned stable id
    }

    @Test
    void blankOfferItemIsRejectedWithoutMutatingState() {
        var npc = newNpc();
        var model = new TraderBankerAdminScreenModel(npc);
        model.beginAddListing();
        model.setPriceItemIdField("minecraft:diamond");
        String err = model.commitListing();

        assertThat(err).isNotNull();
        assertThat(err).contains("Offer item");
        assertThat(npc.getTrader()).isNull();
    }

    @Test
    void offerCountOutOfBoundsIsRejected() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.beginAddListing();
        model.setOfferItemIdField("minecraft:emerald");
        model.setOfferCountField("999");
        model.setPriceItemIdField("minecraft:diamond");
        String err = model.commitListing();

        assertThat(err).isNotNull();
        assertThat(err).contains("offer count");
    }

    @Test
    void editingAnExistingListingPreservesUsesAndUpdatesFields() {
        var npc = newNpc();
        TraderRole trader = new TraderRole();
        var original = new com.storynpcs.domain.role.trader.TradeListing("minecraft:emerald", 2, "minecraft:diamond", 1);
        original.setMaxUses(5);
        original.reserveTrade(); // uses == 1
        trader.addListing(original);
        npc.setTrader(trader);

        var model = new TraderBankerAdminScreenModel(npc);
        boolean loaded = model.beginEditListing(0);
        assertThat(loaded).isTrue();
        assertThat(model.currentUsesForEditingListing()).isEqualTo(1);

        model.setOfferCountField("4"); // change offer count
        String err = model.commitListing();

        assertThat(err).isNull();
        assertThat(model.getListings()).hasSize(1);
        var updated = model.getListings().get(0);
        assertThat(updated.getOfferCount()).isEqualTo(4);
        assertThat(updated.getUses()).isEqualTo(1); // preserved, not reset
    }

    @Test
    void editingOutOfRangeIndexFails() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        assertThat(model.beginEditListing(0)).isFalse();
    }

    @Test
    void removeListingRejectsOutOfRangeIndex() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        String err = model.removeListing(0);
        assertThat(err).isNotNull();
        assertThat(err).contains("out of range");
    }

    @Test
    void removeListingDeletesTheListing() {
        var npc = newNpc();
        var model = new TraderBankerAdminScreenModel(npc);
        model.beginAddListing();
        model.setOfferItemIdField("minecraft:emerald");
        model.setPriceItemIdField("minecraft:diamond");
        model.commitListing();
        assertThat(model.getListings()).hasSize(1);

        String err = model.removeListing(0);
        assertThat(err).isNull();
        assertThat(model.getListings()).isEmpty();
    }

    @Test
    void requiredFactionFieldParsesNamespacedId() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.beginAddListing();
        model.setOfferItemIdField("minecraft:emerald");
        model.setPriceItemIdField("minecraft:diamond");
        model.setRequiredFactionField("storynpcs:townsfolk");
        model.setRequiredFactionPointsField("50");
        String err = model.commitListing();

        assertThat(err).isNull();
        var listing = model.getListings().get(0);
        assertThat(listing.getRequiredFaction()).isEqualTo(NamespacedId.of("storynpcs:townsfolk"));
        assertThat(listing.getRequiredFactionPoints()).isEqualTo(50);
    }

    @Test
    void malformedRequiredFactionIsRejected() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.beginAddListing();
        model.setOfferItemIdField("minecraft:emerald");
        model.setPriceItemIdField("minecraft:diamond");
        model.setRequiredFactionField("not a namespaced id!!");
        String err = model.commitListing();

        assertThat(err).isNotNull();
        assertThat(err).contains("required faction");
    }

    @Test
    void newNpcDefaultsBankerFieldsForDisplayWithoutCreatingRole() {
        var npc = newNpc();
        var model = new TraderBankerAdminScreenModel(npc);
        assertThat(npc.getBanker()).isNull();
        assertThat(model.getBankNameField()).isEqualTo("Standard Vault");
        assertThat(model.getMaxTabsField()).isEqualTo("4");
        assertThat(model.getTabUpgradeCostField()).isEqualTo("1000");
    }

    @Test
    void savingBankerConfigCreatesRoleOnDemandAndAppliesFields() {
        var npc = newNpc();
        var model = new TraderBankerAdminScreenModel(npc);
        model.setBankNameField("Iron Vault");
        model.setMaxTabsField("6");
        model.setTabUpgradeCostField("2500");

        String err = model.commitBankerConfig();

        assertThat(err).isNull();
        BankerRole banker = npc.getBanker();
        assertThat(banker).isNotNull();
        assertThat(banker.getBankName()).isEqualTo("Iron Vault");
        assertThat(banker.getMaxTabs()).isEqualTo(6);
        assertThat(banker.getTabUpgradeCost()).isEqualTo(2500);
    }

    @Test
    void bankerConfigLoadsExistingRoleFields() {
        var npc = newNpc();
        BankerRole existing = new BankerRole("Old Vault");
        existing.setMaxTabs(2);
        existing.setTabUpgradeCost(500);
        npc.setBanker(existing);

        var model = new TraderBankerAdminScreenModel(npc);
        assertThat(model.getBankNameField()).isEqualTo("Old Vault");
        assertThat(model.getMaxTabsField()).isEqualTo("2");
        assertThat(model.getTabUpgradeCostField()).isEqualTo("500");
    }

    @Test
    void blankBankNameIsRejected() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.setBankNameField("   ");
        String err = model.commitBankerConfig();
        assertThat(err).isNotNull();
        assertThat(err).contains("Bank name");
    }

    @Test
    void maxTabsOutOfBoundsIsRejected() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.setMaxTabsField("0"); // below MIN_TABS
        String err = model.commitBankerConfig();
        assertThat(err).isNotNull();
        assertThat(err).contains("max tabs");

        model.setMaxTabsField(Integer.toString(TraderBankerAdminScreenModel.MAX_TABS + 1));
        err = model.commitBankerConfig();
        assertThat(err).isNotNull();
        assertThat(err).contains("max tabs");
    }

    @Test
    void negativeTabUpgradeCostIsRejected() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.setTabUpgradeCostField("-1");
        String err = model.commitBankerConfig();
        assertThat(err).isNotNull();
        assertThat(err).contains("tab upgrade cost");
    }

    @Test
    void switchingTabsExitsListingEditModeAndClearsStatus() {
        var model = new TraderBankerAdminScreenModel(newNpc());
        model.beginAddListing();
        model.setStatus("some error", true);
        assertThat(model.isEditingListing()).isTrue();

        model.setTab(TraderBankerAdminScreenModel.Tab.BANKER);

        assertThat(model.isEditingListing()).isFalse();
        assertThat(model.getStatusMessage()).isEmpty();
        assertThat(model.getTab()).isEqualTo(TraderBankerAdminScreenModel.Tab.BANKER);
    }
}
