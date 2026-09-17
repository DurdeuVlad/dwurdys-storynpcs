package com.storynpcs.ai.combat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThreatManager & Accidental Hit Tolerance Tests")
class ThreatManagerTest {

    private ThreatManager threatManager;

    @BeforeEach
    void setUp() {
        threatManager = new ThreatManager();
    }

    @Test
    @DisplayName("Single accidental strike within tolerance results in warning without retaliation")
    void testSingleAccidentalStrikeTolerated() {
        UUID player = UUID.randomUUID();
        long tick = 1000L;

        // Tolerance = 1, window = 100 ticks
        ThreatManager.CombatReaction reaction = threatManager.evaluateHit(player, tick, 1, 100);

        assertEquals(ThreatManager.CombatReaction.TOLERATED_WARN, reaction);
        assertEquals(1, threatManager.getStrikes(player));
        assertTrue(threatManager.getCurrentTarget().isEmpty(), "No combat target should be set on first accidental hit");
    }

    @Test
    @DisplayName("Second strike within tolerance window triggers retaliation and acquires target")
    void testSecondStrikeTriggersRetaliation() {
        UUID player = UUID.randomUUID();
        long tick1 = 1000L;
        long tick2 = 1040L; // 40 ticks later (< 100 ticks window)

        threatManager.evaluateHit(player, tick1, 1, 100);
        ThreatManager.CombatReaction secondHit = threatManager.evaluateHit(player, tick2, 1, 100);

        assertEquals(ThreatManager.CombatReaction.ENGAGE_RETALIATE, secondHit);
        assertEquals(2, threatManager.getStrikes(player));
        assertTrue(threatManager.getCurrentTarget().isPresent());
        assertEquals(player, threatManager.getCurrentTarget().get());
    }

    @Test
    @DisplayName("Strike occurring after tolerance window resets counter and tolerates again")
    void testStrikeAfterWindowDecays() {
        UUID player = UUID.randomUUID();
        long tick1 = 1000L;
        long tick2 = 1200L; // 200 ticks later (> 100 ticks window)

        threatManager.evaluateHit(player, tick1, 1, 100);
        ThreatManager.CombatReaction laterHit = threatManager.evaluateHit(player, tick2, 1, 100);

        assertEquals(ThreatManager.CombatReaction.TOLERATED_WARN, laterHit, "Strike after window should reset and warn again");
        assertEquals(1, threatManager.getStrikes(player));
        assertTrue(threatManager.getCurrentTarget().isEmpty());
    }

    @Test
    @DisplayName("Threat decays over time and clears target when out of combat")
    void testThreatDecay() {
        UUID player = UUID.randomUUID();
        threatManager.addThreat(player, 20);

        assertTrue(threatManager.getCurrentTarget().isPresent());

        // Fast-forward decay
        for (int i = 0; i < 500; i++) {
            threatManager.tick(10);
        }

        assertTrue(threatManager.getCurrentTarget().isEmpty(), "Target should clear after threat decay");
    }

    @Test
    @DisplayName("Forgiving a player immediately clears threat and strike records")
    void testForgivePlayer() {
        UUID player = UUID.randomUUID();
        threatManager.evaluateHit(player, 100L, 0, 100); // 0 tolerance -> instant retaliation
        assertTrue(threatManager.getCurrentTarget().isPresent());

        threatManager.forgive(player);

        assertTrue(threatManager.getCurrentTarget().isEmpty());
        assertEquals(0, threatManager.getStrikes(player));
    }
}
