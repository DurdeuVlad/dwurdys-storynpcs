package com.storynpcs.domain.template;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NpcTemplateTest {

    private static NpcDefinition sourceNpc() {
        NpcDefinition def = new NpcDefinition(NamespacedId.of("storynpcs:source_npc"), "Source NPC");
        def.setInventory(new ArrayList<>(List.of("minecraft:iron_sword", "minecraft:shield")));
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

        source.getInventory().add("minecraft:diamond_sword");
        source.getDisplay().setName("Mutated After Capture");

        Optional<NpcDefinition> instantiated = template.instantiate(NamespacedId.of("storynpcs:guard_clone_1"));
        assertThat(instantiated).isPresent();
        assertThat(instantiated.get().getInventory()).containsExactly("minecraft:iron_sword", "minecraft:shield");
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

        first.getInventory().add("minecraft:golden_apple");

        assertThat(first.getInventory()).contains("minecraft:golden_apple");
        assertThat(second.getInventory()).doesNotContain("minecraft:golden_apple");
        assertThat(second.getInventory()).containsExactly("minecraft:iron_sword", "minecraft:shield");
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
