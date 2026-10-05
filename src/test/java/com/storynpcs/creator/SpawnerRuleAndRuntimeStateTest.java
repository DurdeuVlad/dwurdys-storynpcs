package com.storynpcs.creator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.creator.template.SpawnerRule;
import com.storynpcs.creator.template.SpawnerRuntimeState;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.SpawnerRuntimeStore;
import com.storynpcs.runtime.spawner.NpcSpawnerRuntime;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P8-1 spawner fixtures: anchor consistency, YAML family loading, durable
 * runtime-state serde, and deterministic spawn-definition naming.
 */
class SpawnerRuleAndRuntimeStateTest {

    @TempDir
    Path tempDir;

    @Test
    void anchorIsAllOrNone() {
        var rule = new SpawnerRule();
        assertThat(rule.isAnchored()).isFalse();
        assertThat(rule.hasPartialAnchor()).isFalse();

        rule.setDimension("minecraft:overworld");
        rule.setAnchorX(5);
        assertThat(rule.hasPartialAnchor()).isTrue();
        assertThat(rule.isAnchored()).isFalse();

        rule.setAnchorY(64);
        rule.setAnchorZ(-3);
        assertThat(rule.isAnchored()).isTrue();
        assertThat(rule.hasPartialAnchor()).isFalse();
    }

    @Test
    void boundsAreEnforced() {
        var rule = new SpawnerRule();
        assertThatThrownBy(() -> rule.setQuota(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule.setQuota(65)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule.setSpawnIntervalTicks(19))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule.setPlacementRadiusBlocks(65.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spawnerFamilyLoadsFromYaml() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        String yaml = """
                id: "storynpcs:east_gate"
                templateId: "storynpcs:guard_template"
                quota: 6
                spawnIntervalTicks: 400
                placementRadiusBlocks: 12.0
                cleanupOnChunkUnload: true
                respawnOnDeath: false
                enabled: true
                dimension: "minecraft:overworld"
                anchorX: 100
                anchorY: 64
                anchorZ: -200
                """;
        var rule = loader.loadSpawner(yaml, "spawners/east_gate.yaml", result);

        assertThat(rule).isNotNull();
        assertThat(result.hasErrors()).isFalse();
        assertThat(rule.isAnchored()).isTrue();
        assertThat(rule.getQuota()).isEqualTo(6);
        assertThat(registry.getSpawnerRule(NamespacedId.of("storynpcs:east_gate"))).isPresent();
        // Missing template is a warning, not an error — the spawner is inert.
        assertThat(result.getDiagnostics())
                .anyMatch(d -> d.code().equals("SPAWNER_TEMPLATE_UNKNOWN"));
        // The dependent registers — template delete will report this spawner.
        assertThat(registry.templateSpawnerDependents(NamespacedId.of("storynpcs:guard_template")))
                .containsExactly(NamespacedId.of("storynpcs:east_gate"));
    }

    @Test
    void partialAnchorFailsLoad() {
        var loader = new YamlDefinitionLoader(new DefinitionRegistry());
        var result = ValidationResult.valid();
        String yaml = """
                id: "storynpcs:broken"
                templateId: "storynpcs:t"
                anchorX: 10
                """;
        assertThat(loader.loadSpawner(yaml, "spawners/broken.yaml", result)).isNull();
        assertThat(result.getErrors())
                .anyMatch(e -> e.code().equals("SPAWNER_PARTIAL_ANCHOR"));
    }

    @Test
    void duplicateSpawnerIdFailsLoad() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        String yaml = """
                id: "storynpcs:dup"
                templateId: "storynpcs:t"
                """;
        var first = ValidationResult.valid();
        loader.loadSpawner(yaml, "a.yaml", first);
        var second = ValidationResult.valid();
        assertThat(loader.loadSpawner(yaml, "b.yaml", second)).isNull();
        assertThat(second.getErrors())
                .anyMatch(e -> e.code().equals("DUPLICATE_DEFINITION_ID"));
    }

    @Test
    void runtimeStateSerdeRoundTrips() throws Exception {
        var mapper = new ObjectMapper();
        var state = new SpawnerRuntimeState("storynpcs:east_gate");
        state.getOwnedUuids().add("uuid-a");
        state.getOwnedUuids().add("uuid-b");
        state.setLastSpawnTick(12345L);
        state.setDeaths(2);
        state.setInstantiatedDefinitionId("storynpcs:spawned/east_gate");
        state.setMissingSince(Map.of("uuid-c", 400L));

        var restored = mapper.readValue(
                mapper.writeValueAsString(state), SpawnerRuntimeState.class);

        assertThat(restored.getOwnedUuids()).containsExactly("uuid-a", "uuid-b");
        assertThat(restored.getLastSpawnTick()).isEqualTo(12345L);
        assertThat(restored.getDeaths()).isEqualTo(2);
        assertThat(restored.getInstantiatedDefinitionId())
                .isEqualTo("storynpcs:spawned/east_gate");
        assertThat(restored.getMissingSince()).containsEntry("uuid-c", 400L);
    }

    @Test
    void runtimeStorePersistsAndDeletes() throws Exception {
        var store = new SpawnerRuntimeStore(tempDir, new ObjectMapper());
        store.open();
        var id = NamespacedId.of("storynpcs:east_gate");
        var state = new SpawnerRuntimeState(id.toString());
        state.getOwnedUuids().add("uuid-a");
        state.setDeaths(1);
        store.save(state);

        // Fresh store instance over the same directory = restart.
        var reopened = new SpawnerRuntimeStore(tempDir, new ObjectMapper());
        reopened.open();
        var loaded = reopened.load(id);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getOwnedUuids()).containsExactly("uuid-a");
        assertThat(loaded.get().getDeaths()).isEqualTo(1);
        assertThat(reopened.listIds()).containsExactly(id.toString());

        assertThat(reopened.delete(id)).isTrue();
        assertThat(reopened.load(id)).isEmpty();
    }

    @Test
    void spawnedDefinitionIdIsDeterministicAndCollisionSafe() {
        var a = NpcSpawnerRuntime.spawnedIdFor(NamespacedId.of("storynpcs:spawns/guard"));
        var b = NpcSpawnerRuntime.spawnedIdFor(NamespacedId.of("storynpcs:spawns/guard"));
        var c = NpcSpawnerRuntime.spawnedIdFor(NamespacedId.of("other:spawns/guard"));
        var d = NpcSpawnerRuntime.spawnedIdFor(NamespacedId.of("storynpcs:spawns/guard2"));

        assertThat(a).isEqualTo(b);                          // deterministic
        assertThat(a.toString()).isEqualTo("storynpcs:spawned/spawns_guard");
        assertThat(c).isNotEqualTo(a);                       // cross-namespace safe
        assertThat(d).isNotEqualTo(a);                       // per-spawner unique
    }

    @Test
    void enabledFlagDefaultsTrueAndPersists() throws Exception {
        var mapper = new ObjectMapper();
        var rule = mapper.readValue(
                "{\"id\":\"storynpcs:x\",\"templateId\":\"storynpcs:t\"}", SpawnerRule.class);
        assertThat(rule.isEnabled()).isTrue();

        rule.setEnabled(false);
        var restored = mapper.readValue(
                mapper.writeValueAsString(rule), SpawnerRule.class);
        assertThat(restored.isEnabled()).isFalse();
    }

    @Test
    void spawnedIdSetType() {
        var state = new SpawnerRuntimeState("storynpcs:x");
        state.setOwnedUuids(new java.util.LinkedHashSet<>(java.util.List.of("a", "b", "a")));
        assertThat(state.getOwnedUuids()).containsExactly("a", "b"); // deduped copy
    }
}
