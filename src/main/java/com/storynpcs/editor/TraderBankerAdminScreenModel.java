package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TraderRole;

import java.util.List;

/**
 * Pure client-side state for the admin trader/banker configuration editor (issue #44).
 * Mirrors {@link NpcRulesScreenModel}'s shape: it mutates a detached {@link NpcDefinition}
 * in place and saves through the existing whole-definition
 * ServerboundNpcSavePayload -&gt; saveNpc path, so server-side validation is identical
 * to every other admin editor and no new network payload is required.
 *
 * <p>Trader and banker are edited on this NPC's existing {@link TraderRole}/{@link BankerRole};
 * if the NPC has neither role configured yet, opening the corresponding tab creates one
 * in memory (not saved until the admin explicitly saves).
 */
public final class TraderBankerAdminScreenModel {

    /** No pre-existing "supported tab boundary" constant exists in this codebase (searched
     * BankerRole, BankVault, NpcBankScreen); this is a newly authored, explicit UI/data bound. */
    public static final int MIN_TABS = 1;
    public static final int MAX_TABS = 20;

    public enum Tab { TRADER, BANKER }

    private final NpcDefinition npc;
    private Tab tab = Tab.TRADER;
    private boolean editingListing;
    private int editingIndex = -1; // -1 while adding a new listing

    // Trader listing edit-form fields (free text, validated on commit)
    private String listingId = "";
    private String offerItemId = "";
    private String offerCount = "1";
    private String priceItemId = "";
    private String priceCount = "1";
    private String maxUses = "0";
    private String requiredFaction = "";
    private String requiredFactionPoints = "0";

    // Banker fields (free text, validated on commit)
    private String bankName;
    private String maxTabs;
    private String tabUpgradeCost;

    private String statusMessage = "";
    private boolean statusError;

    public TraderBankerAdminScreenModel(NpcDefinition npc) {
        this.npc = npc;
        BankerRole banker = npc.getBanker();
        if (banker == null) {
            bankName = "Standard Vault";
            maxTabs = "4";
            tabUpgradeCost = "1000";
        } else {
            bankName = banker.getBankName();
            maxTabs = Integer.toString(banker.getMaxTabs());
            tabUpgradeCost = Integer.toString(banker.getTabUpgradeCost());
        }
    }

    public NpcDefinition getNpc() { return npc; }
    public Tab getTab() { return tab; }
    public void setTab(Tab tab) {
        this.tab = tab;
        editingListing = false;
        editingIndex = -1;
        setStatus("", false);
    }

    public boolean isEditingListing() { return editingListing; }
    public boolean isAddingListing() { return editingListing && editingIndex < 0; }

    public String getStatusMessage() { return statusMessage; }
    public boolean isStatusError() { return statusError; }
    public void setStatus(String message, boolean isError) {
        statusMessage = message != null ? message : "";
        statusError = isError;
    }

    // ── Trader listings ─────────────────────────────────────────────────────

    /** Read-only snapshot of the current trader listings, or an empty list if the NPC has no trader role. */
    public List<TradeListing> getListings() {
        return npc.getTrader() == null ? List.of() : npc.getTrader().getListings();
    }

    public void beginAddListing() {
        editingListing = true;
        editingIndex = -1;
        listingId = "";
        offerItemId = "";
        offerCount = "1";
        priceItemId = "";
        priceCount = "1";
        maxUses = "0";
        requiredFaction = "";
        requiredFactionPoints = "0";
        setStatus("", false);
    }

    /** Loads an existing listing's fields into the edit form. Returns false if the index is out of range. */
    public boolean beginEditListing(int index) {
        List<TradeListing> listings = getListings();
        if (index < 0 || index >= listings.size()) return false;
        TradeListing listing = listings.get(index);
        editingListing = true;
        editingIndex = index;
        listingId = listing.getListingId() == null ? "" : listing.getListingId();
        offerItemId = listing.getOfferItemId() == null ? "" : listing.getOfferItemId();
        offerCount = Integer.toString(listing.getOfferCount());
        priceItemId = listing.getPriceItemId() == null ? "" : listing.getPriceItemId();
        priceCount = Integer.toString(listing.getPriceCount());
        maxUses = Integer.toString(listing.getMaxUses());
        requiredFaction = listing.getRequiredFaction() == null ? "" : listing.getRequiredFaction().toString();
        requiredFactionPoints = Integer.toString(listing.getRequiredFactionPoints());
        setStatus("", false);
        return true;
    }

    public void cancelEditListing() {
        editingListing = false;
        editingIndex = -1;
        setStatus("", false);
    }

    public String getListingIdField() { return listingId; }
    public void setListingIdField(String v) { listingId = v; }
    public String getOfferItemIdField() { return offerItemId; }
    public void setOfferItemIdField(String v) { offerItemId = v; }
    public String getOfferCountField() { return offerCount; }
    public void setOfferCountField(String v) { offerCount = v; }
    public String getPriceItemIdField() { return priceItemId; }
    public void setPriceItemIdField(String v) { priceItemId = v; }
    public String getPriceCountField() { return priceCount; }
    public void setPriceCountField(String v) { priceCount = v; }
    public String getMaxUsesField() { return maxUses; }
    public void setMaxUsesField(String v) { maxUses = v; }
    public String getRequiredFactionField() { return requiredFaction; }
    public void setRequiredFactionField(String v) { requiredFaction = v; }
    public String getRequiredFactionPointsField() { return requiredFactionPoints; }
    public void setRequiredFactionPointsField(String v) { requiredFactionPoints = v; }

    /** "uses" is deliberately not editable here — it is read-only runtime state (issue #44 acceptance). */
    public int currentUsesForEditingListing() {
        if (editingIndex < 0) return 0;
        List<TradeListing> listings = getListings();
        return editingIndex < listings.size() ? listings.get(editingIndex).getUses() : 0;
    }

    /**
     * Validates the edit form and commits it as a new listing or an in-place edit of
     * {@code editingIndex}. Returns null on success or an error string to show inline;
     * on error, no mutation is made.
     */
    public String commitListing() {
        TradeListing built;
        try {
            built = buildListing();
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), true);
            return e.getMessage();
        }

        TraderRole trader = npc.getTrader();
        if (trader == null) {
            trader = new TraderRole();
            npc.setTrader(trader);
        }
        if (editingIndex < 0) {
            trader.addListing(built);
        } else {
            // Preserve the listing's runtime "uses" counter across an in-place edit —
            // the admin never edits it directly, so it must not reset to zero.
            built.setUses(currentUsesForEditingListing());
            trader.removeListing(editingIndex);
            trader.addListingAt(editingIndex, built);
        }
        editingListing = false;
        editingIndex = -1;
        return null;
    }

    /** Removes the 0-based listing index. Returns an error string or null. */
    public String removeListing(int index) {
        TraderRole trader = npc.getTrader();
        List<TradeListing> listings = getListings();
        if (trader == null || index < 0 || index >= listings.size()) {
            String err = "Listing index " + (index + 1) + " out of range.";
            setStatus(err, true);
            return err;
        }
        trader.removeListing(index);
        return null;
    }

    public String describeListing(TradeListing listing) {
        StringBuilder sb = new StringBuilder();
        sb.append(listing.getOfferCount()).append("x ").append(listing.getOfferItemId())
                .append(" -> ").append(listing.getPriceCount()).append("x ").append(listing.getPriceItemId());
        if (listing.getMaxUses() > 0) {
            sb.append(" (").append(listing.getUses()).append('/').append(listing.getMaxUses()).append(" uses)");
        }
        if (listing.getRequiredFaction() != null) {
            sb.append(" [req ").append(listing.getRequiredFaction())
                    .append(" >= ").append(listing.getRequiredFactionPoints()).append(']');
        }
        return sb.toString();
    }

    private TradeListing buildListing() {
        if (offerItemId.isBlank()) throw new IllegalArgumentException("Offer item ID is required.");
        if (priceItemId.isBlank()) throw new IllegalArgumentException("Price item ID is required.");
        int offerCountValue = parseInt(offerCount, "offer count", 1, 64);
        int priceCountValue = parseInt(priceCount, "price count", 1, 64);
        int maxUsesValue = parseInt(maxUses, "max uses", 0, 1_000_000);
        int requiredPointsValue = parseInt(requiredFactionPoints, "required faction points", -100_000, 100_000);

        TradeListing listing = new TradeListing(offerItemId.trim(), offerCountValue, priceItemId.trim(), priceCountValue);
        listing.setMaxUses(maxUsesValue);
        listing.setRequiredFactionPoints(requiredPointsValue);
        if (!requiredFaction.isBlank()) {
            listing.setRequiredFaction(parseId(requiredFaction, "required faction"));
        }
        if (!listingId.isBlank()) {
            listing.setListingId(listingId.trim());
        } else {
            listing.ensureStableId();
        }
        return listing;
    }

    // ── Banker config ───────────────────────────────────────────────────────

    public String getBankNameField() { return bankName; }
    public void setBankNameField(String v) { bankName = v; }
    public String getMaxTabsField() { return maxTabs; }
    public void setMaxTabsField(String v) { maxTabs = v; }
    public String getTabUpgradeCostField() { return tabUpgradeCost; }
    public void setTabUpgradeCostField(String v) { tabUpgradeCost = v; }

    /**
     * Validates and applies the banker config fields onto this NPC's banker role
     * (creating one if absent). Returns null on success or an error string; on
     * error, no mutation is made.
     */
    public String commitBankerConfig() {
        if (bankName.isBlank()) {
            String err = "Bank name cannot be blank.";
            setStatus(err, true);
            return err;
        }
        int maxTabsValue;
        int costValue;
        try {
            maxTabsValue = parseInt(maxTabs, "max tabs", MIN_TABS, MAX_TABS);
            costValue = parseInt(tabUpgradeCost, "tab upgrade cost", 0, 1_000_000);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), true);
            return e.getMessage();
        }

        BankerRole banker = npc.getBanker();
        if (banker == null) {
            banker = new BankerRole();
            npc.setBanker(banker);
        }
        banker.setBankName(bankName.trim());
        banker.setMaxTabs(maxTabsValue);
        banker.setTabUpgradeCost(costValue);
        return null;
    }

    private static NamespacedId parseId(String raw, String label) {
        try {
            return NamespacedId.of(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Bad " + label + " id '" + raw + "' — expected namespace:path (e.g. storynpcs:townsfolk).");
        }
    }

    private static int parseInt(String raw, String label, int min, int max) {
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid whole number for " + label + ".");
        }
    }
}
