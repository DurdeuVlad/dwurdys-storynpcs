package com.storynpcs.domain.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NpcResistancesTest {

    @Test
    void defaultsAreNeutral() {
        NpcResistances resistances = new NpcResistances();
        assertEquals(1.0, resistances.getKnockback());
        assertEquals(1.0, resistances.getArrow());
        assertEquals(1.0, resistances.getMelee());
        assertEquals(1.0, resistances.getExplosion());
    }

    @Test
    void valuesAboveMaxAreClampedNotRejected() {
        NpcResistances resistances = new NpcResistances();
        resistances.setKnockback(5.0);
        resistances.setArrow(2.01);
        resistances.setMelee(100.0);
        resistances.setExplosion(Double.MAX_VALUE);

        assertEquals(2.0, resistances.getKnockback());
        assertEquals(2.0, resistances.getArrow());
        assertEquals(2.0, resistances.getMelee());
        assertEquals(2.0, resistances.getExplosion());
    }

    @Test
    void valuesBelowMinAreClampedNotRejected() {
        NpcResistances resistances = new NpcResistances();
        resistances.setKnockback(-1.0);
        resistances.setArrow(-0.01);
        resistances.setMelee(Double.NEGATIVE_INFINITY);

        assertEquals(0.0, resistances.getKnockback());
        assertEquals(0.0, resistances.getArrow());
        assertEquals(0.0, resistances.getMelee());
    }

    @Test
    void boundaryValuesSurviveUnchanged() {
        NpcResistances resistances = new NpcResistances();
        resistances.setKnockback(0.0);
        resistances.setArrow(2.0);
        assertEquals(0.0, resistances.getKnockback());
        assertEquals(2.0, resistances.getArrow());
    }

    @Test
    void nanFallsBackToNeutralRatherThanSerializingAnInvalidValue() {
        NpcResistances resistances = new NpcResistances();
        resistances.setExplosion(Double.NaN);
        assertEquals(1.0, resistances.getExplosion());
    }
}
