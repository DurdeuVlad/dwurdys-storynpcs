package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NpcDefinitionSerdeTest {

    @Test
    @DisplayName("NpcDefinition round-trips through JSON serialization with all fields intact")
    void testJsonRoundTrip() {
        NpcDefinition original = new NpcDefinition(NamespacedId.of("storynpcs", "veteran_guard"), "Veteran Guard");
        original.getDisplay().setTitle("City Watch");
        original.getDisplay().setSkinTexture("storynpcs:textures/entity/guard.png");
        original.getStats().setMaxHealth(50.0);
        original.getStats().setAttackDamage(8.5);
        original.getStats().setMovementSpeed(0.28);
        original.getAi().setMovementType(NpcAi.MovementType.WANDERING);
        original.getAi().setWalkingRange(20);
        original.getAi().setTacticalStance(TacticalStance.AGGRESSIVE);
        original.setDialogueId(NamespacedId.of("storynpcs", "guard_dialogue"));
        original.setFactionId(NamespacedId.of("storynpcs", "town_guard"));

        String json = NpcDefinitionSerde.toJson(original);
        assertNotNull(json);
        assertTrue(json.contains("veteran_guard"));
        assertTrue(json.contains("City Watch"));

        var restoredOpt = NpcDefinitionSerde.fromJson(json);
        assertTrue(restoredOpt.isPresent());

        NpcDefinition restored = restoredOpt.get();
        assertEquals(original.getId(), restored.getId());
        assertEquals("Veteran Guard", restored.getDisplay().getName());
        assertEquals("City Watch", restored.getDisplay().getTitle());
        assertEquals("storynpcs:textures/entity/guard.png", restored.getDisplay().getSkinTexture());
        assertEquals(50.0, restored.getStats().getMaxHealth());
        assertEquals(8.5, restored.getStats().getAttackDamage());
        assertEquals(0.28, restored.getStats().getMovementSpeed());
        assertEquals(NpcAi.MovementType.WANDERING, restored.getAi().getMovementType());
        assertEquals(20, restored.getAi().getWalkingRange());
        assertEquals(TacticalStance.AGGRESSIVE, restored.getAi().getTacticalStance());
        assertEquals(NamespacedId.of("storynpcs", "guard_dialogue"), restored.getDialogueId());
        assertEquals(NamespacedId.of("storynpcs", "town_guard"), restored.getFactionId());
    }

    @Test
    @DisplayName("fromJson handles null and blank safely")
    void testMalformedJson() {
        assertTrue(NpcDefinitionSerde.fromJson(null).isEmpty());
        assertTrue(NpcDefinitionSerde.fromJson("").isEmpty());
        assertTrue(NpcDefinitionSerde.fromJson("{invalid-json}").isEmpty());
    }
}
