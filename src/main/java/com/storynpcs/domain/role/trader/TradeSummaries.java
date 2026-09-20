package com.storynpcs.domain.role.trader;

/**
 * Shared single-line descriptions for trade listings, used by the
 * {@code /storynpcs npc trade list} command output and the client trade screen
 * so both surfaces describe a listing identically.
 */
public final class TradeSummaries {

    private TradeSummaries() {}

    /** e.g. "3x minecraft:bread <- 1x minecraft:emerald (uses: 2/5)" */
    public static String describe(TradeListing listing) {
        StringBuilder sb = new StringBuilder();
        sb.append(Math.max(1, listing.getOfferCount())).append('x').append(' ')
                .append(listing.getOfferItemId())
                .append(" <- ")
                .append(Math.max(1, listing.getPriceCount())).append('x').append(' ')
                .append(listing.getPriceItemId());
        if (listing.getMaxUses() > 0) {
            sb.append(" (uses: ").append(listing.getUses()).append('/').append(listing.getMaxUses()).append(')');
        }
        if (listing.getRequiredFaction() != null) {
            sb.append(" [faction: ").append(listing.getRequiredFaction())
                    .append(" >= ").append(listing.getRequiredFactionPoints()).append(']');
        }
        return sb.toString();
    }

    /** Plain-language reason a listing is unavailable, or null when purchasable. */
    public static String unavailableReason(TradeListing listing, int playerFactionScore) {
        if (listing.getMaxUses() > 0 && listing.getUses() >= listing.getMaxUses()) {
            return "Sold out";
        }
        if (listing.getRequiredFaction() != null && playerFactionScore < listing.getRequiredFactionPoints()) {
            return "Requires " + listing.getRequiredFaction() + " >= " + listing.getRequiredFactionPoints();
        }
        return null;
    }
}
