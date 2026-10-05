package com.storynpcs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.persistence.TradeStateRepository;

class P7DomainTest {

    // --- P7-1 trader ---------------------------------------------------------

    @Test
    void twoInputsAndOutputValidateBeforeCommit() {
        TradeListing listing = new TradeListing("minecraft:emerald", 2, "minecraft:diamond", 1);
        listing.validate(); // single input still valid

        listing.setSecondaryPriceItemId("minecraft:gold_ingot");
        listing.setSecondaryPriceCount(3);
        assertThat(listing.hasTwoInputs()).isTrue();
        listing.validate();

        // Output invalid → throws.
        TradeListing badOut = new TradeListing("", 0, "minecraft:diamond", 1);
        assertThatThrownBy(badOut::validate).isInstanceOf(IllegalStateException.class);
        // Primary input invalid → throws.
        TradeListing badIn = new TradeListing("minecraft:emerald", 1, "", 0);
        assertThatThrownBy(badIn::validate).isInstanceOf(IllegalStateException.class);
        // Secondary id without count → throws; count without id → throws.
        TradeListing noCount = new TradeListing("minecraft:emerald", 1, "minecraft:diamond", 1);
        noCount.setSecondaryPriceItemId("minecraft:gold_ingot");
        assertThatThrownBy(noCount::validate).isInstanceOf(IllegalStateException.class);
        TradeListing noId = new TradeListing("minecraft:emerald", 1, "minecraft:diamond", 1);
        noId.setSecondaryPriceCount(2);
        assertThatThrownBy(noId::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listing.setSecondaryPriceCount(65))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restockResetsUsesDeterministically(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        // Restock cadence is durable state: the marker lives in
        // TradeStateRepository beside uses, so boundaries survive restart and
        // the reset+advance lands in one atomic write.
        var repo = new TradeStateRepository(tempDir.resolve("trade_restock"));
        String npc = "storynpcs:shop", lid = "storynpcs:deal";
        repo.reserveUse(npc, lid, 0, 2);
        repo.reserveUse(npc, lid, 1, 2);
        assertThat(repo.getUses(npc, lid)).isEqualTo(2);

        assertThat(repo.restockIfDue(npc, lid, 100, 50)).isFalse();   // before boundary
        assertThat(repo.restockIfDue(npc, lid, 100, 150)).isTrue();   // boundary → reset
        assertThat(repo.getUses(npc, lid)).isEqualTo(0);
        assertThat(repo.restockIfDue(npc, lid, 100, 150)).isFalse();  // no double restock
        assertThat(repo.restockIfDue(npc, lid, 0, 1_000_000)).isFalse(); // 0 = never

        // Catch-up: several elapsed intervals land on the latest boundary.
        repo.reserveUse(npc, lid, 0, 2);
        assertThat(repo.restockIfDue(npc, lid, 100, 450)).isTrue();
        assertThat(repo.getUses(npc, lid)).isEqualTo(0);
        assertThat(repo.restockIfDue(npc, lid, 100, 449)).isFalse();

        // Restart: a fresh repository over the same files keeps the marker.
        var repo2 = new TradeStateRepository(tempDir.resolve("trade_restock"));
        assertThat(repo2.restockIfDue(npc, lid, 100, 460)).isFalse();
    }

    @Test
    void roleRestockIntervalIsTheListingFallback() {
        var role = new com.storynpcs.domain.role.trader.TraderRole();
        var listing = new TradeListing("minecraft:emerald", 1, "minecraft:diamond", 1);
        assertThat(role.effectiveRestockInterval(listing)).isEqualTo(24_000); // role default
        listing.setRestockIntervalTicks(500);
        assertThat(role.effectiveRestockInterval(listing)).isEqualTo(500);    // listing wins
        role.setRestockIntervalTicks(0);
        var unset = new TradeListing("minecraft:emerald", 1, "minecraft:diamond", 1);
        assertThat(role.effectiveRestockInterval(unset)).isEqualTo(0);        // permanent
        assertThatThrownBy(() -> role.setRestockIntervalTicks(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purchaseEligibilityIsServerSideAndBounded() {
        TradeListing listing = new TradeListing("minecraft:emerald", 1, "minecraft:diamond", 1);
        listing.setMaxUses(1);
        listing.setRequiredFaction(NamespacedId.of("storynpcs", "guild"));
        listing.setRequiredFactionPoints(500);
        listing.setPage(3);

        assertThat(listing.canPurchase(600, true)).isTrue();
        assertThat(listing.canPurchase(499, true)).isFalse();  // faction gate
        assertThat(listing.canPurchase(600, false)).isFalse(); // permission gate
        listing.setUses(1);
        assertThat(listing.canPurchase(600, true)).isFalse();  // uses exhausted
        assertThatThrownBy(() -> listing.setPage(100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- P7-2 bank -----------------------------------------------------------

    @Test
    void tabCountCannotExceedSix() {
        BankerRole banker = new BankerRole();
        banker.setMaxTabs(6);
        assertThatThrownBy(() -> banker.setMaxTabs(7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> banker.setMaxTabs(0))
                .isInstanceOf(IllegalArgumentException.class);

        BankVault vault = new BankVault(UUID.randomUUID());
        vault.setUnlockedTabs(6);
        assertThatThrownBy(() -> vault.setUnlockedTabs(7))
                .isInstanceOf(IllegalArgumentException.class);
        // Zero unlocked tabs is a representable vault state (pre-first-unlock);
        // only negative and over-cap counts are rejected.
        vault.setUnlockedTabs(0);
        assertThat(vault.getUnlockedTabs()).isEqualTo(0);
        assertThatThrownBy(() -> vault.setUnlockedTabs(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BankVault.MAX_TABS).isEqualTo(6);
        assertThat(BankerRole.MAX_TABS).isEqualTo(6);
    }

    @Test
    void accessPolicyIsPrivateByDefaultAndSharedOnlyWhenListed() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        BankVault vault = new BankVault(owner);

        assertThat(vault.getAccessPolicy()).isEqualTo(BankVault.AccessPolicy.PRIVATE);
        assertThat(vault.canAccess(owner)).isTrue();
        assertThat(vault.canAccess(member)).isFalse();
        assertThat(vault.canAccess(null)).isFalse();

        vault.setAccessPolicy(BankVault.AccessPolicy.SHARED);
        vault.setSharedMemberUuids(Set.of(member));
        assertThat(vault.canAccess(member)).isTrue();
        assertThat(vault.canAccess(stranger)).isFalse();

        // Copy/restore preserve access state.
        BankVault copy = vault.copy();
        assertThat(copy.canAccess(member)).isTrue();
        assertThat(copy.getSharedMemberUuids()).containsExactly(member);
        vault.setAccessPolicy(BankVault.AccessPolicy.PRIVATE);
        vault.restoreFrom(copy);
        assertThat(vault.canAccess(member)).isTrue();
    }
}
