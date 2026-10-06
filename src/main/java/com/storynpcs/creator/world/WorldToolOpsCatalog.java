package com.storynpcs.creator.world;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit reversible / non-reversible operation list per world-tool family
 * (P8-3). The catalog is the contract a failed activation reports against:
 * every leg names whether it can be undone, and the executor reports each
 * leg's applied/rolled-back/not-run status rather than a vague "where
 * possible" claim.
 */
public final class WorldToolOpsCatalog {

    private WorldToolOpsCatalog() {}

    public record FamilyOps(
            List<WorldToolDefinition.ReversibleOp> reversible,
            List<WorldToolDefinition.IrreversibleOp> irreversible) {
        public FamilyOps {
            reversible = reversible == null ? List.of() : List.copyOf(reversible);
            irreversible = irreversible == null ? List.of() : List.copyOf(irreversible);
        }
    }

    private static final Map<WorldToolDefinition.Family, FamilyOps> CATALOG;

    static {
        Map<WorldToolDefinition.Family, FamilyOps> ops =
                new EnumMap<>(WorldToolDefinition.Family.class);
        ops.put(WorldToolDefinition.Family.SCRIPTED_BLOCK, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("block.place",
                                "Place the scripted block, restoring the prior block on rollback"),
                        new WorldToolDefinition.ReversibleOp("block.replace",
                                "Replace the target block, restoring the prior block on rollback"),
                        new WorldToolDefinition.ReversibleOp("hooks.bind",
                                "Attach inert typed hook bindings, removing them on rollback")),
                List.of()));
        ops.put(WorldToolDefinition.Family.SCRIPTED_DOOR, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("door.place",
                                "Place the scripted door block, restoring the prior block on rollback"),
                        new WorldToolDefinition.ReversibleOp("hooks.bind",
                                "Attach inert typed hook bindings, removing them on rollback")),
                List.of()));
        ops.put(WorldToolDefinition.Family.REDSTONE, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("block.place",
                                "Place the emitter block, restoring the prior block on rollback")),
                List.of(
                        new WorldToolDefinition.IrreversibleOp("signal.pulse",
                                "Redstone pulses propagate to neighbors and cannot be recalled — they are logged, not rolled back"))));
        ops.put(WorldToolDefinition.Family.BANNER, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("block.place",
                                "Place the banner block, restoring the prior block on rollback"),
                        new WorldToolDefinition.ReversibleOp("pattern.apply",
                                "Write banner pattern state, restoring the prior state on rollback")),
                List.of()));
        ops.put(WorldToolDefinition.Family.MAILBOX, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("block.place",
                                "Place the mailbox block, restoring the prior block on rollback")),
                List.of(
                        new WorldToolDefinition.IrreversibleOp("mail.interact",
                                "Mail deliveries/views through the P5-5 mail path are business transactions — they are journaled, not rolled back"))));
        ops.put(WorldToolDefinition.Family.SCENE, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("binding.register",
                                "Register the bounded scene activation, evicted on unload")),
                List.of(
                        new WorldToolDefinition.IrreversibleOp("scene.play",
                                "Scene playback mutates actors over time (P8-5) — the registration is reversible, the performance is not"))));
        ops.put(WorldToolDefinition.Family.SCRIPTER, new FamilyOps(
                List.of(
                        new WorldToolDefinition.ReversibleOp("hooks.bind",
                                "Attach inert typed hook bindings, removing them on rollback")),
                List.of()));
        CATALOG = Map.copyOf(ops);
    }

    /** Ops contract for a family — always present for every enum value. */
    public static FamilyOps opsFor(WorldToolDefinition.Family family) {
        var ops = CATALOG.get(family);
        if (ops == null) {
            throw new IllegalStateException("world-tool family has no ops contract: " + family);
        }
        return ops;
    }
}
