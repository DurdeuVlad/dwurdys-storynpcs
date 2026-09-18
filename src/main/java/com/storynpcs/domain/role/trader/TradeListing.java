package com.storynpcs.domain.role.trader;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

public class TradeListing {

    @JsonProperty(required = true)
    private String offerItemId;

    @JsonProperty
    private int offerCount = 1;

    @JsonProperty(required = true)
    private String priceItemId;

    @JsonProperty
    private int priceCount = 1;

    @JsonProperty
    private int maxUses = 0; // 0 = unlimited

    @JsonProperty
    private int uses = 0;

    @JsonProperty
    private NamespacedId requiredFaction;

    @JsonProperty
    private int requiredFactionPoints = 0;

    public TradeListing() {}

    public TradeListing(String offerItemId, int offerCount, String priceItemId, int priceCount) {
        this.offerItemId = offerItemId;
        this.offerCount = offerCount;
        this.priceItemId = priceItemId;
        this.priceCount = priceCount;
    }

    public String getOfferItemId() { return offerItemId; }
    public void setOfferItemId(String offerItemId) { this.offerItemId = offerItemId; }

    public int getOfferCount() { return offerCount; }
    public void setOfferCount(int offerCount) { this.offerCount = offerCount; }

    public String getPriceItemId() { return priceItemId; }
    public void setPriceItemId(String priceItemId) { this.priceItemId = priceItemId; }

    public int getPriceCount() { return priceCount; }
    public void setPriceCount(int priceCount) { this.priceCount = priceCount; }

    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }

    public int getUses() { return uses; }
    public void setUses(int uses) { this.uses = uses; }

    public NamespacedId getRequiredFaction() { return requiredFaction; }
    public void setRequiredFaction(NamespacedId requiredFaction) { this.requiredFaction = requiredFaction; }

    public int getRequiredFactionPoints() { return requiredFactionPoints; }
    public void setRequiredFactionPoints(int requiredFactionPoints) { this.requiredFactionPoints = requiredFactionPoints; }

    public boolean isAvailable(int playerFactionScore) {
        if (maxUses > 0 && uses >= maxUses) {
            return false;
        }
        if (requiredFaction != null && playerFactionScore < requiredFactionPoints) {
            return false;
        }
        return true;
    }

    public synchronized boolean recordTrade() {
        if (maxUses > 0 && uses >= maxUses) {
            return false;
        }
        // VULN-48: clamp to avoid integer overflow on unlimited trades
        if (uses < Integer.MAX_VALUE) {
            uses++;
        }
        return true;
    }

    public void restock() {
        this.uses = 0;
    }
}