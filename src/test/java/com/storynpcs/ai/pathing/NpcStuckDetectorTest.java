package com.storynpcs.ai.pathing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NpcStuckDetector & Anti-Stuck State Machine Tests")
class NpcStuckDetectorTest {

    private NpcStuckDetector detector;

    @BeforeEach
    void setUp() {
        // Window size 10 ticks, stall threshold 0.04 blocks^2
        detector = new NpcStuckDetector(10, 0.04);
    }

    @Test
    @DisplayName("Continuous forward movement results in zero stalls and no recovery action")
    void testNormalMovement() {
        double x = 0.0;
        for (int tick = 0; tick < 50; tick++) {
            x += 0.25; // moving 0.25 blocks per tick
            NpcStuckDetector.RecoveryAction action = detector.update(x, 64.0, 0.0, true);
            assertEquals(NpcStuckDetector.RecoveryAction.NONE, action);
            assertFalse(detector.isStuck());
            assertEquals(0, detector.getStalledTicks());
        }
    }

    @Test
    @DisplayName("Immobilized entity triggers progressive recovery actions as stall persists")
    void testProgressiveRecovery() {
        // Prime the 10-tick window with stationary samples
        for (int i = 0; i < 10; i++) {
            detector.update(10.0, 64.0, 10.0, true);
        }

        // Ticks 1 to 9 of stall: no action yet
        for (int i = 1; i <= 9; i++) {
            NpcStuckDetector.RecoveryAction action = detector.update(10.0, 64.0, 10.0, true);
            assertEquals(NpcStuckDetector.RecoveryAction.NONE, action);
        }

        // Tick 10: JUMP_ASSIST
        NpcStuckDetector.RecoveryAction tick10 = detector.update(10.0, 64.0, 10.0, true);
        assertEquals(NpcStuckDetector.RecoveryAction.JUMP_ASSIST, tick10);
        assertTrue(detector.isStuck());

        // Advance to tick 20: ADVANCE_NODE
        for (int i = 11; i <= 19; i++) {
            detector.update(10.0, 64.0, 10.0, true);
        }
        NpcStuckDetector.RecoveryAction tick20 = detector.update(10.0, 64.0, 10.0, true);
        assertEquals(NpcStuckDetector.RecoveryAction.ADVANCE_NODE, tick20);

        // Advance to tick 35: REPATH
        for (int i = 21; i <= 34; i++) {
            detector.update(10.0, 64.0, 10.0, true);
        }
        NpcStuckDetector.RecoveryAction tick35 = detector.update(10.0, 64.0, 10.0, true);
        assertEquals(NpcStuckDetector.RecoveryAction.REPATH, tick35);

        // Advance to tick 50: NUDGE
        for (int i = 36; i <= 49; i++) {
            detector.update(10.0, 64.0, 10.0, true);
        }
        NpcStuckDetector.RecoveryAction tick50 = detector.update(10.0, 64.0, 10.0, true);
        assertEquals(NpcStuckDetector.RecoveryAction.NUDGE, tick50);

        // Advance to tick 65: EMERGENCY_UNSTUCK
        for (int i = 51; i <= 64; i++) {
            detector.update(10.0, 64.0, 10.0, true);
        }
        NpcStuckDetector.RecoveryAction tick65 = detector.update(10.0, 64.0, 10.0, true);
        assertEquals(NpcStuckDetector.RecoveryAction.EMERGENCY_UNSTUCK, tick65);
    }

    @Test
    @DisplayName("Stopping navigation immediately clears stall state")
    void testStopNavigationResetsState() {
        // Stall entity
        for (int i = 0; i < 25; i++) {
            detector.update(5.0, 64.0, 5.0, true);
        }
        assertTrue(detector.isStuck());

        // Stop navigation
        NpcStuckDetector.RecoveryAction action = detector.update(5.0, 64.0, 5.0, false);
        assertEquals(NpcStuckDetector.RecoveryAction.NONE, action);
        assertFalse(detector.isStuck());
        assertEquals(0, detector.getStalledTicks());
    }

    @Test
    @DisplayName("Resuming movement gradually decreases stall counter")
    void testResumeMovementRecovers() {
        // Stall until tick 12
        for (int i = 0; i < 22; i++) {
            detector.update(5.0, 64.0, 5.0, true);
        }
        assertTrue(detector.isStuck());
        int stallsBefore = detector.getStalledTicks();

        // Entity starts moving
        double x = 5.0;
        for (int i = 0; i < 15; i++) {
            x += 0.5;
            detector.update(x, 64.0, 5.0, true);
        }

        assertTrue(detector.getStalledTicks() < stallsBefore, "Stalled ticks should decrease as entity moves");
    }
}
