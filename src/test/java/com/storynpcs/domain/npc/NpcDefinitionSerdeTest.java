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
        original.getDisplay().setSkinSource(NpcDisplay.SkinSource.PLAYER);
        original.getDisplay().setSkinPlayer("CaptainMarcus");
        original.getDisplay().setCloakTexture("storynpcs:textures/entity/guard_cape.png");
        original.getDisplay().setGlowTexture("storynpcs:textures/entity/guard_glow.png");
        original.getDisplay().setVisibility(2);
        original.getDisplay().setModelId("minecraft:iron_golem");
        original.getDisplay().setModelSize(12);
        original.getDisplay().setShowNameMode(2);
        original.getDisplay().setTint(0xAABBCC);
        original.getDisplay().setLivingAnimation(false);
        original.getDisplay().setHitboxState(1);
        original.getDisplay().setBossBarMode(1);
        original.getDisplay().setBossBarColor(NpcDisplay.BossBarColor.BLUE);
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
        assertEquals(NpcDisplay.SkinSource.PLAYER, restored.getDisplay().getSkinSource());
        assertEquals("CaptainMarcus", restored.getDisplay().getSkinPlayer());
        assertEquals("storynpcs:textures/entity/guard_cape.png", restored.getDisplay().getCloakTexture());
        assertEquals("storynpcs:textures/entity/guard_glow.png", restored.getDisplay().getGlowTexture());
        assertEquals(2, restored.getDisplay().getVisibility());
        assertEquals("minecraft:iron_golem", restored.getDisplay().getModelId());
        assertEquals(12, restored.getDisplay().getModelSize());
        assertEquals(2, restored.getDisplay().getShowNameMode());
        assertEquals(0xAABBCC, restored.getDisplay().getTint());
        assertFalse(restored.getDisplay().hasLivingAnimation());
        assertEquals(1, restored.getDisplay().getHitboxState());
        assertEquals(1, restored.getDisplay().getBossBarMode());
        assertEquals(NpcDisplay.BossBarColor.BLUE, restored.getDisplay().getBossBarColor());
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
    void explicitTextureSourceSurvivesRoundTripWhenAStalePlayerNameIsPresent() {
        NpcDefinition original = new NpcDefinition(
                NamespacedId.of("storynpcs", "skin_source_order"), "Skin Source");
        original.getDisplay().setSkinPlayer("Alex");
        original.getDisplay().setSkinSource(NpcDisplay.SkinSource.TEXTURE);

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        assertEquals("Alex", restored.getDisplay().getSkinPlayer());
        assertEquals(NpcDisplay.SkinSource.TEXTURE, restored.getDisplay().getSkinSource());
    }

    @Test
    @DisplayName("String inventory identifiers survive definition JSON round-trip")
    void inventoryIdentifiersRoundTrip() {
        NpcDefinition original = new NpcDefinition(NamespacedId.of("storynpcs", "quartermaster"), "Quartermaster");
        original.setInventory(java.util.List.of("minecraft:iron_sword", "minecraft:bread"));

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        assertEquals(java.util.List.of("minecraft:iron_sword", "minecraft:bread"), restored.getInventory());
    }

    @Test
    @DisplayName("Single mark type, color, and text survive definition JSON round-trip")
    void singleMarkRoundTrips() {
        NpcDefinition original = new NpcDefinition(NamespacedId.of("storynpcs", "quest_guide"), "Quest Guide");
        original.setMark(new NpcMark(2, 0xFF1100, "Quest"));

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        assertEquals(2, restored.getMark().getType());
        assertEquals(0xFF1100, restored.getMark().getColor());
        assertEquals("Quest", restored.getMark().getText());
    }

    @Test
    @DisplayName("fromJson handles null and blank safely")
    void testMalformedJson() {
        assertTrue(NpcDefinitionSerde.fromJson(null).isEmpty());
        assertTrue(NpcDefinitionSerde.fromJson("").isEmpty());
        assertTrue(NpcDefinitionSerde.fromJson("{invalid-json}").isEmpty());
    }

    @Test
    @DisplayName("Healer and bard role config survive definition JSON round-trip")
    void healerAndBardRoleRoundTrip() {
        NpcDefinition original = new NpcDefinition(NamespacedId.of("storynpcs", "chapel_healer"), "Chapel Healer");
        original.setHealer(new com.storynpcs.domain.role.healer.HealerRole(
                6.0, 8.0, 5_000L, com.storynpcs.domain.role.healer.HealTargetMode.ANY));
        original.setBard(new com.storynpcs.domain.role.bard.BardRole(
                com.storynpcs.domain.role.bard.BardBuffType.STRENGTH, 12.0, 20_000L, 45_000L, 1));

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        assertEquals(6.0, restored.getHealer().getHealAmount());
        assertEquals(8.0, restored.getHealer().getEffectRadius());
        assertEquals(5_000L, restored.getHealer().getCooldownMillis());
        assertEquals(com.storynpcs.domain.role.healer.HealTargetMode.ANY, restored.getHealer().getTargetMode());

        assertEquals(com.storynpcs.domain.role.bard.BardBuffType.STRENGTH, restored.getBard().getBuffType());
        assertEquals(12.0, restored.getBard().getEffectRadius());
        assertEquals(20_000L, restored.getBard().getEffectDurationMillis());
        assertEquals(45_000L, restored.getBard().getCooldownMillis());
        assertEquals(1, restored.getBard().getBuffAmplifier());
    }

    @Test
    @DisplayName("Transporter role config survives definition JSON round-trip")
    void transporterRoleRoundTrip() {
        NpcDefinition original = new NpcDefinition(NamespacedId.of("storynpcs", "ferryman"), "Ferryman");
        com.storynpcs.domain.role.transporter.TransporterRole transporter =
                new com.storynpcs.domain.role.transporter.TransporterRole(
                        java.util.Set.of(NamespacedId.of("storynpcs", "harbor"), NamespacedId.of("storynpcs", "capital")),
                        25);
        original.setTransporter(transporter);

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        assertEquals(2, restored.getTransporter().getOfferedDestinationIds().size());
        assertTrue(restored.getTransporter().offers(NamespacedId.of("storynpcs", "harbor")));
        assertTrue(restored.getTransporter().offers(NamespacedId.of("storynpcs", "capital")));
        assertEquals(25, restored.getTransporter().getFeeOverride());
        assertTrue(restored.getTransporter().hasFeeOverride());
    }

    @Test
    @DisplayName("Display bounds reject unsafe model and visibility values")
    void testDisplayBounds() {
        NpcDisplay display = new NpcDisplay();
        assertThrows(IllegalArgumentException.class, () -> display.setModelSize(0));
        assertThrows(IllegalArgumentException.class, () -> display.setVisibility(3));
        assertThrows(IllegalArgumentException.class, () -> display.setScaleX(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> display.setTint(0x1000000));
        assertThrows(IllegalArgumentException.class, () -> display.setSkinUrl("x".repeat(513)));
    }
}
