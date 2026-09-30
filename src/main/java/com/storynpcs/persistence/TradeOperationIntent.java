package com.storynpcs.persistence;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/** Bounded, non-secret intent retained while a trade spans inventory and listing state. */
public record TradeOperationIntent(
        UUID playerUuid,
        String npcId,
        int listingIndex,
        String listingId,
        String offerItemId,
        int offerCount,
        String priceItemId,
        int priceCount,
        String secondaryPriceItemId,
        int secondaryPriceCount,
        int maxUses,
        int usesBefore,
        String requiredFactionId,
        int requiredFactionPoints
) {
    public TradeOperationIntent(UUID playerUuid, String npcId, int listingIndex, String listingId,
                                String offerItemId, int offerCount, String priceItemId, int priceCount,
                                int maxUses, int usesBefore, String requiredFactionId,
                                int requiredFactionPoints) {
        this(playerUuid, npcId, listingIndex, listingId, offerItemId, offerCount, priceItemId,
                priceCount, "", 0, maxUses, usesBefore, requiredFactionId, requiredFactionPoints);
    }

    public TradeOperationIntent(UUID playerUuid, String npcId, int listingIndex,
                                String offerItemId, int offerCount, String priceItemId,
                                int priceCount, int maxUses, int usesBefore) {
        this(playerUuid, npcId, listingIndex, "", offerItemId, offerCount, priceItemId,
                priceCount, "", 0, maxUses, usesBefore, "", 0);
    }

    public TradeOperationIntent {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid cannot be null");
        if (npcId == null || npcId.isBlank()) throw new IllegalArgumentException("npcId cannot be blank");
        if (listingIndex < -1) throw new IllegalArgumentException("listingIndex cannot be less than -1");
        if (listingId == null || listingId.length() > 128
                || (!listingId.isEmpty() && !listingId.matches("[A-Za-z0-9_.:-]+"))) {
            throw new IllegalArgumentException("listingId is malformed");
        }
        if (offerItemId == null || offerItemId.isBlank() || offerCount <= 0) {
            throw new IllegalArgumentException("invalid offer");
        }
        if (priceItemId == null || priceItemId.isBlank() || priceCount <= 0) {
            throw new IllegalArgumentException("invalid price");
        }
        secondaryPriceItemId = secondaryPriceItemId == null ? "" : secondaryPriceItemId.trim();
        if (secondaryPriceItemId.isEmpty()) {
            if (secondaryPriceCount != 0) {
                throw new IllegalArgumentException("secondary price count requires an item id");
            }
        } else {
            if (secondaryPriceItemId.length() > 256 || secondaryPriceCount < 1 || secondaryPriceCount > 64) {
                throw new IllegalArgumentException("invalid secondary price");
            }
            NamespacedId.of(secondaryPriceItemId);
        }
        if (maxUses < 0 || usesBefore < 0) throw new IllegalArgumentException("invalid use bounds");
        if (requiredFactionId == null || requiredFactionId.length() > 256) {
            throw new IllegalArgumentException("requiredFactionId is malformed");
        }
        if (!requiredFactionId.isBlank()) NamespacedId.of(requiredFactionId);
        if (requiredFactionPoints < 0) throw new IllegalArgumentException("requiredFactionPoints cannot be negative");
        NamespacedId.of(npcId);
    }
}
