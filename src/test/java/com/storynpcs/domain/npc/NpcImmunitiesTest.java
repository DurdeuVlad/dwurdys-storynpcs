package com.storynpcs.domain.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NpcImmunitiesTest {

    @Test
    void defaultsAreAllFalse() {
        NpcImmunities immunities = new NpcImmunities();
        assertFalse(immunities.isPotion());
        assertFalse(immunities.isFall());
        assertFalse(immunities.isSunlight());
        assertFalse(immunities.isFire());
        assertFalse(immunities.isDrowning());
        assertFalse(immunities.isCobweb());
    }

    @Test
    void eachToggleIsIndependentlySettable() {
        NpcImmunities immunities = new NpcImmunities();
        immunities.setFire(true);
        immunities.setDrowning(true);

        assertTrue(immunities.isFire());
        assertTrue(immunities.isDrowning());
        assertFalse(immunities.isPotion());
        assertFalse(immunities.isFall());
        assertFalse(immunities.isSunlight());
        assertFalse(immunities.isCobweb());
    }

    @Test
    void allSixTogglesCanBeSetTrue() {
        NpcImmunities immunities = new NpcImmunities();
        immunities.setPotion(true);
        immunities.setFall(true);
        immunities.setSunlight(true);
        immunities.setFire(true);
        immunities.setDrowning(true);
        immunities.setCobweb(true);

        assertTrue(immunities.isPotion());
        assertTrue(immunities.isFall());
        assertTrue(immunities.isSunlight());
        assertTrue(immunities.isFire());
        assertTrue(immunities.isDrowning());
        assertTrue(immunities.isCobweb());
    }
}
