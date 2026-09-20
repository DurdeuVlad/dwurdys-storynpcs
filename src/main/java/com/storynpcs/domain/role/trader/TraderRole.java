package com.storynpcs.domain.role.trader;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TraderRole {

    @JsonProperty
    private String marketName = "Trader";

    @JsonProperty
    private List<TradeListing> listings = new ArrayList<>();

    @JsonProperty
    private int restockIntervalTicks = 24000; // 1 in-game day default

    public TraderRole() {}

    public TraderRole(String marketName) {
        this.marketName = marketName != null ? marketName : "Trader";
    }

    public String getMarketName() { return marketName; }
    public void setMarketName(String marketName) { this.marketName = marketName; }

    public List<TradeListing> getListings() { return Collections.unmodifiableList(listings); }
    public void setListings(List<TradeListing> listings) {
        this.listings = listings != null ? new ArrayList<>(listings) : new ArrayList<>();
    }

    public void addListing(TradeListing listing) {
        if (listing != null) {
            this.listings.add(listing);
        }
    }

    /** Removes and returns the listing at the given index, or null when out of range. */
    public TradeListing removeListing(int index) {
        if (index < 0 || index >= listings.size()) {
            return null;
        }
        return listings.remove(index);
    }

    /** Re-inserts a previously removed listing at the given index (rollback helper). */
    public void addListingAt(int index, TradeListing listing) {
        if (listing != null && index >= 0 && index <= listings.size()) {
            this.listings.add(index, listing);
        }
    }

    public int getRestockIntervalTicks() { return restockIntervalTicks; }
    public void setRestockIntervalTicks(int restockIntervalTicks) { this.restockIntervalTicks = restockIntervalTicks; }

    public void restockAll() {
        for (TradeListing listing : listings) {
            listing.restock();
        }
    }
}