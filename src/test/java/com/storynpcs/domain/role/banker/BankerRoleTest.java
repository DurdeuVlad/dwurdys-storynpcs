package com.storynpcs.domain.role.banker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BankerRoleTest {

    @Test
    @DisplayName("Default maxTabs is within the six-tab ceiling")
    void testDefaultMaxTabsIsValid() {
        BankerRole banker = new BankerRole();
        assertEquals(4, banker.getMaxTabs());
    }

    @Test
    @DisplayName("setMaxTabs rejects any value above the target's six-tab ceiling (issue #76)")
    void testSetMaxTabsRejectsAboveSix() {
        BankerRole banker = new BankerRole();
        assertThrows(IllegalArgumentException.class, () -> banker.setMaxTabs(BankerRole.MAX_TABS + 1));
        assertThrows(IllegalArgumentException.class, () -> banker.setMaxTabs(20));
    }

    @Test
    @DisplayName("setMaxTabs rejects a value below MIN_TABS")
    void testSetMaxTabsRejectsBelowMin() {
        BankerRole banker = new BankerRole();
        assertThrows(IllegalArgumentException.class, () -> banker.setMaxTabs(BankerRole.MIN_TABS - 1));
        assertThrows(IllegalArgumentException.class, () -> banker.setMaxTabs(0));
    }

    @Test
    @DisplayName("setMaxTabs accepts every value in the valid boundary range")
    void testSetMaxTabsAcceptsBoundaryValues() {
        BankerRole banker = new BankerRole();
        for (int tabs = BankerRole.MIN_TABS; tabs <= BankerRole.MAX_TABS; tabs++) {
            banker.setMaxTabs(tabs);
            assertEquals(tabs, banker.getMaxTabs());
        }
    }

    @Test
    @DisplayName("MAX_TABS is exactly six, matching the target's hard cap")
    void testMaxTabsConstantIsSix() {
        assertEquals(6, BankerRole.MAX_TABS);
    }
}
