package com.storynpcs.domain.role.trader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeListingTest {

    @Test
    void singleInputListingHasNoSecondInput() {
        TradeListing listing = new TradeListing("minecraft:bread", 3, "minecraft:emerald", 1);
        assertThat(listing.hasSecondInput()).isFalse();
        assertThat(listing.getSecondPriceItemId()).isNull();
    }

    @Test
    void twoInputConstructorSetsTheSecondInput() {
        TradeListing listing = new TradeListing(
                "minecraft:golden_apple", 1, "minecraft:emerald", 5, "minecraft:gold_ingot", 3);
        assertThat(listing.hasSecondInput()).isTrue();
        assertThat(listing.getSecondPriceItemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(listing.getSecondPriceCount()).isEqualTo(3);
    }

    @Test
    void settingSecondPriceItemIdToBlankOrNullClearsIt() {
        TradeListing listing = new TradeListing(
                "minecraft:golden_apple", 1, "minecraft:emerald", 5, "minecraft:gold_ingot", 3);
        listing.setSecondPriceItemId("");
        assertThat(listing.hasSecondInput()).isFalse();

        listing.setSecondPriceItemId("minecraft:iron_ingot");
        assertThat(listing.hasSecondInput()).isTrue();
        listing.setSecondPriceItemId(null);
        assertThat(listing.hasSecondInput()).isFalse();
    }

    @Test
    void singleInputContractIdentityIsUnchangedFromBeforeTheSecondInputSlotExisted() {
        // Regression guard: this exact string format must never change for a
        // single-input listing, since it is hashed into already-persisted
        // "legacy-<hash>" listing IDs in shipped worlds.
        TradeListing listing = new TradeListing("minecraft:bread", 3, "minecraft:emerald", 1);
        String expected = "minecraft:bread|3|minecraft:emerald|1|0|null|0";
        assertThat(listing.contractIdentity()).isEqualTo(expected);
    }

    @Test
    void twoInputContractIdentityAppendsTheSecondInputFields() {
        TradeListing listing = new TradeListing(
                "minecraft:golden_apple", 1, "minecraft:emerald", 5, "minecraft:gold_ingot", 3);
        assertThat(listing.contractIdentity())
                .isEqualTo("minecraft:golden_apple|1|minecraft:emerald|5|0|null|0|minecraft:gold_ingot|3");
    }

    @Test
    void ensureStableIdIsDeterministicAndDiffersBetweenSingleAndTwoInputVariants() {
        TradeListing singleInput = new TradeListing("minecraft:bread", 3, "minecraft:emerald", 1);
        TradeListing twoInput = new TradeListing(
                "minecraft:bread", 3, "minecraft:emerald", 1, "minecraft:gold_ingot", 2);

        String singleId = singleInput.ensureStableId();
        String twoInputId = twoInput.ensureStableId();

        assertThat(singleId).isNotBlank().isNotEqualTo(twoInputId);
        // Calling again must not change an already-assigned ID.
        assertThat(singleInput.ensureStableId()).isEqualTo(singleId);
    }

    @Test
    void reservationLifecycleCommitConsumesOneUsePermanently() {
        TradeListing listing = new TradeListing("minecraft:bread", 1, "minecraft:emerald", 1);
        listing.setMaxUses(1);

        TradeListing.TradeReservation reservation = listing.reserveTrade();
        assertThat(reservation).isNotNull();
        assertThat(listing.reserveTrade()).isNull(); // sold out while reserved

        reservation.commit();
        assertThat(listing.getUses()).isEqualTo(1);
        assertThat(listing.reserveTrade()).isNull(); // still sold out after commit
    }

    @Test
    void reservationRollbackFreesTheUseForAnotherBuyer() {
        TradeListing listing = new TradeListing("minecraft:bread", 1, "minecraft:emerald", 1);
        listing.setMaxUses(1);

        TradeListing.TradeReservation reservation = listing.reserveTrade();
        reservation.rollback();

        assertThat(listing.getUses()).isZero();
        assertThat(listing.reserveTrade()).isNotNull();
    }
}
