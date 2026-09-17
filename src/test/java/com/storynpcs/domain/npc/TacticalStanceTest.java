package com.storynpcs.domain.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("TacticalStance Enum & Parsing Tests")
class TacticalStanceTest {

    @Test
    @DisplayName("TacticalStance parses strings case-insensitively and defaults safely")
    void testTacticalStanceParsing() {
        assertEquals(TacticalStance.GUARD, TacticalStance.fromString("guard"));
        assertEquals(TacticalStance.GUARD, TacticalStance.fromString("GUARD"));
        assertEquals(TacticalStance.PASSIVE, TacticalStance.fromString("passive"));
        assertEquals(TacticalStance.RETALIATE_ONLY, TacticalStance.fromString("retaliate_only"));
        assertEquals(TacticalStance.DEFENSIVE, TacticalStance.fromString("defensive"));
        assertEquals(TacticalStance.AGGRESSIVE, TacticalStance.fromString("aggressive"));
        assertEquals(TacticalStance.GUARD, TacticalStance.fromString("unknown_stance"));
        assertEquals(TacticalStance.GUARD, TacticalStance.fromString(null));
    }

    @Test
    @DisplayName("NpcAi default combat configuration matches town guard specifications")
    void testNpcAiCombatDefaults() {
        NpcAi ai = new NpcAi();
        assertEquals(TacticalStance.GUARD, ai.getTacticalStance());
        assertEquals(1, ai.getStrikeTolerance(), "Should tolerate 1 accidental hit before engaging");
        assertEquals(100, ai.getToleranceWindowTicks(), "Tolerance window should default to 5 seconds (100 ticks)");
        assertEquals(16, ai.getWitnessRadius(), "Witness scan radius should default to 16 blocks");
        assertEquals(true, ai.isDefendAllies());
        assertEquals(400, ai.getAggroDurationTicks(), "Aggro duration should default to 20 seconds (400 ticks)");
    }
}
