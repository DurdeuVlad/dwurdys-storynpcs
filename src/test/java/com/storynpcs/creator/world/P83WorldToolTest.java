package com.storynpcs.creator.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.runtime.worldtool.WorldToolExecutor;
import com.storynpcs.runtime.worldtool.WorldToolExecutor.LegStatus;
import com.storynpcs.runtime.worldtool.WorldToolExecutor.PlannedLeg;

class P83WorldToolTest {

    // ── catalog contract ────────────────────────────────────────────────────

    @Test
    void everyFamilyHasAnExplicitOpsContract() {
        for (var family : WorldToolDefinition.Family.values()) {
            var ops = WorldToolOpsCatalog.opsFor(family);
            assertThat(ops.reversible().size() + ops.irreversible().size())
                    .as("family %s must declare at least one op", family)
                    .isGreaterThan(0);
            for (var op : ops.reversible()) {
                assertThat(op.opId()).isNotBlank();
                assertThat(op.description()).isNotBlank();
            }
            for (var op : ops.irreversible()) {
                assertThat(op.opId()).isNotBlank();
                assertThat(op.description()).isNotBlank();
            }
        }
    }

    @Test
    void destructiveFamiliesDeclareTheirIrreversibleLegs() {
        // Redstone pulses propagate to neighbors — cataloged irreversible.
        var redstone = WorldToolOpsCatalog.opsFor(WorldToolDefinition.Family.REDSTONE);
        assertThat(redstone.irreversible().stream().map(WorldToolDefinition.IrreversibleOp::opId))
                .contains("signal.pulse");
        // Mailbox mail interactions are business transactions — logged, not rolled back.
        var mailbox = WorldToolOpsCatalog.opsFor(WorldToolDefinition.Family.MAILBOX);
        assertThat(mailbox.irreversible().stream().map(WorldToolDefinition.IrreversibleOp::opId))
                .contains("mail.interact");
    }

    // ── definition bounds ───────────────────────────────────────────────────

    @Test
    void budgetAndHooksAreBounded() {
        var tool = new WorldToolDefinition();
        assertThatThrownBy(() -> tool.setMaxBlocksPerActivation(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.setMaxBlocksPerActivation(1025))
                .isInstanceOf(IllegalArgumentException.class);
        tool.setMaxBlocksPerActivation(1024);

        var hooks = new java.util.ArrayList<ScriptedHookBinding>();
        for (int i = 0; i < WorldToolDefinition.MAX_HOOKS + 1; i++) {
            hooks.add(new ScriptedHookBinding(NamespacedId.of("storynpcs:h" + i), "interact"));
        }
        assertThatThrownBy(() -> tool.setHooks(hooks))
                .isInstanceOf(IllegalArgumentException.class);
        hooks.remove(hooks.size() - 1);
        tool.setHooks(hooks);
        assertThat(tool.getHooks()).hasSize(WorldToolDefinition.MAX_HOOKS);
    }

    @Test
    void placesBlockCoversTheBlockFamilies() {
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.SCRIPTED_BLOCK)).isTrue();
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.SCRIPTED_DOOR)).isTrue();
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.MAILBOX)).isTrue();
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.REDSTONE)).isTrue();
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.BANNER)).isTrue();
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.SCRIPTER)).isFalse();
        assertThat(WorldToolDefinition.placesBlock(WorldToolDefinition.Family.SCENE)).isFalse();
    }

    // ── executePlan: the exact-status rollback contract ─────────────────────

    @Test
    void successfulPlanAppliesEveryLeg() {
        AtomicInteger applied = new AtomicInteger();
        var report = WorldToolExecutor.executePlan("storynpcs:t1", List.of(
                new PlannedLeg("a", true, () -> { applied.incrementAndGet(); return () -> {}; }),
                new PlannedLeg("b", false, () -> { applied.incrementAndGet(); return null; })));
        assertThat(applied.get()).isEqualTo(2);
        assertThat(report.fullyApplied()).isTrue();
        assertThat(report.legs()).extracting(WorldToolExecutor.LegResult::status)
                .containsExactly(LegStatus.APPLIED, LegStatus.LOGGED);
    }

    @Test
    void failedLegRollsBackAppliedReversibleLegsInReverseOrder() {
        var order = new StringBuilder();
        var report = WorldToolExecutor.executePlan("storynpcs:t1", List.of(
                new PlannedLeg("a", true, () -> { order.append("A"); return () -> order.append("~A"); }),
                new PlannedLeg("b", true, () -> { order.append("B"); return () -> order.append("~B"); }),
                new PlannedLeg("boom", true, () -> { throw new IllegalStateException("nope"); }),
                new PlannedLeg("never", true, () -> null)));
        assertThat(report.fullyApplied()).isFalse();
        assertThat(order.toString()).isEqualTo("AB~B~A");
        assertThat(report.legs()).extracting(WorldToolExecutor.LegResult::status)
                .containsExactly(LegStatus.ROLLED_BACK, LegStatus.ROLLED_BACK,
                        LegStatus.FAILED, LegStatus.NOT_RUN);
        assertThat(report.failures()).extracting(WorldToolExecutor.LegResult::opId)
                .containsExactly("boom");
    }

    @Test
    void irreversibleLegsThatRanBeforeAFailureAreLoggedNotRolledBack() {
        AtomicInteger pulse = new AtomicInteger();
        var report = WorldToolExecutor.executePlan("storynpcs:t1", List.of(
                new PlannedLeg("signal.pulse", false,
                        () -> { pulse.incrementAndGet(); return null; }),
                new PlannedLeg("boom", true, () -> { throw new IllegalStateException("nope"); })));
        assertThat(report.legs()).extracting(WorldToolExecutor.LegResult::status)
                .containsExactly(LegStatus.LOGGED, LegStatus.FAILED);
    }

    @Test
    void nullAndMissingUndoAreTolerated() {
        var report = WorldToolExecutor.executePlan("storynpcs:t1", List.of(
                new PlannedLeg("a", true, null),
                new PlannedLeg("b", true, () -> null),
                new PlannedLeg("boom", true, () -> { throw new RuntimeException("x"); })));
        // 'a' and 'b' applied without undo payloads — rolled-back status is honest.
        assertThat(report.legs()).extracting(WorldToolExecutor.LegResult::status)
                .containsExactly(LegStatus.ROLLED_BACK, LegStatus.ROLLED_BACK, LegStatus.FAILED);
    }

    // ── YAML family load ────────────────────────────────────────────────────

    @Test
    void worldToolFamilyLoadsFromYaml() {
        var registry = new com.storynpcs.yaml.DefinitionRegistry();
        var loader = new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        var result = com.storynpcs.domain.common.ValidationResult.valid();
        String yaml = """
                id: "storynpcs:gate_console"
                family: "scripted_block"
                blockId: "minecraft:oak_planks"
                dimensionId: "minecraft:overworld"
                maxBlocksPerActivation: 32
                hooks:
                  - hookId: "storynpcs:scripts/open_gate"
                    event: "interact"
                    parameters:
                      door: "north"
                """;
        var tool = loader.loadWorldTool(yaml, "worldtools/gate_console.yaml", result);

        assertThat(tool).isNotNull();
        assertThat(result.hasErrors()).isFalse();
        assertThat(tool.getFamily()).isEqualTo(WorldToolDefinition.Family.SCRIPTED_BLOCK);
        assertThat(tool.getHooks()).hasSize(1);
        assertThat(tool.getHooks().get(0).getParameters()).containsEntry("door", "north");
        assertThat(registry.getWorldTool(NamespacedId.of("storynpcs:gate_console"))).isPresent();
    }

    @Test
    void blockFamilyWithoutBlockIdFailsClosed() {
        var registry = new com.storynpcs.yaml.DefinitionRegistry();
        var loader = new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        var result = com.storynpcs.domain.common.ValidationResult.valid();
        String yaml = """
                id: "storynpcs:bad"
                family: "redstone"
                """;
        assertThat(loader.loadWorldTool(yaml, "worldtools/bad.yaml", result)).isNull();
        assertThat(result.getDiagnostics())
                .anyMatch(d -> d.code().equals("WORLDTOOL_MISSING_BLOCK"));
        assertThat(registry.getWorldTool(NamespacedId.of("storynpcs:bad"))).isEmpty();
    }

    @Test
    void missingFamilyFailsClosed() {
        var registry = new com.storynpcs.yaml.DefinitionRegistry();
        var loader = new com.storynpcs.yaml.YamlDefinitionLoader(registry);
        var result = com.storynpcs.domain.common.ValidationResult.valid();
        String yaml = """
                id: "storynpcs:bad2"
                """;
        assertThat(loader.loadWorldTool(yaml, "worldtools/bad2.yaml", result)).isNull();
        assertThat(result.getDiagnostics())
                .anyMatch(d -> d.code().equals("WORLDTOOL_MISSING_FAMILY"));
    }

    // ── session ledger: scene activations bounded + unload cleanup ──────────

    @Test
    void sceneActivationsAreBoundedAndEvictOnClear() {
        var sessions = new com.storynpcs.runtime.session.RuntimeSessionRegistry();
        for (int i = 0; i < com.storynpcs.runtime.session.RuntimeSessionRegistry.MAX_SCENE_ACTIVATIONS; i++) {
            assertThat(sessions.registerSceneActivation("tool:" + i, i)).isTrue();
        }
        assertThat(sessions.registerSceneActivation("tool:overflow", 1)).isFalse();
        assertThat(sessions.sceneActivationCount())
                .isEqualTo(com.storynpcs.runtime.session.RuntimeSessionRegistry.MAX_SCENE_ACTIVATIONS);
        assertThat(sessions.unregisterSceneActivation("tool:0", 0)).isTrue();
        assertThat(sessions.registerSceneActivation("tool:overflow", 1)).isTrue();
        sessions.clearAll();
        assertThat(sessions.sceneActivationCount()).isZero();
    }

    @Test
    void posConfirmationsAreConsumeOnceFailClosed() {
        var sessions = new com.storynpcs.runtime.session.RuntimeSessionRegistry();
        var player = java.util.UUID.randomUUID();
        long now = System.currentTimeMillis();
        sessions.armPosConfirmation(player, "worldtool:t", 12345L, 10_000);
        // Wrong pos spends the arm — a stray second click can't confirm.
        assertThat(sessions.consumePosConfirmation(player, "worldtool:t", 999L, now + 1)).isNull();
        assertThat(sessions.consumePosConfirmation(player, "worldtool:t", 12345L, now + 1)).isNull();
        sessions.armPosConfirmation(player, "worldtool:t", 12345L, 10_000);
        var confirmed = sessions.consumePosConfirmation(player, "worldtool:t", 12345L, now + 1);
        assertThat(confirmed).isNotNull();
        assertThat(confirmed.targetPos()).isEqualTo(12345L);
        // Expired arms cannot execute.
        sessions.armPosConfirmation(player, "worldtool:t", 7L, 10_000);
        assertThat(sessions.consumePosConfirmation(player, "worldtool:t", 7L, now + 20_000)).isNull();
    }
}
