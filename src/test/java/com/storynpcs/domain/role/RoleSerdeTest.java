package com.storynpcs.domain.role;

import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TradeSummaries;
import com.storynpcs.domain.role.trader.TraderRole;
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
}
