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
    @DisplayName("Slotted inventory with item components survives definition JSON round-trip")
    void inventoryIdentifiersRoundTrip() {
        NpcDefinition original = new NpcDefinition(NamespacedId.of("storynpcs", "quartermaster"), "Quartermaster");
        NpcInventory inventory = new NpcInventory();
        inventory.equip(NpcInventory.ItemSlot.RIGHT_HAND,
                new NpcItemStack(NamespacedId.of("minecraft:iron_sword"), 1, "{Damage:0}"));
        inventory.equip(NpcInventory.ItemSlot.HELMET,
                NpcItemStack.single(NamespacedId.of("minecraft:iron_helmet")));
        inventory.setDrop(0, NpcItemStack.single(NamespacedId.of("minecraft:bread")), 50);
        inventory.setDrop(9, new NpcItemStack(NamespacedId.of("minecraft:emerald"), 3, ""), 25);
        inventory.setLootMode(NpcInventory.LootMode.NPC_ONLY);
        original.setInventory(inventory);

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        NpcInventory restoredInv = restored.getInventory();
        assertEquals("minecraft:iron_sword",
                restoredInv.getEquipment().get(NpcInventory.ItemSlot.RIGHT_HAND).itemId().toString());
        assertEquals("{Damage:0}",
                restoredInv.getEquipment().get(NpcInventory.ItemSlot.RIGHT_HAND).components());
        assertEquals("minecraft:bread",
                restoredInv.getDrops().get(0).getItem().itemId().toString());
        assertEquals(50, restoredInv.getDrops().get(0).getChancePercent());
        assertEquals(3, restoredInv.getDrops().get(9).getItem().count());
        assertEquals(25, restoredInv.getDrops().get(9).getChancePercent());
        assertEquals(NpcInventory.LootMode.NPC_ONLY, restoredInv.getLootMode());
        assertEquals(21, restoredInv.getDrops().size());
    }

    @Test
    @DisplayName("Legacy string-list inventory migrates onto visible drop slots")
    void legacyInventoryMigratesToDropSlots() {
        String legacyJson = """
            {"id": "storynpcs:legacy_guard", "inventory": ["minecraft:iron_sword", "minecraft:shield"]}
            """;
        NpcDefinition restored = NpcDefinitionSerde.fromJson(legacyJson).orElseThrow();
        assertEquals("minecraft:iron_sword",
                restored.getInventory().getDrops().get(0).getItem().itemId().toString());
        assertEquals("minecraft:shield",
                restored.getInventory().getDrops().get(1).getItem().itemId().toString());
        assertEquals(100, restored.getInventory().getDrops().get(0).getChancePercent());
    }

    @Test
    @DisplayName("Legacy inventory migration preserves all entries within the supported slot count")
    void legacyInventoryMigrationPreservesUpToTwentyOneEntries() {
        String items = java.util.stream.IntStream.range(0, NpcInventory.DROP_SLOTS)
                .mapToObj(i -> "\"minecraft:stone\"")
                .collect(java.util.stream.Collectors.joining(","));
        String json = "{\"id\":\"storynpcs:legacy_full_inventory\",\"inventory\":[" + items + "]}";

        NpcDefinition restored = NpcDefinitionSerde.fromJson(json).orElseThrow();

        assertEquals(100, restored.getInventory().getDrops().get(9).getChancePercent());
        assertEquals(NpcInventory.DROP_SLOTS, restored.getInventory().legacyItemIds().size());
    }

    @Test
    @DisplayName("Legacy inventory migration rejects entries beyond supported slots")
    void legacyInventoryMigrationRejectsOverflowInsteadOfTruncating() {
        String items = java.util.stream.IntStream.range(0, NpcInventory.DROP_SLOTS + 1)
                .mapToObj(i -> "\"minecraft:stone\"")
                .collect(java.util.stream.Collectors.joining(","));
        String json = "{\"id\":\"storynpcs:legacy_overflow_inventory\",\"inventory\":[" + items + "]}";

        assertTrue(NpcDefinitionSerde.fromJson(json).isEmpty());
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
        var healer = new com.storynpcs.domain.role.social.HealerRole();
        healer.setHealAmount(6.0f);
        healer.setRangeBlocks(8.0);
        healer.setCooldownTicks(5_000);
        healer.setTargetPolicy(com.storynpcs.domain.role.social.HealerRole.TargetPolicy.ANY_LIVING);
        original.setHealer(healer);
        var bard = new com.storynpcs.domain.role.social.BardRole();
        bard.setSongId(NamespacedId.of("storynpcs:ballad"));
        bard.setBuffEffect(NamespacedId.of("minecraft:strength"));
        bard.setEffectRadiusBlocks(12.0);
        bard.setCooldownTicks(900);
        original.setBard(bard);

        NpcDefinition restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(original)).orElseThrow();

        assertEquals(6.0f, restored.getHealer().getHealAmount());
        assertEquals(8.0, restored.getHealer().getRangeBlocks());
        assertEquals(5_000, restored.getHealer().getCooldownTicks());
        assertEquals(com.storynpcs.domain.role.social.HealerRole.TargetPolicy.ANY_LIVING,
                restored.getHealer().getTargetPolicy());

        assertEquals(NamespacedId.of("storynpcs:ballad"), restored.getBard().getSongId());
        assertEquals(NamespacedId.of("minecraft:strength"), restored.getBard().getBuffEffect());
        assertEquals(12.0, restored.getBard().getEffectRadiusBlocks());
        assertEquals(900, restored.getBard().getCooldownTicks());
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
