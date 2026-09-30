package com.storynpcs.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.creator.gui.CustomGuiLayout;
import com.storynpcs.creator.gui.OverlaySession;
import com.storynpcs.creator.link.LinkedNpcGraph;
import com.storynpcs.creator.recipe.CarpentryRecipe;
import com.storynpcs.creator.scene.SceneDefinition;
import com.storynpcs.creator.scene.SceneTimer;
import com.storynpcs.creator.spawn.NaturalSpawnRule;
import com.storynpcs.creator.template.NpcTemplate;
import com.storynpcs.creator.template.SpawnerRule;
import com.storynpcs.creator.template.TemplateLibrary;
import com.storynpcs.creator.tools.MountPolicy;
import com.storynpcs.creator.tools.WaypointPath;
import com.storynpcs.creator.transform.TransformRule;
import com.storynpcs.creator.world.ScriptedHookBinding;
import com.storynpcs.creator.world.WorldToolDefinition;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;

class P8DomainTest {

    private static NamespacedId id(String n) { return NamespacedId.of("storynpcs", n); }

    // --- P8-1 templates/spawners --------------------------------------------

    @Test
    void templateInstantiateDeepCopiesDefinition() {
        NpcTemplate t = new NpcTemplate();
        t.setId(id("knight"));
        NpcDefinition def = new NpcDefinition();
        def.setId(id("orig"));
        def.setFactionId(id("knights"));
        t.setDefinition(def);
        NpcDefinition clone = t.instantiate(id("clone-1"));
        assertThat(clone.getId()).isEqualTo(id("clone-1"));
        assertThat(clone.getFactionId()).isEqualTo(id("knights"));
        assertThat(clone).isNotSameAs(def);
        clone.setFactionId(id("rebels"));
        assertThat(def.getFactionId()).isEqualTo(id("knights")); // no shared state
    }

    @Test
    void templateLibrarySearchImportDeleteDependents() throws Exception {
        TemplateLibrary lib = new TemplateLibrary();
        NpcTemplate t = new NpcTemplate();
        t.setId(id("guard"));
        t.setDescription("Town guard");
        t.setTags(List.of("combat", "town"));
        lib.put(t);
        lib.registerSpawner(id("guard"), id("spawner-east"));
        assertThat(lib.search("town")).containsExactly(id("guard"));
        assertThat(lib.dependentSpawners(id("guard"))).containsExactly(id("spawner-east"));
        var outcome = lib.delete(id("guard"));
        assertThat(outcome.removed()).isTrue();
        assertThat(outcome.dependentSpawners()).containsExactly(id("spawner-east"));

        // Import validation: unknown fields rejected, not ignored.
        var node = new ObjectMapper().readTree(
                "{\"id\":\"storynpcs:x\",\"schemaVersion\":1,\"definition\":{},\"evil\":true}");
        var result = lib.importValidation(node);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().get(0).code()).isEqualTo("TEMPLATE_UNKNOWN_FIELD");
    }

    @Test
    void spawnerRuleIsDeterministicAndBounded() {
        SpawnerRule rule = new SpawnerRule();
        rule.setQuota(2);
        rule.setSpawnIntervalTicks(100);
        assertThat(rule.shouldSpawn(1, 0, 100)).isTrue();
        assertThat(rule.shouldSpawn(2, 0, 100)).isFalse();  // quota
        assertThat(rule.shouldSpawn(1, 0, 99)).isFalse();   // interval
        assertThatThrownBy(() -> rule.setQuota(65)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- P8-2 movement tools -------------------------------------------------

    @Test
    void waypointEditingAndTraversalModes() {
        WaypointPath path = new WaypointPath();
        path.add(1, 64, 1); path.add(5, 64, 5); path.add(9, 64, 1);
        assertThat(path.move(1, 5, 65, 5)).isTrue();
        assertThat(path.move(3, 0, 0, 0)).isFalse();
        assertThat(path.delete(1)).isTrue();
        assertThat(path.getWaypoints()).hasSize(2);
        path.setMode(WaypointPath.TraversalMode.LOOP);
        assertThat(path.nextIndex(1, true)).isEqualTo(0); // wraps
        path.setMode(WaypointPath.TraversalMode.ONCE);
        assertThat(path.nextIndex(1, true)).isEqualTo(-1); // ends
        path.setMode(WaypointPath.TraversalMode.PING_PONG);
        assertThat(path.nextIndex(0, true)).isEqualTo(1);
        assertThat(path.nextIndex(1, false)).isEqualTo(0);
    }

    @Test
    void mountPolicyRejectsIllegalRelationships() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<UUID, UUID> mounts = new java.util.HashMap<>();
        mounts.put(b, a); // b rides a
        assertThat(MountPolicy.check(a, a, mounts)).isEqualTo(MountPolicy.Reject.SELF);
        assertThat(MountPolicy.check(a, b, mounts)).isEqualTo(MountPolicy.Reject.CYCLE);
        assertThat(MountPolicy.check(c, a, mounts)).isNull();
        assertThat(MountPolicy.check(b, c, mounts)).isEqualTo(MountPolicy.Reject.PASSENGER_ALREADY_MOUNTED);
        mounts.put(c, b); mounts.put(UUID.randomUUID(), c);
        Map<UUID, UUID> deep = new java.util.HashMap<>();
        UUID x1 = UUID.randomUUID(), x2 = UUID.randomUUID(), x3 = UUID.randomUUID(), x4 = UUID.randomUUID();
        deep.put(x2, x1); deep.put(x3, x2); deep.put(x4, x3);
        assertThat(MountPolicy.check(UUID.randomUUID(), x4, deep))
                .isEqualTo(MountPolicy.Reject.STACK_TOO_DEEP);
    }

    // --- P8-3 world tools ----------------------------------------------------

    @Test
    void worldToolDefinitionsCarryInertHooks() {
        WorldToolDefinition def = new WorldToolDefinition();
        def.setId(id("door1"));
        def.setFamily(WorldToolDefinition.Family.SCRIPTED_DOOR);
        def.setDimensionId(id("overworld"));
        def.setMaxBlocksPerActivation(8);
        assertThatThrownBy(() -> def.setMaxBlocksPerActivation(2048))
                .isInstanceOf(IllegalArgumentException.class);
        ScriptedHookBinding hook = new ScriptedHookBinding(id("on_open"), "interact");
        hook.setParameters(Map.of("speed", "2"));
        assertThatThrownBy(() -> hook.setParameters(
                java.util.Collections.nCopies(20, "k").stream()
                        .collect(java.util.stream.Collectors.toMap(k -> k + Math.random(), v -> "v"))))
                .isInstanceOf(IllegalArgumentException.class);
        def.setHooks(List.of(hook));
        assertThat(def.getHooks()).hasSize(1);
        assertThat(def.getHooks().get(0).getHookId()).isEqualTo(id("on_open"));
    }

    // --- P8-4 recipes --------------------------------------------------------

    @Test
    void carpentryRecipeValidatesGridAndSlots() {
        CarpentryRecipe r = new CarpentryRecipe();
        r.setId(id("bench_sword"));
        r.setGroupId(id("weapons"));
        r.setOutputItemId("storynpcs:sword");
        var grid = new java.util.ArrayList<>(java.util.Collections.nCopies(9, ""));
        grid.set(4, "minecraft:iron_ingot");
        grid.set(7, "bad-id"); // not namespaced
        r.setGrid(grid);
        var result = r.validate();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().get(0).code()).isEqualTo("RECIPE_BAD_SLOT");
        assertThat(result.getErrors().get(0).message()).contains("slot 7");
        grid.set(7, "minecraft:stick");
        r.setGrid(grid);
        assertThat(r.validate().isValid()).isTrue();
        assertThatThrownBy(() -> r.setGrid(List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- P8-5 links/scenes/timers/natural spawn -------------------------------

    @Test
    void linkedGraphRejectsCyclesAndMissingTargets() {
        LinkedNpcGraph g = new LinkedNpcGraph();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), missing = UUID.randomUUID();
        Set<UUID> known = Set.of(a, b, c);
        assertThat(g.link(a, b, known)).isNull();
        assertThat(g.link(b, c, known)).isNull();
        assertThat(g.link(c, a, known)).isEqualTo(LinkedNpcGraph.Reject.CYCLE);
        assertThat(g.link(a, missing, known)).isEqualTo(LinkedNpcGraph.Reject.TARGET_MISSING);
        assertThat(g.link(a, a, known)).isEqualTo(LinkedNpcGraph.Reject.SELF);
        assertThat(g.removeActor(b)).isEqualTo(2); // a→b and b→c both cleaned
        assertThat(g.links()).isEmpty();
    }

    @Test
    void scenesAreBoundedAndTimersNeverDrift() {
        SceneDefinition scene = new SceneDefinition();
        scene.setId(id("intro"));
        scene.setParticipantTemplateIds(List.of(id("a"), id("b"), id("c")));
        scene.setMaxEntities(2);
        assertThat(scene.validate().getErrors().get(0).code()).isEqualTo("SCENE_OVER_BUDGET");

        SceneTimer timers = new SceneTimer();
        UUID actor = UUID.randomUUID();
        var entry = timers.schedule(actor, 100, 50, "on_tick");
        assertThat(timers.due(99)).isEmpty();
        assertThat(timers.due(100)).hasSize(1);
        var next = timers.fired(entry.timerId(), 250);
        assertThat(next.nextFireTick()).isGreaterThan(250); // catch-up, no burst
        assertThat(timers.cancelActor(actor)).isEqualTo(1);
    }

    @Test
    void naturalSpawnRulesAreBounded() {
        NaturalSpawnRule rule = new NaturalSpawnRule();
        rule.setTemplateId(id("wolf_pack"));
        rule.setWeight(0);
        assertThat(rule.eligible(0)).isFalse(); // zero weight never spawns
        rule.setWeight(10);
        rule.setMaxPerDimension(2);
        assertThat(rule.eligible(1)).isTrue();
        assertThat(rule.eligible(2)).isFalse();
        assertThatThrownBy(() -> rule.setMaxPerDimension(200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transformRulesDeclareIdentityPolicy() {
        TransformRule rule = new TransformRule();
        rule.setId(id("wolf_to_dire"));
        rule.setTargetTemplateId(id("dire_wolf"));
        rule.setIdentityPolicy(TransformRule.IdentityPolicy.PRESERVE);
        rule.setTrigger(TransformRule.Trigger.ON_DEFEAT);
        assertThat(rule.getReplacedFacets()).contains("display");
    }

    // --- P8-6 custom GUI / overlays ------------------------------------------

    @Test
    void guiLayoutRejectsOversizedDeepInvalidTrees() {
        CustomGuiLayout layout = new CustomGuiLayout();
        layout.setId(id("hud"));
        var root = new CustomGuiLayout.GuiElement("root", "panel");
        var cur = root;
        for (int i = 0; i < 8; i++) { // depth 9 > MAX_DEPTH 6
            var child = new CustomGuiLayout.GuiElement("lvl" + i, "panel");
            cur.setChildren(List.of(child));
            cur = child;
        }
        layout.setRoot(root);
        var errors = layout.validate().getErrors();
        assertThat(errors).anyMatch(e -> e.code().equals("GUI_TOO_DEEP"));

        var flat = new CustomGuiLayout.GuiElement("root", "panel");
        flat.setChildren(java.util.Collections.nCopies(9, new CustomGuiLayout.GuiElement("c", "button")));
        layout.setRoot(flat);
        assertThat(layout.validate().getErrors())
                .anyMatch(e -> e.code().equals("GUI_TOO_MANY_CHILDREN"));
    }

    @Test
    void overlaysAreSessionScopedAndExpire() {
        OverlaySession overlays = new OverlaySession();
        UUID session = UUID.randomUUID();
        overlays.openSession(session, UUID.randomUUID());
        var ov = overlays.show(session, "hp-bar", 50, 0);
        assertThat(ov).isNotNull();
        assertThat(overlays.active(session, 49)).hasSize(1);
        assertThat(overlays.active(session, 51)).isEmpty(); // expired
        for (int i = 0; i < 10; i++) overlays.show(session, "el" + i, 100, 0);
        assertThat(overlays.active(session, 0)).hasSizeLessThanOrEqualTo(8);
        overlays.show(session, "kept", 100, 0);
        assertThat(overlays.closeSession(session)).isGreaterThan(0);
        assertThat(overlays.active(session, 0)).isEmpty();
    }
}
