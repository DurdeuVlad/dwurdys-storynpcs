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
    void restockResetsUsesDeterministically() {
        TradeListing listing = new TradeListing("minecraft:emerald", 1, "minecraft:diamond", 1);
        listing.setMaxUses(2);
        listing.setRestockIntervalTicks(100);
        listing.setLastRestockTick(0);
        listing.setUses(2);
        assertThat(listing.restock(50)).isFalse();   // before boundary
        assertThat(listing.restock(150)).isTrue();   // boundary crossed → uses reset
        assertThat(listing.getUses()).isEqualTo(0);
        assertThat(listing.getLastRestockTick()).isEqualTo(100);
        listing.setUses(2);
        assertThat(listing.restock(150)).isFalse();  // no double restock within window
        listing.setRestockIntervalTicks(0);
        assertThat(listing.restock(1_000_000)).isFalse(); // disabled never restocks
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
