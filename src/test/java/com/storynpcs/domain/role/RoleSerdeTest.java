package com.storynpcs.domain.role;

import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TradeSummaries;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.persistence.BankOperationIntent;
import com.storynpcs.persistence.TradeOperationIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Wire-format serde for the trader/banker screen payloads plus listing summary text. */
class RoleSerdeTest {

    @Test
    @DisplayName("TraderRole, BankerRole, BankVault and score map survive JSON round-trip")
    void testRoleRoundTrips() {
        var trader = new TraderRole("Riverside Market");
        trader.setRestockIntervalTicks(12000);
        var listing = new TradeListing("minecraft:bread", 3, "minecraft:emerald", 1);
        listing.setMaxUses(10);
        listing.setUses(4);
        trader.addListing(listing);

        var restoredTrader = RoleSerde.traderFromJson(RoleSerde.toJson(trader));
        assertTrue(restoredTrader.isPresent());
        assertEquals("Riverside Market", restoredTrader.get().getMarketName());
        assertEquals(12000, restoredTrader.get().getRestockIntervalTicks());
        assertEquals(1, restoredTrader.get().getListings().size());
        assertEquals(4, restoredTrader.get().getListings().get(0).getUses());

        var banker = new BankerRole("Iron Vault");
        banker.setMaxTabs(3);
        banker.setTabUpgradeCost(64);
        var restoredBanker = RoleSerde.bankerFromJson(RoleSerde.toJson(banker));
        assertTrue(restoredBanker.isPresent());
        assertEquals("Iron Vault", restoredBanker.get().getBankName());
        assertEquals(3, restoredBanker.get().getMaxTabs());
        assertEquals(64, restoredBanker.get().getTabUpgradeCost());

        var vault = new BankVault(UUID.randomUUID());
        vault.setUnlockedTabs(2);
        vault.deposit(0, 0, "minecraft:diamond", 10, "tagA");
        vault.deposit(1, 3, "minecraft:bread", 5, null);
        var restoredVault = RoleSerde.vaultFromJson(RoleSerde.toJson(vault));
        assertTrue(restoredVault.isPresent());
        assertEquals(2, restoredVault.get().getUnlockedTabs());
        assertEquals(10, restoredVault.get().getTabItems(0).get(0).getCount());
        assertEquals("tagA", restoredVault.get().getTabItems(0).get(0).getTag());
        assertEquals(5, restoredVault.get().getTabItems(1).get(0).getCount());

        var scores = RoleSerde.scoresFromJson(RoleSerde.toJson(Map.of("storynpcs:pirates", 250)));
        assertEquals(250, scores.get("storynpcs:pirates"));
        assertTrue(RoleSerde.scoresFromJson("").isEmpty());
        assertTrue(RoleSerde.scoresFromJson("not json").isEmpty());
        assertTrue(RoleSerde.traderFromJson("{").isEmpty());
    }

    @Test
    void runtimeTradeViewChangesCannotMutateRegistryOwnedRoleOrAssignIdsToIt() {
        TraderRole definition = new TraderRole("Projection Test");
        TradeListing legacyListing = new TradeListing("minecraft:bread", 1, "minecraft:emerald", 1);
        legacyListing.setUses(2);
        definition.addListingAt(0, legacyListing);
        assertTrue(legacyListing.getListingId().isBlank());

        TraderRole view = RoleSerde.copyTrader(definition);
        TradeListing viewListing = view.getListings().get(0);
        viewListing.setUses(7);

        assertEquals(7, viewListing.getUses());
        assertEquals(2, legacyListing.getUses());
        assertTrue(legacyListing.getListingId().isBlank());
    }

    @Test
    @DisplayName("scoresFromJson drops non-integer values and rejects non-object roots")
    void testScoresFromJsonHardening() {
        // Strings, floats, nulls and out-of-int-range values are filtered, not coerced
        var scores = RoleSerde.scoresFromJson(
                "{\"a\":\"x\",\"b\":5,\"c\":null,\"d\":1.5,\"e\":99999999999}");
        assertEquals(Map.of("b", 5), scores);

        // Non-object roots yield an empty map rather than a raw-type blob
        assertTrue(RoleSerde.scoresFromJson("[1,2,3]").isEmpty());
        assertTrue(RoleSerde.scoresFromJson("\"hello\"").isEmpty());
        assertTrue(RoleSerde.scoresFromJson("5").isEmpty());
        assertTrue(RoleSerde.scoresFromJson("null").isEmpty());
        assertTrue(RoleSerde.scoresFromJson(null).isEmpty());
        assertTrue(RoleSerde.scoresFromJson("   ").isEmpty());

        // Result is a defensive immutable copy
        var parsed = RoleSerde.scoresFromJson("{\"a\":1}");
        assertThrows(UnsupportedOperationException.class, () -> parsed.put("b", 2));
    }

    @Test
    @DisplayName("TradeSummaries describes listings and reports unavailability reasons")
    void testTradeSummaries() {
        var listing = new TradeListing("minecraft:bread", 3, "minecraft:emerald", 1);
        assertEquals("3x minecraft:bread <- 1x minecraft:emerald", TradeSummaries.describe(listing));
        assertNull(TradeSummaries.unavailableReason(listing, 0));

        listing.setMaxUses(2);
        listing.setUses(2);
        assertTrue(TradeSummaries.describe(listing).contains("(uses: 2/2)"));
        assertEquals("Sold out", TradeSummaries.unavailableReason(listing, 0));

        var gated = new TradeListing("minecraft:apple", 1, "minecraft:emerald", 5);
        gated.setRequiredFaction(com.storynpcs.domain.common.NamespacedId.of("storynpcs:pirates"));
        gated.setRequiredFactionPoints(100);
        String reason = TradeSummaries.unavailableReason(gated, 50);
        assertNotNull(reason);
        assertTrue(reason.contains("storynpcs:pirates"));
        assertNull(TradeSummaries.unavailableReason(gated, 100));
    }

    @Test
    @DisplayName("Bank operation intent validates and round-trips its recovery fields")
    void testBankOperationIntentRoundTrip() {
        BankOperationIntent intent = new BankOperationIntent(
                UUID.randomUUID(), "bank.deposit_held", 0, 3,
                "minecraft:diamond", "{\"components\":{}}", 4, 8, 12);

        var restored = RoleSerde.bankOperationIntentFromJson(RoleSerde.toJson(intent));
        assertTrue(restored.isPresent());
        assertEquals(intent, restored.get());
        assertTrue(intent.matches(new BankVault.VaultItem(3, "minecraft:diamond", 12,
                "{\"components\":{}}")));
        assertFalse(intent.matches(new BankVault.VaultItem(3, "minecraft:diamond", 11,
                "{\"components\":{}}")));
        assertThrows(IllegalArgumentException.class, () -> new BankOperationIntent(
                UUID.randomUUID(), "bank.deposit_held", 0, 54,
                "minecraft:diamond", null, 1, 0, 1));
    }

    @Test
    @DisplayName("Trade operation intent validates and round-trips its replay fields")
    void testTradeOperationIntentRoundTrip() {
        TradeOperationIntent intent = new TradeOperationIntent(
                UUID.randomUUID(), "storynpcs:merchant", 2,
                "minecraft:bread", 4, "minecraft:wheat", 12, 5, 1);

        var restored = RoleSerde.tradeOperationIntentFromJson(RoleSerde.toJson(intent));
        assertTrue(restored.isPresent());
        assertEquals(intent, restored.get());
        assertThrows(IllegalArgumentException.class, () -> new TradeOperationIntent(
                UUID.randomUUID(), "bad", -2, "minecraft:bread", 1,
                "minecraft:wheat", 1, 0, 0));
    }
}
