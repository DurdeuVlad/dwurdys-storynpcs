package com.storynpcs.domain.role.banker;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BankerRole {

    @JsonProperty
    private String bankName = "Standard Vault";

    @JsonProperty
    private int maxTabs = 4;

    @JsonProperty
    private int tabUpgradeCost = 1000;

    /**
     * Authored shared-vault owner (UUID string). When set, every player who
     * opens this banker operates on that owner's vault — gated by the vault's
     * {@link com.storynpcs.domain.role.banker.BankVault.AccessPolicy#SHARED}
     * member list (or operator authority). Empty = the opening player's own vault.
     */
    @JsonProperty
    private String vaultOwnerUuid = "";

    public BankerRole() {}

    public BankerRole(String bankName) {
        this.bankName = bankName != null ? bankName : "Standard Vault";
    }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    /** Target bank parity: a bank may have at most six tabs. */
    public static final int MAX_TABS = 6;

    public int getMaxTabs() { return maxTabs; }
    public void setMaxTabs(int maxTabs) {
        if (maxTabs < 1 || maxTabs > MAX_TABS) {
            throw new IllegalArgumentException("maxTabs must be in [1," + MAX_TABS + "]");
        }
        this.maxTabs = maxTabs;
    }

    public int getTabUpgradeCost() { return tabUpgradeCost; }
    public void setTabUpgradeCost(int tabUpgradeCost) { this.tabUpgradeCost = tabUpgradeCost; }

    public String getVaultOwnerUuid() { return vaultOwnerUuid; }
    public void setVaultOwnerUuid(String vaultOwnerUuid) {
        this.vaultOwnerUuid = vaultOwnerUuid == null ? "" : vaultOwnerUuid.trim();
    }

    /** The authored vault owner, or empty when this banker serves personal vaults. */
    public java.util.Optional<java.util.UUID> authoredVaultOwner() {
        if (vaultOwnerUuid == null || vaultOwnerUuid.isBlank()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(java.util.UUID.fromString(vaultOwnerUuid));
        } catch (IllegalArgumentException malformed) {
            return java.util.Optional.empty();
        }
    }
}