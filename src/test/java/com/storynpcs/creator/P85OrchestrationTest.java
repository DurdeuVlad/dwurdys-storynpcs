package com.storynpcs.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.P85OrchestrationEvents.NpcLinkEvent;
import com.storynpcs.api.event.P85OrchestrationEvents.NpcTimerEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.creator.link.LinkedNpcGraph;
import com.storynpcs.creator.scene.SceneDefinition;
import com.storynpcs.creator.scene.SceneTimer;
import com.storynpcs.creator.spawn.NaturalSpawnRule;
import com.storynpcs.creator.transform.TransformRule;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.LinkedNpcStore;
import com.storynpcs.persistence.SceneTimerStore;
import com.storynpcs.runtime.orchestration.LinkedNpcRuntime;
import com.storynpcs.runtime.orchestration.TimerRuntime;
import com.storynpcs.service.MutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/** P8-5: linked NPCs, scenes, transforms, timers, natural spawn. */
class P85OrchestrationTest {

    @TempDir
    Path tempDir;

    private static final String SCENE_YAML = """
            schemaVersion: 1
            id: "storynpcs:village_festival"
            participantTemplateIds: ["storynpcs:bard", "storynpcs:dancer"]
            maxEntities: 8
            maxDurationTicks: 600
            cancelRecovery: restore_positions
            stages:
              - name: gather
                durationTicks: 100
                cueText: "The crowd assembles"
              - name: perform
                durationTicks: 200
                cueText: "The show begins"
            """;

    private static final String TRANSFORM_YAML = """
            schemaVersion: 1
            id: "storynpcs:villager_to_guard"
            targetTemplateId: "storynpcs:guard_template"
            identityPolicy: preserve
            trigger: manual
            replacedFacets: ["display", "stats"]
            """;

    private static final String NATURAL_YAML = """
            schemaVersion: 1
            id: "storynpcs:wild_merchant"
            templateId: "storynpcs:merchant_template"
            dimensionId: "minecraft:overworld"
            weight: 15
            maxPerDimension: 4
            minPlayerDistanceBlocks: 32
            enabled: true
            """;

    // ── definitions + loader ─────────────────────────────────────────────────

    @Test
    void sceneYamlLoadsWithBudgetsAndStages() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadScene(SCENE_YAML, "scenes/festival.yaml", result)).isNotNull();
        assertThat(result.getErrors()).isEmpty();
        var scene = registry.getScene(NamespacedId.of("storynpcs:village_festival")).orElseThrow();
        assertThat(scene.getStages()).hasSize(2);
        assertThat(scene.getMaxEntities()).isEqualTo(8);
    }

    @Test
    void overBudgetSceneFailsAtLoad() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadScene(SCENE_YAML.replace("maxEntities: 8", "maxEntities: 1"),
                "scenes/festival.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code()).contains("SCENE_OVER_BUDGET");
        assertThat(registry.getAllScenes()).isEmpty();
    }

    @Test
    void transformAndNaturalSpawnYamlLoad() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadTransform(TRANSFORM_YAML, "transforms/guard.yaml", result)).isNotNull();
        assertThat(loader.loadNaturalSpawn(NATURAL_YAML, "naturalspawns/merchant.yaml", result)).isNotNull();
        assertThat(result.getErrors()).isEmpty();
        assertThat(registry.getTransform(NamespacedId.of("storynpcs:villager_to_guard"))).isPresent();
        assertThat(registry.getNaturalSpawn(NamespacedId.of("storynpcs:wild_merchant"))).isPresent();
    }

    @Test
    void transformWithoutTargetFailsClosed() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadTransform("""
                schemaVersion: 1
                id: "storynpcs:bad"
                """, "transforms/bad.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("TRANSFORM_MISSING_TARGET");
    }

    @Test
    void duplicateIdsFailDeterministicallyAcrossFamilies() {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        assertThat(loader.loadScene(SCENE_YAML, "a.yaml", ValidationResult.valid())).isNotNull();
        var result = ValidationResult.valid();
        assertThat(loader.loadScene(SCENE_YAML, "b.yaml", result)).isNull();
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("DUPLICATE_DEFINITION_ID");
    }

    // ── canonical ops ────────────────────────────────────────────────────────

    private StoryNpcsApplicationService service(DefinitionRegistry registry) throws Exception {
        Files.createDirectories(tempDir.resolve("progression"));
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir);
        var service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir.resolve("progression")),
                new EventPublisher());
        service.setLoader(loader);
        return service;
    }

    @Test
    void canonicalSceneTransformNaturalSpawnRoundTrip() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);

        var scene = new SceneDefinition();
        scene.setId(NamespacedId.of("storynpcs:village_festival"));
        scene.setParticipantTemplateIds(List.of(NamespacedId.of("storynpcs:bard")));
        scene.setStages(List.of(new SceneDefinition.SceneStage("act", 100, null)));
        assertThat(service.saveScene(scene).getErrors()).isEmpty();
        assertThat(registry.getScene(scene.getId())).isPresent();
        assertThat(Files.exists(tempDir.resolve("scenes"))).isTrue();

        var rule = new TransformRule();
        rule.setId(NamespacedId.of("storynpcs:villager_to_guard"));
        rule.setTargetTemplateId(NamespacedId.of("storynpcs:guard_template"));
        assertThat(service.saveTransform(rule).getErrors()).isEmpty();

        var spawn = new NaturalSpawnRule();
        spawn.setId(NamespacedId.of("storynpcs:wild_merchant"));
        spawn.setTemplateId(NamespacedId.of("storynpcs:merchant_template"));
        assertThat(service.saveNaturalSpawn(spawn).getErrors()).isEmpty();

        var delete = service.deleteScene(new MutationRequest("scene.delete", "command",
                "scene.delete", scene.getId(),
                service.currentRevision("scene", scene.getId()), UUID.randomUUID()));
        assertThat(delete.applied()).isTrue();
        assertThat(registry.getScene(scene.getId())).isEmpty();
    }

    @Test
    void canonicalRejectsMalformedDefinitionBeforeWrite() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        var spawn = new NaturalSpawnRule();
        spawn.setId(NamespacedId.of("storynpcs:no_template"));
        // templateId intentionally null
        var result = service.saveNaturalSpawn(spawn);
        assertThat(result.getErrors()).extracting(e -> e.code())
                .contains("NATURALSPAWN_MISSING_TEMPLATE");
        assertThat(Files.exists(tempDir.resolve("naturalspawns"))).isFalse();
    }

    // ── linked NPCs ──────────────────────────────────────────────────────────

    @Test
    void linkRejectsSelfCycleAndMissingTarget() {
        var graph = new LinkedNpcGraph();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertThat(graph.link(a, a, Set.of(a))).isEqualTo(LinkedNpcGraph.Reject.SELF);
        assertThat(graph.link(a, UUID.randomUUID(), Set.of(a)))
                .isEqualTo(LinkedNpcGraph.Reject.TARGET_MISSING);
        assertThat(graph.link(a, b, Set.of(a, b, c))).isNull();
        assertThat(graph.link(b, c, Set.of(a, b, c))).isNull();
        assertThat(graph.link(c, a, Set.of(a, b, c)))
                .isEqualTo(LinkedNpcGraph.Reject.CYCLE);
    }

    @Test
    void linkRuntimePersistsRehydratesAndCleansUp() throws Exception {
        var store = new LinkedNpcStore(tempDir.resolve("links"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.open();
        var seen = new CopyOnWriteArrayList<StoryNpcsEvent>();
        var events = new EventPublisher();
        events.register(seen::add);
        var runtime = new LinkedNpcRuntime();
        runtime.attach(store, events);

        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(runtime.link(a, b, Set.of(a, b))).isNull();
        assertThat(store.load(a)).isPresent();
        assertThat(seen).filteredOn(e -> e instanceof NpcLinkEvent l
                && l.action() == NpcLinkEvent.Action.LINKED).hasSize(1);

        // Rehydrate through a fresh runtime — the link survives restart.
        var runtime2 = new LinkedNpcRuntime();
        runtime2.attach(store, new EventPublisher());
        assertThat(runtime2.links()).containsEntry(a, b);

        // Departing actor cleans up both directions durably.
        assertThat(runtime2.removeActor(b)).isEqualTo(1);
        assertThat(runtime2.links()).isEmpty();
        assertThat(store.load(a)).isEmpty();
    }

    // ── timers ───────────────────────────────────────────────────────────────

    @Test
    void timerSurvivesRestartAndFiresAtMostOnce() throws Exception {
        var store = new SceneTimerStore(tempDir.resolve("timers"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.open();
        var events = new EventPublisher();
        var fired = new CopyOnWriteArrayList<StoryNpcsEvent>();
        events.register(fired::add);
        var runtime = new TimerRuntime();
        runtime.attach(store, events);

        UUID actor = UUID.randomUUID();
        var entry = runtime.schedule(actor, 10, 20, "storynpcs:my_event");
        assertThat(store.load(entry.timerId())).isPresent();

        // Simulate restart: new runtime rehydrates with the SAME timer id.
        var runtime2 = new TimerRuntime();
        runtime2.attach(store, events);
        assertThat(runtime2.timers()).containsKey(entry.timerId());

        // Fire at t=10 — once only; the schedule advances past the fire tick.
        assertThat(runtime2.tick(10)).isEqualTo(1);
        assertThat(runtime2.tick(10)).isEqualTo(0);
        assertThat(runtime2.tick(29)).isEqualTo(0);
        assertThat(runtime2.tick(30)).isEqualTo(1);
        assertThat(fired).filteredOn(e -> e instanceof NpcTimerEvent).hasSize(2);
    }

    @Test
    void timerCatchUpAdvancesWithoutBursting() {
        var timer = new SceneTimer();
        UUID actor = UUID.randomUUID();
        var entry = timer.schedule(actor, 0, 10, "e");
        // 100 ticks passed while offline — due() reports ONE firing; fired()
        // skips missed periods instead of bursting 10 events.
        var due = timer.due(100);
        assertThat(due).hasSize(1);
        var next = timer.fired(entry.timerId(), 100);
        assertThat(next.nextFireTick()).isGreaterThan(100);
        assertThat(timer.due(100)).isEmpty();
    }

    @Test
    void cancelActorRemovesTimersDurably() throws Exception {
        var store = new SceneTimerStore(tempDir.resolve("timers2"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.open();
        var runtime = new TimerRuntime();
        runtime.attach(store, new EventPublisher());
        UUID actor = UUID.randomUUID();
        runtime.schedule(actor, 5, 10, "e1");
        runtime.schedule(actor, 5, 10, "e2");
        runtime.schedule(UUID.randomUUID(), 5, 10, "e3");
        assertThat(runtime.cancelActor(actor)).isEqualTo(2);
        assertThat(runtime.timers()).hasSize(1);
        assertThat(store.listIds()).hasSize(1);
    }

    // ── natural spawn rule bounds ────────────────────────────────────────────

    @Test
    void naturalSpawnBoundsAndEligibility() {
        var rule = new NaturalSpawnRule();
        rule.setId(NamespacedId.of("storynpcs:r"));
        rule.setTemplateId(NamespacedId.of("storynpcs:t"));
        assertThat(rule.eligible(0)).isTrue();
        assertThat(rule.eligible(8)).isFalse(); // at default cap
        rule.setWeight(0);
        assertThat(rule.eligible(0)).isFalse(); // zero weight never spawns
        assertThatThrownBy(() -> rule.setWeight(1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule.setMaxPerDimension(129))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule.setMinPlayerDistanceBlocks(300))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weightedPickHonorsWeightsDeterministically() {
        var heavy = rule("storynpcs:heavy", 90);
        var light = rule("storynpcs:light", 10);
        var zero = rule("storynpcs:zero", 0);
        var rules = java.util.List.of(heavy, light, zero);
        // Same seed → same pick; the zero-weight rule is never selected.
        var counts = new java.util.HashMap<NaturalSpawnRule, Integer>();
        for (long seed = 0; seed < 2000; seed++) {
            var picked = com.storynpcs.runtime.orchestration.NaturalSpawnRuntime
                    .weightedPick(rules, net.minecraft.util.RandomSource.create(seed));
            assertThat(picked).isNotSameAs(zero);
            counts.merge(picked, 1, Integer::sum);
        }
        var same = com.storynpcs.runtime.orchestration.NaturalSpawnRuntime
                .weightedPick(rules, net.minecraft.util.RandomSource.create(42));
        assertThat(com.storynpcs.runtime.orchestration.NaturalSpawnRuntime
                .weightedPick(rules, net.minecraft.util.RandomSource.create(42)))
                .isSameAs(same);
        // 90/10 split must dominate over 2000 draws — generous 3x bound.
        assertThat(counts.get(heavy)).isGreaterThan(counts.get(light) * 3);
        assertThat(com.storynpcs.runtime.orchestration.NaturalSpawnRuntime
                .weightedPick(java.util.List.of(), net.minecraft.util.RandomSource.create(1)))
                .isNull();
    }

    private static NaturalSpawnRule rule(String id, int weight) {
        var r = new NaturalSpawnRule();
        r.setId(NamespacedId.of(id));
        r.setWeight(weight);
        return r;
    }
}
