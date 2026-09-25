package com.storynpcs.domain.role.banker;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BankerRole {

    /** Issue #76 acceptance criterion: "tab count cannot exceed six." */
    public static final int MIN_TABS = 1;
    public static final int MAX_TABS = 6;

    @JsonProperty
    private String bankName = "Standard Vault";

    @JsonProperty
    private int maxTabs = 4;

    @JsonProperty
    private int tabUpgradeCost = 1000;

    public BankerRole() {}

    public BankerRole(String bankName) {
        this.bankName = bankName != null ? bankName : "Standard Vault";
    }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public int getMaxTabs() { return maxTabs; }

    /**
     * Rejects any value outside [{@link #MIN_TABS}, {@link #MAX_TABS}] rather
     * than silently clamping, matching how other hard-bounded fields in this
     * codebase (e.g. {@code NpcDisplay}'s model/visibility bounds) fail loudly
     * on an authoring mistake instead of tolerating it.
     */
    public void setMaxTabs(int maxTabs) {
        if (maxTabs < MIN_TABS || maxTabs > MAX_TABS) {
            throw new IllegalArgumentException(
                    "maxTabs must be between " + MIN_TABS + " and " + MAX_TABS + " (was " + maxTabs + ")");
        }
        this.maxTabs = maxTabs;
    }

    public int getTabUpgradeCost() { return tabUpgradeCost; }
    public void setTabUpgradeCost(int tabUpgradeCost) { this.tabUpgradeCost = tabUpgradeCost; }
}