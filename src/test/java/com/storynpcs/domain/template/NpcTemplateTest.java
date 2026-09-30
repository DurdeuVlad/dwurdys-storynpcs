package com.storynpcs.domain.template;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcInventory;
import com.storynpcs.domain.npc.NpcItemStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NpcTemplateTest {

    private static final NamespacedId IRON_SWORD = NamespacedId.of("minecraft:iron_sword");
    private static final NamespacedId SHIELD = NamespacedId.of("minecraft:shield");
    private static final NamespacedId GOLDEN_APPLE = NamespacedId.of("minecraft:golden_apple");

    private static NpcDefinition sourceNpc() {
        NpcDefinition def = new NpcDefinition(NamespacedId.of("storynpcs:source_npc"), "Source NPC");
        def.getInventory().equip(NpcInventory.ItemSlot.RIGHT_HAND, NpcItemStack.single(IRON_SWORD));
        def.getInventory().equip(NpcInventory.ItemSlot.LEFT_HAND, NpcItemStack.single(SHIELD));
        return def;
    }

    @Test
    void captureStoresAnIndependentSnapshot() {
        NpcDefinition source = sourceNpc();
        NpcTemplate template = NpcTemplate.capture(
                NamespacedId.of("storynpcs:guard_template"), source.getId(), source);

        assertThat(template.getId()).isEqualTo(NamespacedId.of("storynpcs:guard_template"));
        assertThat(template.getSourceNpcId()).isEqualTo(source.getId());
        assertThat(template.getSchemaVersion()).isEqualTo(NpcTemplate.CURRENT_SCHEMA_VERSION);
        assertThat(template.getRevision()).isEqualTo(1);
        assertThat(template.getEmbeddedDefinitionJson()).contains("source_npc");
        assertThat(template.getCapturedAtEpochMillis()).isGreaterThan(0);
        assertThat(template.getUpdatedAtEpochMillis()).isEqualTo(template.getCapturedAtEpochMillis());
    }

    @Test
    void mutatingSourceAfterCaptureDoesNotAffectTheTemplate() {
        NpcDefinition source = sourceNpc();
        NpcTemplate template = NpcTemplate.capture(
                NamespacedId.of("storynpcs:guard_template"), source.getId(), source);

        source.getInventory().equip(NpcInventory.ItemSlot.PROJECTILE, NpcItemStack.single(GOLDEN_APPLE));
        source.getDisplay().setName("Mutated After Capture");

        Optional<NpcDefinition> instantiated = template.instantiate(NamespacedId.of("storynpcs:guard_clone_1"));
        assertThat(instantiated).isPresent();
        var equipment = instantiated.get().getInventory().getEquipment();
        assertThat(equipment.get(NpcInventory.ItemSlot.RIGHT_HAND).itemId()).isEqualTo(IRON_SWORD);
        assertThat(equipment.get(NpcInventory.ItemSlot.LEFT_HAND).itemId()).isEqualTo(SHIELD);
        assertThat(equipment).hasSize(2);
        assertThat(instantiated.get().getDisplay().getName()).isEqualTo("Source NPC");
    }

    @Test
    void instantiateAssignsTheRequestedIdAndLeavesTheTemplateUnchanged() {
        NpcDefinition source = sourceNpc();
        NpcTemplate template = NpcTemplate.capture(
                NamespacedId.of("storynpcs:guard_template"), source.getId(), source);

        Optional<NpcDefinition> clone = template.instantiate(NamespacedId.of("storynpcs:guard_clone_1"));
        assertThat(clone).isPresent();
        assertThat(clone.get().getId()).isEqualTo(NamespacedId.of("storynpcs:guard_clone_1"));
        assertThat(template.getSourceNpcId()).isEqualTo(source.getId());
    }

    @Test
    void twoInstantiationsShareNoMutableState() {
        NpcDefinition source = sourceNpc();
        NpcTemplate template = NpcTemplate.capture(
                NamespacedId.of("storynpcs:guard_template"), source.getId(), source);

        NpcDefinition first = template.instantiate(NamespacedId.of("storynpcs:clone_a")).orElseThrow();
        NpcDefinition second = template.instantiate(NamespacedId.of("storynpcs:clone_b")).orElseThrow();

        first.getInventory().equip(NpcInventory.ItemSlot.HELMET, NpcItemStack.single(GOLDEN_APPLE));

        var firstEquipment = first.getInventory().getEquipment();
        assertThat(firstEquipment.get(NpcInventory.ItemSlot.HELMET).itemId()).isEqualTo(GOLDEN_APPLE);
        var secondEquipment = second.getInventory().getEquipment();
        assertThat(secondEquipment).doesNotContainKey(NpcInventory.ItemSlot.HELMET);
        assertThat(secondEquipment.get(NpcInventory.ItemSlot.RIGHT_HAND).itemId()).isEqualTo(IRON_SWORD);
        assertThat(secondEquipment.get(NpcInventory.ItemSlot.LEFT_HAND).itemId()).isEqualTo(SHIELD);
    }

    @Test
    void recaptureReplacesTheSnapshotAndBumpsRevision() {
        NpcDefinition source = sourceNpc();
        NpcTemplate template = NpcTemplate.capture(
                NamespacedId.of("storynpcs:guard_template"), source.getId(), source);
        long firstUpdatedAt = template.getUpdatedAtEpochMillis();

        source.getDisplay().setName("Renamed Before Recapture");
        template.recapture(source);

        assertThat(template.getRevision()).isEqualTo(2);
        assertThat(template.getUpdatedAtEpochMillis()).isGreaterThanOrEqualTo(firstUpdatedAt);
        NpcDefinition clone = template.instantiate(NamespacedId.of("storynpcs:guard_clone_2")).orElseThrow();
        assertThat(clone.getDisplay().getName()).isEqualTo("Renamed Before Recapture");
    }

    @Test
    void instantiateIsEmptyWhenTheSnapshotIsCorrupted() {
        NpcTemplate template = new NpcTemplate();
        template.setId(NamespacedId.of("storynpcs:broken_template"));
        template.setEmbeddedDefinitionJson("{ not valid json");

        assertThat(template.instantiate(NamespacedId.of("storynpcs:whatever"))).isEmpty();
    }
}
