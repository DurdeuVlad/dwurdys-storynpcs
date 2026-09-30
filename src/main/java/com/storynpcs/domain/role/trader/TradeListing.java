package com.storynpcs.domain.role.trader;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.storynpcs.domain.common.NamespacedId;

import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class TradeListing {

    @JsonProperty
    private String listingId = "";

    @JsonProperty(required = true)
    private String offerItemId;

    @JsonProperty
    private int offerCount = 1;

    @JsonProperty(required = true)
    private String priceItemId;

    @JsonProperty
    private int priceCount = 1;

    /** Optional second input slot — up to two inputs/one output (P7-1). */
    @JsonProperty
    private String secondaryPriceItemId;

    @JsonProperty
    private int secondaryPriceCount = 0;

    @JsonProperty
    private int maxUses = 0; // 0 = unlimited

    @JsonProperty
    private int uses = 0;

    @JsonProperty
    private NamespacedId requiredFaction;

    @JsonProperty
    private int requiredFactionPoints = 0;

    /** Display page index — listings are grouped into pages of fixed size. */
    @JsonProperty
    private int page = 0;

    /** Restock interval in ticks; 0 = never restocks (uses are permanent). */
    @JsonProperty
    private long restockIntervalTicks = 0;

    /** Last restock tick — durable so restock survives restarts. */
    @JsonProperty
    private long lastRestockTick = 0;

    @JsonIgnore
    private transient long nextReservationId;

    @JsonIgnore
    private transient Set<Long> activeReservations = new HashSet<>();

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

    public String getSecondaryPriceItemId() { return secondaryPriceItemId; }
    public void setSecondaryPriceItemId(String secondaryPriceItemId) { this.secondaryPriceItemId = secondaryPriceItemId; }

    public int getSecondaryPriceCount() { return secondaryPriceCount; }
    public void setSecondaryPriceCount(int secondaryPriceCount) {
        if (secondaryPriceCount < 0 || secondaryPriceCount > 64) {
            throw new IllegalArgumentException("secondaryPriceCount must be in [0,64]");
        }
        this.secondaryPriceCount = secondaryPriceCount;
    }

    public boolean hasTwoInputs() {
        return secondaryPriceItemId != null && !secondaryPriceItemId.isBlank() && secondaryPriceCount > 0;
    }

    public int getPage() { return page; }
    public void setPage(int page) {
        if (page < 0 || page > 99) {
            throw new IllegalArgumentException("page must be in [0,99]");
        }
        this.page = page;
    }

    public long getRestockIntervalTicks() { return restockIntervalTicks; }
    public void setRestockIntervalTicks(long restockIntervalTicks) {
        if (restockIntervalTicks < 0) {
            throw new IllegalArgumentException("restockIntervalTicks must be >= 0");
        }
        this.restockIntervalTicks = restockIntervalTicks;
    }

    public long getLastRestockTick() { return lastRestockTick; }
    public void setLastRestockTick(long lastRestockTick) { this.lastRestockTick = lastRestockTick; }

    /** True when a restock boundary has passed; resets uses deterministically. */
    public synchronized boolean restock(long nowTick) {
        if (restockIntervalTicks <= 0 || nowTick - lastRestockTick < restockIntervalTicks) {
            return false;
        }
        lastRestockTick += restockIntervalTicks * ((nowTick - lastRestockTick) / restockIntervalTicks);
        uses = 0;
        return true;
    }

    /**
     * Pre-commit validation — both input slots and the output must be coherent
     * before a transaction may begin.
     */
    public void validate() {
        if (offerItemId == null || offerItemId.isBlank() || offerCount < 1 || offerCount > 64) {
            throw new IllegalStateException("output slot requires item id and count in [1,64]");
        }
        if (priceItemId == null || priceItemId.isBlank() || priceCount < 1 || priceCount > 64) {
            throw new IllegalStateException("primary input requires item id and count in [1,64]");
        }
        if (secondaryPriceItemId != null && !secondaryPriceItemId.isBlank()
                && (secondaryPriceCount < 1 || secondaryPriceCount > 64)) {
            throw new IllegalStateException("secondary input with an item id requires count in [1,64]");
        }
        if (secondaryPriceItemId == null || secondaryPriceItemId.isBlank()) {
            if (secondaryPriceCount != 0) {
                throw new IllegalStateException("secondary input count requires an item id");
            }
        }
        // Identical input slots are incoherent: the exchange would deduct the
        // same item type twice while the held-check only proves each amount
        // independently, silently under-charging the player. Fold the price
        // into a single larger primary count instead.
        if (secondaryPriceItemId != null && !secondaryPriceItemId.isBlank()
                && secondaryPriceItemId.trim().equals(priceItemId.trim())) {
            throw new IllegalStateException("primary and secondary inputs must be different items");
        }
    }

    /** Server-side purchase eligibility: uses remaining + faction + permission. */
    public synchronized boolean canPurchase(int playerFactionPoints, boolean hasPermission) {
        if (!hasPermission) {
            return false;
        }
        if (maxUses > 0 && uses >= maxUses) {
            return false;
        }
        return requiredFaction == null || playerFactionPoints >= requiredFactionPoints;
    }

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
        TradeReservation reservation = reserveTrade();
        if (reservation == null) return false;
        reservation.commit();
        return true;
    }

    /** Stable authored identity; runtime uses must never be keyed by list position. */
    public String getListingId() { return listingId; }
    public void setListingId(String listingId) {
        String normalized = listingId == null ? "" : listingId.trim();
        if (normalized.length() > 128 || (!normalized.isEmpty() && !normalized.matches("[A-Za-z0-9_.:-]+"))) {
            throw new IllegalArgumentException("listingId must contain only bounded identifier characters");
        }
        this.listingId = normalized;
    }

    /** Assigns a deterministic legacy identity from the authored trade contract. */
    public String ensureStableId() {
        if (listingId == null || listingId.isBlank()) {
            listingId = "legacy-" + digest(contractIdentity());
        }
        return listingId;
    }

    String contractIdentity() {
        return String.valueOf(offerItemId) + "|" + offerCount + "|"
                + String.valueOf(priceItemId) + "|" + priceCount + "|" + maxUses + "|"
                + String.valueOf(requiredFaction) + "|" + requiredFactionPoints;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format("%02x", bytes[i]));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Reserves one listing use for a multi-step exchange. The reservation owns
     * only its own use, so a failed exchange can roll back without decrementing
     * a concurrent successful trade.
     */
    public synchronized TradeReservation reserveTrade() {
        if (maxUses > 0 && uses >= maxUses) return null;
        if (uses >= Integer.MAX_VALUE) return null;
        uses++;
        long reservationId = ++nextReservationId;
        activeReservations().add(reservationId);
        return new TradeReservation(this, reservationId);
    }

    private Set<Long> activeReservations() {
        if (activeReservations == null) activeReservations = new HashSet<>();
        return activeReservations;
    }

    private synchronized void commitReservation(long reservationId) {
        activeReservations().remove(reservationId);
    }

    private synchronized void rollbackReservation(long reservationId) {
        if (activeReservations().remove(reservationId) && uses > 0) uses--;
    }

    public synchronized void restock() {
        // Reservations already in flight remain owned by their callers; the
        // next committed trade count is therefore the number still reserved.
        this.uses = activeReservations().size();
    }

    public static final class TradeReservation {
        private final TradeListing owner;
        private final long reservationId;
        private boolean closed;

        private TradeReservation(TradeListing owner, long reservationId) {
            this.owner = owner;
            this.reservationId = reservationId;
        }

        public synchronized void commit() {
            if (closed) return;
            owner.commitReservation(reservationId);
            closed = true;
        }

        public synchronized void rollback() {
            if (closed) return;
            owner.rollbackReservation(reservationId);
            closed = true;
        }
    }
}
