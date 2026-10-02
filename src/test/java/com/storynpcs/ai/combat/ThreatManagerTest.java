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

        // Fast-forward decay — one game tick of elapsed time per call.
        for (int i = 0; i < 500; i++) {
            threatManager.tick(10, 1);
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

    @Test
    @DisplayName("Authored aggro duration drives calm-down; default stays 400 ticks")
    void testAuthoredAggroDuration() {
        UUID player = UUID.randomUUID();

        // Default: threat does not decay before the full 400-tick window —
        // tick() consumes REAL elapsed game ticks, matching the entity's
        // ~20-tick sensing cadence.
        threatManager.addThreat(player, 20);
        for (int i = 0; i < 19; i++) {
            threatManager.tick(5, 20); // 380 elapsed — window still open
        }
        assertTrue(threatManager.getCurrentTarget().isPresent(),
                "Default aggro window (400 game ticks) must hold at 380 elapsed ticks");

        // Authored 100-tick window on a ~20-tick call cadence: decay starts
        // once the window expires, never before.
        threatManager.clearAll();
        threatManager.setAggroDurationTicks(100);
        threatManager.addThreat(player, 20);
        threatManager.tick(5, 20); // 80 left
        threatManager.tick(5, 20); // 60
        threatManager.tick(5, 20); // 40
        threatManager.tick(5, 20); // 20 — still inside the window
        assertTrue(threatManager.getCurrentTarget().isPresent(),
                "Threat must not decay before the authored window expires");
        threatManager.tick(5, 20); // window expires — first decay pulse
        for (int i = 0; i < 10; i++) {
            threatManager.tick(5, 20); // remaining threat decays away
        }
        assertTrue(threatManager.getCurrentTarget().isEmpty(),
                "Threat should decay and clear once the authored window expires");
    }

    @Test
    @DisplayName("Skipped sensing pulses consume the whole elapsed span — the authored window does not stretch")
    void testSkippedPulseElapsedAccounting() {
        UUID player = UUID.randomUUID();
        threatManager.setAggroDurationTicks(100);
        threatManager.addThreat(player, 1);

        // One deferred pulse covering 100 elapsed ticks expires the window
        // outright — the budget can't stretch authored time.
        threatManager.tick(5, 100);
        assertTrue(threatManager.getCurrentTarget().isEmpty(),
                "A single pulse spanning the whole window must end the engagement");
    }

    @Test
    @DisplayName("Non-positive elapsed ticks floor to one — the timer can never stall")
    void testElapsedFloor() {
        UUID player = UUID.randomUUID();
        threatManager.setAggroDurationTicks(40);
        threatManager.addThreat(player, 1);

        // 39 ticks floored to 1 each: 39 elapsed — window still open.
        for (int i = 0; i < 39; i++) {
            threatManager.tick(5, 0);
        }
        assertTrue(threatManager.getCurrentTarget().isPresent());
        threatManager.tick(5, -7); // floors to 1 — 40th tick ends the window
        assertTrue(threatManager.getCurrentTarget().isEmpty());
    }

    @Test
    @DisplayName("Shortening the authored duration clamps a running timer")
    void testSetAggroDurationClampsRunningTimer() {
        UUID player = UUID.randomUUID();
        threatManager.setAggroDurationTicks(400);
        threatManager.addThreat(player, 1);

        threatManager.setAggroDurationTicks(60); // running 400-window clamps to 60
        for (int i = 0; i < 3; i++) {
            threatManager.tick(5, 20); // 60 elapsed — window expires exactly
        }
        assertTrue(threatManager.getCurrentTarget().isEmpty(),
                "Clamped window must expire at the new shorter duration");
    }

    @Test
    @DisplayName("Aggro duration setter floors at 40 ticks like the authored clamp")
    void testAggroDurationFloor() {
        threatManager.setAggroDurationTicks(0);
        assertEquals(40, threatManager.getAggroDurationTicks());
        threatManager.setAggroDurationTicks(600);
        assertEquals(600, threatManager.getAggroDurationTicks());
    }

    @Test
    @DisplayName("Adding massive threat clamps at 100,000 without integer overflow")
    void testThreatOverflowClamping() {
        UUID player = UUID.randomUUID();
        threatManager.addThreat(player, 2_000_000_000);
        threatManager.addThreat(player, 2_000_000_000);
        assertTrue(threatManager.getCurrentTarget().isPresent());
        assertEquals(player, threatManager.getCurrentTarget().get());
    }
}
