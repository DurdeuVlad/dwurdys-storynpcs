package com.storynpcs.domain.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefeatResolution} — the headless defeat-mode contract the
 * entity executes in {@code die()} (issue #59 / P3-2).
 */
class DefeatResolutionTest {

    private static NpcStats statsWith(NpcStats.Defeat.Mode mode, int respawnSeconds, int fleePct) {
        NpcStats stats = new NpcStats();
        NpcStats.Defeat defeat = new NpcStats.Defeat();
        defeat.setMode(mode);
        defeat.setFleeHealthPercent(fleePct);
        stats.setDefeat(defeat);
        stats.setRespawnTimeSeconds(respawnSeconds);
        return stats;
    }

    @Test
    @DisplayName("DIE mode performs real death with no side-band behavior")
    void testDieResolvesToDeath() {
        var d = DefeatResolution.resolve(statsWith(NpcStats.Defeat.Mode.DIE, 60, 10));
        assertEquals(NpcStats.Defeat.Mode.DIE, d.mode());
        assertTrue(d.performsDeath());
        assertFalse(d.returnsHome());
    }

    @Test
    @DisplayName("HIDE suppresses death and schedules reappearance from the respawn timer")
    void testHideSchedulesRespawn() {
        var d = DefeatResolution.resolve(statsWith(NpcStats.Defeat.Mode.HIDE, 30, 10));
        assertEquals(NpcStats.Defeat.Mode.HIDE, d.mode());
        assertFalse(d.performsDeath());
        assertEquals(600, d.hiddenTicks()); // 30s * 20 ticks
        assertFalse(d.returnsHome());
    }

    @Test
    @DisplayName("HIDE with non-positive respawn stays hidden indefinitely")
    void testHideNonPositiveTimer() {
        // respawnTimeSeconds is schema-clamped to [0, 86400] — 0 is the only
        // reachable "no timer" value.
        var d = DefeatResolution.resolve(statsWith(NpcStats.Defeat.Mode.HIDE, 0, 10));
        assertEquals(-1, d.hiddenTicks());
    }

    @Test
    @DisplayName("FLEE survives at the authored health threshold and returns home")
    void testFleeThreshold() {
        var d = DefeatResolution.resolve(statsWith(NpcStats.Defeat.Mode.FLEE, 0, 25));
        assertEquals(NpcStats.Defeat.Mode.FLEE, d.mode());
        assertFalse(d.performsDeath());
        assertEquals(0.25f, d.healthAfterFraction(), 1e-6);
        assertTrue(d.returnsHome());
    }

    @Test
    @DisplayName("FLEE clamps a 0% threshold to a minimum survivable fraction")
    void testFleeMinimumHealth() {
        var d = DefeatResolution.resolve(statsWith(NpcStats.Defeat.Mode.FLEE, 0, 0));
        assertEquals(0.01f, d.healthAfterFraction(), 1e-6);
    }

    @Test
    @DisplayName("null stats/defeat resolve to plain death — never silently fakes survival")
    void testDefaultsDie() {
        var d = DefeatResolution.resolve(null);
        assertTrue(d.performsDeath());
        assertEquals(NpcStats.Defeat.Mode.DIE, d.mode());

        NpcStats bare = new NpcStats();
        d = DefeatResolution.resolve(bare);
        assertTrue(d.performsDeath());
    }
}
