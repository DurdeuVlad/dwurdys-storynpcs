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

    @JsonProperty
    private int maxUses = 0; // 0 = unlimited

    @JsonProperty
    private int uses = 0;

    @JsonProperty
    private NamespacedId requiredFaction;

    @JsonProperty
    private int requiredFactionPoints = 0;

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
