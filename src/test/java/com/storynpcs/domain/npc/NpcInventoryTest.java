package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpcInventoryTest {

    @Test
    void slotLayoutMatchesTheTargetContract() {
        // 4 armor + 3 weapon positions; 21 drops; first 9 are the GUI slots.
        assertThat(NpcInventory.ItemSlot.values()).hasSize(7);
        assertThat(NpcInventory.DROP_SLOTS).isEqualTo(21);
        assertThat(NpcInventory.VISIBLE_DROP_SLOTS).isEqualTo(9);
        assertThat(new NpcInventory().getDrops()).hasSize(21);
    }

    @Test
    void itemStacksAreBoundedServerAuthoritativeComponents() {
        assertThatThrownBy(() -> new NpcItemStack(null, 1, ""));
        assertThatThrownBy(() -> new NpcItemStack(NamespacedId.of("minecraft:a"), 0, ""));
        assertThatThrownBy(() -> new NpcItemStack(NamespacedId.of("minecraft:a"), 100, ""));
        assertThatThrownBy(() -> new NpcItemStack(
                NamespacedId.of("minecraft:a"), 1, "x".repeat(4097)));

        NpcItemStack stack = new NpcItemStack(
                NamespacedId.of("minecraft:diamond_sword"), 1, "{Enchantments:[{id:sharpness,lvl:5}]}");
        assertThat(stack.components()).startsWith("{Enchantments");
    }

    @Test
    void equipReplacesAndUnequipsWithoutLoss() {
        NpcInventory inv = new NpcInventory();
        NpcItemStack sword = NpcItemStack.single(NamespacedId.of("minecraft:iron_sword"));
        NpcItemStack axe = NpcItemStack.single(NamespacedId.of("minecraft:iron_axe"));

        NpcInventory.EquipResult first = inv.equip(NpcInventory.ItemSlot.RIGHT_HAND, sword);
        assertThat(first.outcome()).isEqualTo(NpcInventory.EquipOutcome.EQUIPPED);
        assertThat(first.previous()).isNull();

        NpcInventory.EquipResult replaced = inv.equip(NpcInventory.ItemSlot.RIGHT_HAND, axe);
        assertThat(replaced.outcome()).isEqualTo(NpcInventory.EquipOutcome.REPLACED);
        assertThat(replaced.previous()).isSameAs(sword); // displaced stack returned — never lost

        assertThat(inv.equip(null, sword).outcome())
                .isEqualTo(NpcInventory.EquipOutcome.REJECTED_INVALID_SLOT);
        assertThat(inv.equip(NpcInventory.ItemSlot.BOOTS, null).outcome())
                .isEqualTo(NpcInventory.EquipOutcome.REJECTED_EMPTY);
        assertThat(inv.getEquipment().get(NpcInventory.ItemSlot.BOOTS)).isNull();

        assertThat(inv.unequip(NpcInventory.ItemSlot.RIGHT_HAND)).containsSame(axe);
        assertThat(inv.unequip(NpcInventory.ItemSlot.RIGHT_HAND)).isEmpty();
    }

    @Test
    void dropSlotsEnforceIndexChanceAndItemBounds() {
        NpcInventory inv = new NpcInventory();
        NpcItemStack gem = new NpcItemStack(NamespacedId.of("minecraft:emerald"), 2, "");

        assertThat(inv.setDrop(-1, gem, 50).outcome())
                .isEqualTo(NpcInventory.DropOutcome.REJECTED_INVALID_INDEX);
        assertThat(inv.setDrop(21, gem, 50).outcome())
                .isEqualTo(NpcInventory.DropOutcome.REJECTED_INVALID_INDEX);
        assertThat(inv.setDrop(0, gem, -5).outcome())
                .isEqualTo(NpcInventory.DropOutcome.REJECTED_INVALID_CHANCE);
        assertThat(inv.setDrop(0, gem, 101).outcome())
                .isEqualTo(NpcInventory.DropOutcome.REJECTED_INVALID_CHANCE);
        assertThat(inv.setDrop(0, null, 50).outcome())
                .isEqualTo(NpcInventory.DropOutcome.REJECTED_EMPTY);

        assertThat(inv.setDrop(20, gem, 33).applied()).isTrue();
        assertThat(inv.getDrops().get(20).getChancePercent()).isEqualTo(33);
        assertThat(inv.clearDrop(20).applied()).isTrue();
        assertThat(inv.getDrops().get(20).getItem()).isNull();
    }

    @Test
    void legacyItemIdsFlattensEquipmentAndDrops() {
        NpcInventory inv = new NpcInventory();
        inv.equip(NpcInventory.ItemSlot.RIGHT_HAND, NpcItemStack.single(NamespacedId.of("minecraft:bow")));
        inv.setDrop(0, NpcItemStack.single(NamespacedId.of("minecraft:arrow")), 100);

        assertThat(inv.legacyItemIds())
                .containsExactlyInAnyOrder("minecraft:bow", "minecraft:arrow");
    }

    @Test
    void lootModeDefaultsToNormalAndRoundTrips() {
        NpcInventory inv = new NpcInventory();
        assertThat(inv.getLootMode()).isEqualTo(NpcInventory.LootMode.NORMAL);
        inv.setLootMode(NpcInventory.LootMode.NPC_ONLY);
        assertThat(inv.getLootMode()).isEqualTo(NpcInventory.LootMode.NPC_ONLY);
        inv.setLootMode(null);
        assertThat(inv.getLootMode()).isEqualTo(NpcInventory.LootMode.NORMAL);
    }

    @Test
    void markCollectionsBoundAndCarryAvailability() {
        NpcDefinition def = new NpcDefinition(NamespacedId.of("storynpcs:herald"), "Herald");
        NpcMark exclaim = new NpcMark(1, 0xFFFF00, "!");
        exclaim.setAvailable(false);
        def.setMarks(java.util.List.of(exclaim, new NpcMark(2, 0x00FF00, "?")));

        assertThat(def.getMarks()).hasSize(2);
        assertThat(def.getMarks().get(0).isAvailable()).isFalse();
        assertThat(def.getMarks().get(1).getColor()).isEqualTo(0x00FF00);

        java.util.List<NpcMark> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) oversized.add(new NpcMark(i, 0, ""));
        assertThatThrownBy(() -> def.setMarks(oversized));
        assertThatThrownBy(() -> new NpcMark(-1, 0, ""));
        assertThatThrownBy(() -> new NpcMark(0, 0x1000000, ""));
        assertThatThrownBy(() -> new NpcMark(0, 0, "x".repeat(129)));
    }
}
