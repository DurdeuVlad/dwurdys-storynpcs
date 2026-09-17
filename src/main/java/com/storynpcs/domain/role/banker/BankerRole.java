package com.storynpcs.domain.role.banker;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BankerRole {

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
    public void setMaxTabs(int maxTabs) { this.maxTabs = maxTabs; }

    public int getTabUpgradeCost() { return tabUpgradeCost; }
    public void setTabUpgradeCost(int tabUpgradeCost) { this.tabUpgradeCost = tabUpgradeCost; }
}