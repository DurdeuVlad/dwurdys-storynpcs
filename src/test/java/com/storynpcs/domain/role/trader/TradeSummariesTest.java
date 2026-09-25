package com.storynpcs.domain.role.trader;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeSummariesTest {

    @Test
    void describesASingleInputListing() {
        TradeListing listing = new TradeListing("minecraft:bread", 3, "minecraft:emerald", 1);
        assertThat(TradeSummaries.describe(listing)).isEqualTo("3x minecraft:bread <- 1x minecraft:emerald");
    }

    @Test
    void describesATwoInputListingWithBothInputs() {
        TradeListing listing = new TradeListing(
                "minecraft:golden_apple", 1, "minecraft:emerald", 5, "minecraft:gold_ingot", 3);
        assertThat(TradeSummaries.describe(listing))
                .isEqualTo("1x minecraft:golden_apple <- 5x minecraft:emerald + 3x minecraft:gold_ingot");
    }

    @Test
    void describeIncludesUsesAndFactionRequirementAfterTheSecondInput() {
        TradeListing listing = new TradeListing(
                "minecraft:golden_apple", 1, "minecraft:emerald", 5, "minecraft:gold_ingot", 3);
        listing.setMaxUses(5);
        listing.setRequiredFaction(NamespacedId.of("storynpcs:merchants"));
        listing.setRequiredFactionPoints(100);

        assertThat(TradeSummaries.describe(listing)).isEqualTo(
                "1x minecraft:golden_apple <- 5x minecraft:emerald + 3x minecraft:gold_ingot (uses: 0/5) [faction: storynpcs:merchants >= 100]");
    }

    @Test
    void unavailableReasonIsUnaffectedBySecondInput() {
        TradeListing listing = new TradeListing(
                "minecraft:golden_apple", 1, "minecraft:emerald", 5, "minecraft:gold_ingot", 3);
        listing.setMaxUses(1);
        listing.recordTrade();

        assertThat(TradeSummaries.unavailableReason(listing, 0)).isEqualTo("Sold out");
    }
}
