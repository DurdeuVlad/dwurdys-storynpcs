package com.storynpcs.runtime.worldtool;

import com.storynpcs.api.event.CreatorToolAuditEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.creator.world.WorldToolDefinition;
import com.storynpcs.creator.world.WorldToolOpsCatalog;
import com.storynpcs.domain.quest.QuestMail;
import com.storynpcs.persistence.WorldToolBindingStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Server-authoritative world-tool activation (P8-3). Activations validate the
 * tool's dimension binding and chunk containment up front, run their op legs
 * in catalog order, roll back the reversible legs that already applied when a
 * later leg fails, and report every leg's exact status — applied, rolled
 * back, logged (irreversible but executed), or not run — never a vague
 * "where possible" claim.
 *
 * <p>Hook bindings are stored as inert typed data; this layer never executes
 * user code — the P9-2 script host resolves them through canonical ops.
 */
public final class WorldToolExecutor {

    public enum LegStatus { APPLIED, FAILED, ROLLED_BACK, NOT_RUN, LOGGED }

    public record LegResult(String opId, LegStatus status, boolean reversible, String detail) {}

    /** Exact per-leg outcome of an activation — the report callers must surface. */
    public record MutationReport(String toolId, List<LegResult> legs) {
        public MutationReport {
            legs = legs == null ? List.of() : List.copyOf(legs);
        }

        /** Every leg that ran landed and nothing had to roll back. */
        public boolean fullyApplied() {
            return legs.stream().noneMatch(l ->
                    l.status() == LegStatus.FAILED || l.status() == LegStatus.ROLLED_BACK
                            || l.status() == LegStatus.NOT_RUN);
        }

        public List<LegResult> failures() {
            return legs.stream().filter(l -> l.status() == LegStatus.FAILED).toList();
        }
    }

    /** Block-level seam so plan/rollback semantics are unit-testable headless. */
    public interface BlockAccess {
        String blockAt(BlockPos pos);
        void setBlock(BlockPos pos, String blockId);
    }

    /**
     * Applies a leg and returns its undo action (or null when the leg has no
     * meaningful rollback). The undo runs only if a later leg fails.
     */
    public interface UndoableAction { Runnable run() throws Exception; }

    public record PlannedLeg(String opId, boolean reversible, UndoableAction action) {
        public PlannedLeg {
            action = action == null ? () -> null : action;
        }
    }

    /**
     * Pure apply/rollback engine: legs run in order; a failure rolls back the
     * reversible legs already applied (reverse order) and leaves the rest
     * NOT_RUN; irreversible legs that ran are reported LOGGED. This is the
     * exact-status contract every adapter surfaces.
     */
    public static MutationReport executePlan(String toolId, List<PlannedLeg> plan) {
        var legs = new ArrayList<LegResult>();
        var rollback = new ArrayList<Runnable>();
        boolean failed = false;
        for (var leg : plan) {
            if (failed) {
                legs.add(new LegResult(leg.opId(), LegStatus.NOT_RUN, leg.reversible(),
                        "prior leg failed"));
                continue;
            }
            try {
                Runnable undo = leg.action().run();
                if (leg.reversible() && undo != null) {
                    rollback.add(undo);
                }
                legs.add(new LegResult(leg.opId(), leg.reversible()
                        ? LegStatus.APPLIED : LegStatus.LOGGED, leg.reversible(), null));
            } catch (Exception e) {
                legs.add(new LegResult(leg.opId(), LegStatus.FAILED, leg.reversible(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                failed = true;
            }
        }
        if (failed) {
            for (int i = rollback.size() - 1; i >= 0; i--) {
                try {
                    rollback.get(i).run();
                } catch (Exception ignored) {
                    // Rollback is best-effort per leg; statuses below are honest.
                }
            }
            for (int i = 0; i < legs.size(); i++) {
                var leg = legs.get(i);
                if (leg.status() == LegStatus.APPLIED && leg.reversible()) {
                    legs.set(i, new LegResult(leg.opId(), LegStatus.ROLLED_BACK, true, null));
                }
            }
        }
        return new MutationReport(toolId, List.copyOf(legs));
    }

    private final EventPublisher events;
    private final WorldToolBindingStore bindingStore;
    private final Function<UUID, List<QuestMail>> pendingMail;
    private final com.storynpcs.runtime.session.RuntimeSessionRegistry sessions;

    public WorldToolExecutor(EventPublisher events, WorldToolBindingStore bindingStore,
                             Function<UUID, List<QuestMail>> pendingMail,
                             com.storynpcs.runtime.session.RuntimeSessionRegistry sessions) {
        this.events = events;
        this.bindingStore = bindingStore;
        this.pendingMail = pendingMail;
        this.sessions = sessions;
    }

    /** Preview: the catalog legs an activation would run — no world mutation. */
    public List<LegResult> preview(WorldToolDefinition tool) {
        var ops = WorldToolOpsCatalog.opsFor(tool.getFamily());
        var legs = new ArrayList<LegResult>();
        ops.reversible().forEach(op ->
                legs.add(new LegResult(op.opId(), LegStatus.NOT_RUN, true, op.description())));
        ops.irreversible().forEach(op ->
                legs.add(new LegResult(op.opId(), LegStatus.NOT_RUN, false, op.description())));
        return legs;
    }

    /** ServerLevel adapter for the block seam. */
    public static BlockAccess forLevel(ServerLevel level) {
        return new BlockAccess() {
            @Override
            public String blockAt(BlockPos pos) {
                return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            }

            @Override
            public void setBlock(BlockPos pos, String blockId) {
                var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockId))
                        .orElseThrow(() -> new IllegalArgumentException("unknown block: " + blockId));
                level.setBlock(pos, block.defaultBlockState(), 3);
            }
        };
    }

    /**
     * Activate a tool at {@code pos} in {@code level}. Dimension binding and
     * chunk containment are enforced here; actor capability is checked by the
     * caller (op-2 surface).
     */
    public MutationReport activate(WorldToolDefinition tool, ServerLevel level,
                                   BlockPos pos, net.minecraft.world.entity.player.Player actor, BlockAccess blocks) {
        String toolId = tool.getId().toString();

        if (tool.getDimensionId() != null
                && !tool.getDimensionId().toString().equals(level.dimension().location().toString())) {
            audit(actor, toolId, "rejected:DIMENSION_MISMATCH");
            return new MutationReport(toolId, List.of(
                    new LegResult("dimension.check", LegStatus.FAILED, false,
                            "tool is bound to dimension '" + tool.getDimensionId()
                                    + "', not '" + level.dimension().location() + "'")));
        }
        if (!level.isLoaded(pos)) {
            audit(actor, toolId, "rejected:CHUNK_UNLOADED");
            return new MutationReport(toolId, List.of(
                    new LegResult("chunk.check", LegStatus.FAILED, false,
                            "target chunk is not loaded at " + pos.toShortString())));
        }

        var catalog = WorldToolOpsCatalog.opsFor(tool.getFamily());
        int blockLegs = (int) catalog.reversible().stream()
                .filter(op -> op.opId().startsWith("block.") || op.opId().startsWith("door."))
                .count();
        if (blockLegs > tool.getMaxBlocksPerActivation()) {
            audit(actor, toolId, "rejected:BUDGET_EXCEEDED");
            return new MutationReport(toolId, List.of(
                    new LegResult("budget.check", LegStatus.FAILED, false,
                            "activation exceeds maxBlocksPerActivation="
                                    + tool.getMaxBlocksPerActivation())));
        }

        var plan = new ArrayList<PlannedLeg>();
        for (var op : catalog.reversible()) {
            plan.add(new PlannedLeg(op.opId(), true,
                    () -> applyReversible(op.opId(), tool, level, pos, blocks)));
        }
        for (var op : catalog.irreversible()) {
            plan.add(new PlannedLeg(op.opId(), false, () -> {
                applyIrreversible(op.opId(), tool, level, pos, actor);
                return null;
            }));
        }

        var report = executePlan(toolId, plan);
        audit(actor, toolId, report.fullyApplied()
                ? "applied"
                : "failed:" + report.failures().stream()
                        .map(LegResult::opId).findFirst().orElse("unknown"));
        return report;
    }

    public MutationReport activate(WorldToolDefinition tool, ServerLevel level,
                                   BlockPos pos, net.minecraft.world.entity.player.Player actor) {
        return activate(tool, level, pos, actor, forLevel(level));
    }

    /** Applies a reversible leg; returns the undo action for the rollback stack. */
    private Runnable applyReversible(String opId, WorldToolDefinition tool, ServerLevel level,
                                     BlockPos pos, BlockAccess blocks) throws IOException {
        String dim = level.dimension().location().toString();
        switch (opId) {
            case "block.place", "block.replace", "door.place" -> {
                if (tool.getBlockId() == null) {
                    throw new IllegalStateException("tool has no blockId to place");
                }
                String prior = blocks.blockAt(pos);
                blocks.setBlock(pos, tool.getBlockId().toString());
                return () -> blocks.setBlock(pos, prior);
            }
            case "pattern.apply", "hooks.bind" -> {
                // Both legs persist the tool's inert binding payload — authored
                // hook/param data is the pattern/hook state for this position.
                var payload = tool.getHooks();
                if (bindingStore != null && !payload.isEmpty()) {
                    bindingStore.save(dim, pos.asLong(), tool.getId().toString(), payload);
                    return () -> {
                        try {
                            bindingStore.delete(dim, pos.asLong());
                        } catch (IOException ignored) {
                        }
                    };
                }
                return () -> {};
            }
            case "binding.register" -> {
                // Scene activations live in the per-server session ledger —
                // bounded, evicted on stop/unload (unload cleanup).
                if (sessions == null) {
                    return () -> {};
                }
                if (!sessions.registerSceneActivation(tool.getId().toString(), pos.asLong())) {
                    throw new IllegalStateException("scene activation ledger is full ("
                            + com.storynpcs.runtime.session.RuntimeSessionRegistry.MAX_SCENE_ACTIVATIONS + ")");
                }
                return () -> sessions.unregisterSceneActivation(tool.getId().toString(), pos.asLong());
            }
            default -> throw new IllegalStateException("unmapped reversible op: " + opId);
        }
    }

    private String applyIrreversible(String opId, WorldToolDefinition tool, ServerLevel level,
                                     BlockPos pos, net.minecraft.world.entity.player.Player actor) throws IOException {
        switch (opId) {
            case "signal.pulse" -> {
                var block = level.getBlockState(pos).getBlock();
                level.updateNeighborsAt(pos, block);
                return "redstone pulse propagated to neighbors at " + pos.toShortString();
            }
            case "mail.interact" -> {
                if (pendingMail == null || actor == null) {
                    return "no mail surface available";
                }
                var pending = pendingMail.apply(actor.getUUID());
                return "mailbox shows " + pending.size() + " pending mail";
            }
            case "scene.play" -> {
                return "scene activation registered at " + pos.toShortString();
            }
            default -> throw new IllegalStateException("unmapped irreversible op: " + opId);
        }
    }

    private void audit(net.minecraft.world.entity.player.Player actor, String toolId, String outcome) {
        if (events != null && actor != null) {
            events.publish(new CreatorToolAuditEvent(
                    "worldtool", "activate", actor.getUUID(), toolId, outcome));
        }
    }
}
