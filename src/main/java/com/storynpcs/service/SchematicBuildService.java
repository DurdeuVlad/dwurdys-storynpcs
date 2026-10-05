package com.storynpcs.service;

import com.storynpcs.admin.RuntimeTunablesView;
import com.storynpcs.admin.RuntimeTunables;
import com.storynpcs.domain.schematic.BuildPlan;
import com.storynpcs.domain.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded schematic build executor (issue #149): placements are staged as a
 * {@link BuildPlan} and applied a bounded number of cells per server tick —
 * never a synchronous bulk write — so a large structure cannot freeze a tick
 * or chunk-load outside the build area. The budget is <em>global</em>: it is
 * shared across every active build per tick, and every processed cell counts
 * against it (skipped and unresolvable cells included), so pathological plans
 * cannot burn unbounded time in one tick.
 *
 * <p>Chunk safety: positions whose chunk is not loaded are skipped and
 * counted, never force-loaded. One service instance per mod lifecycle; builds
 * are capped at {@link #MAX_ACTIVE_BUILDS}. Block-state resolution goes through
 * vanilla's {@code BlockStateParser}; unresolvable palette entries are counted
 * once each and skipped. Block-entity payloads apply at completion only when
 * the placed block entity's type matches the recorded id.
 *
 * <p>World placement is a multi-tick world operation, so builds intentionally
 * run on this dedicated bounded service rather than the definition-mutation
 * path in {@link StoryNpcsApplicationService}; definitions remain untouched.
 */
public final class SchematicBuildService {

    public static final int MAX_ACTIVE_BUILDS = 4;
    private static final int MAX_RECENT_RESULTS = 8;

    private final RuntimeTunablesView tunables;
    private final List<BuildTask> active = new ArrayList<>();
    /** Ring buffer of recently completed builds, surfaced by `schema info`. */
    private final ArrayDeque<BuildResult> recentResults = new ArrayDeque<>();

    /** Progress view for `schema info`. */
    public record BuildStatus(String name, String dimension, int placed, int total,
                              int skippedUnloaded, int unresolved) {}

    /** Terminal tally retained after a build drains. */
    public record BuildResult(String name, String dimension, int placed, int total,
                              int skippedUnloaded, int unresolved,
                              int blockEntitiesApplied) {}

    /** Legacy MCEdit TileEntities id → modern block-entity registry key. */
    private static final Map<String, String> LEGACY_BE_IDS = Map.ofEntries(
            Map.entry("Chest", "minecraft:chest"),
            Map.entry("TrappedChest", "minecraft:trapped_chest"),
            Map.entry("EnderChest", "minecraft:ender_chest"),
            Map.entry("Furnace", "minecraft:furnace"),
            Map.entry("Sign", "minecraft:sign"),
            Map.entry("Banner", "minecraft:banner"),
            Map.entry("Skull", "minecraft:skull"),
            Map.entry("Beacon", "minecraft:beacon"),
            Map.entry("Dispenser", "minecraft:dispenser"),
            Map.entry("Dropper", "minecraft:dropper"),
            Map.entry("Hopper", "minecraft:hopper"),
            Map.entry("Jukebox", "minecraft:jukebox"),
            Map.entry("NoteBlock", "minecraft:note_block"),
            Map.entry("EnchantTable", "minecraft:enchanting_table"),
            Map.entry("BrewingStand", "minecraft:brewing_stand"),
            Map.entry("Comparator", "minecraft:comparator"),
            Map.entry("DaylightDetector", "minecraft:daylight_detector"),
            Map.entry("CommandBlock", "minecraft:command_block"),
            Map.entry("MobSpawner", "minecraft:spawner"),
            Map.entry("FlowerPot", "minecraft:flower_pot"),
            Map.entry("Bed", "minecraft:bed"),
            Map.entry("Structure", "minecraft:structure_block"));

    private SchematicBuildService(RuntimeTunablesView tunables) {
        this.tunables = tunables;
    }

    public static SchematicBuildService create(RuntimeTunablesView tunables) {
        return new SchematicBuildService(tunables);
    }

    /** Starts a build; returns a human-facing rejection or empty on success. */
    public Optional<String> startBuild(ServerLevel level, Schematic schematic,
                                       BlockPos origin, int quarterTurns) {
        if (active.size() >= MAX_ACTIVE_BUILDS) {
            return Optional.of("too many active builds (max " + MAX_ACTIVE_BUILDS + ")");
        }
        if (origin.getY() < level.getMinBuildHeight()
                || origin.getY() + schematic.height() > level.getMaxBuildHeight()) {
            return Optional.of("build does not fit vertically at y=" + origin.getY()
                    + " (needs " + schematic.height() + ")");
        }
        BuildPlan plan = BuildPlan.of(schematic, quarterTurns);
        if (plan.placements().isEmpty()) {
            return Optional.of("schematic '" + schematic.name() + "' has no placeable blocks");
        }
        active.add(new BuildTask(level, schematic.name(), plan, origin, quarterTurns));
        return Optional.empty();
    }

    /** Stops every active build in {@code level}; returns the count stopped. */
    public int stop(ServerLevel level) {
        int before = active.size();
        active.removeIf(task -> task.level == level);
        return before - active.size();
    }

    public int stopAll() {
        int count = active.size();
        active.clear();
        return count;
    }

    /** Snapshot of in-flight progress for `schema info`. */
    public List<BuildStatus> status() {
        List<BuildStatus> out = new ArrayList<>();
        for (BuildTask task : active) {
            out.add(new BuildStatus(task.name,
                    task.level.dimension().location().toString(),
                    task.placed, task.plan.placements().size(),
                    task.skippedUnloaded, task.unresolved));
        }
        return out;
    }

    /** Terminal tallies of recently completed builds, oldest first. */
    public List<BuildResult> recentResults() {
        return List.copyOf(recentResults);
    }

    /**
     * Per-tick placement driver — call once per server tick. The configured
     * budget is shared across all active builds, so total per-tick work never
     * exceeds it regardless of build count.
     */
    public void tick() {
        if (active.isEmpty()) {
            return;
        }
        long budget = Math.max(16L,
                tunables.longValue(RuntimeTunables.SCHEMATIC_BUILD_BLOCKS_PER_TICK));
        List<BuildTask> finished = new ArrayList<>();
        for (BuildTask task : active) {
            if (budget <= 0) {
                break;
            }
            budget -= task.tick((int) Math.min(Integer.MAX_VALUE, budget));
            if (task.done) {
                finished.add(task);
            }
        }
        for (BuildTask task : finished) {
            recentResults.addLast(new BuildResult(task.name,
                    task.level.dimension().location().toString(),
                    task.placed, task.plan.placements().size(),
                    task.skippedUnloaded, task.unresolved, task.blockEntitiesApplied));
            while (recentResults.size() > MAX_RECENT_RESULTS) {
                recentResults.removeFirst();
            }
        }
        active.removeAll(finished);
    }

    private static final class BuildTask {
        private final ServerLevel level;
        private final String name;
        private final BuildPlan plan;
        private final BlockPos origin;
        private final Rotation rotation;
        private int cursor;
        private int placed;
        private int skippedUnloaded;
        private int unresolved;
        private int blockEntitiesApplied;
        private int blockEntityCursor;
        private boolean done;
        /** Palette name → resolved state; Optional.empty marks unresolvable. */
        private final Map<String, Optional<BlockState>> stateCache = new LinkedHashMap<>();

        BuildTask(ServerLevel level, String name, BuildPlan plan, BlockPos origin,
                  int quarterTurns) {
            this.level = level;
            this.name = name;
            this.plan = plan;
            this.origin = origin;
            this.rotation = switch (Math.floorMod(quarterTurns, 4)) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
        }

        /** Applies up to {@code budget} cells; returns the count processed. */
        int tick(int budget) {
            var registry = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            int processed = 0;
            while (cursor < plan.placements().size() && processed < budget) {
                BuildPlan.Placement p = plan.placements().get(cursor++);
                processed++;
                BlockPos pos = origin.offset(p.dx(), p.dy(), p.dz());
                if (!level.hasChunkAt(pos)) {
                    skippedUnloaded++;
                    continue;
                }
                Optional<BlockState> state = stateCache.computeIfAbsent(p.palette(),
                        n -> Optional.ofNullable(resolveOrNull(registry, n)));
                if (state.isEmpty()) {
                    unresolved++;
                    continue;
                }
                level.setBlock(pos, state.get().rotate(rotation), 3);
                placed++;
            }
            while (cursor >= plan.placements().size()
                    && blockEntityCursor < plan.blockEntities().size() && processed < budget) {
                applyBlockEntity(plan.blockEntities().get(blockEntityCursor++));
                processed++;
            }
            done = cursor >= plan.placements().size()
                    && blockEntityCursor >= plan.blockEntities().size();
            return processed;
        }

        private BlockState resolveOrNull(
                net.minecraft.core.HolderLookup<net.minecraft.world.level.block.Block> registry,
                String name) {
            try {
                var parsed = net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(registry, name, false);
                return parsed.blockState();
            } catch (Exception e) {
                return null;
            }
        }

        /** Applies one carried block-entity payload once all placements land. */
        private void applyBlockEntity(Schematic.BlockEntityRecord record) {
            BlockPos pos = origin.offset(record.x(), record.y(), record.z());
            if (!level.hasChunkAt(pos)) {
                return;
            }
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null || !typeMatches(record.id(), blockEntity)) {
                return;
            }
            try {
                blockEntity.loadWithComponents(record.data(), level.registryAccess());
                blockEntity.setChanged();
                blockEntitiesApplied++;
            } catch (Exception ignored) {
                // A payload that doesn't match the placed block is skipped —
                // the build result still stands.
            }
        }

        /** Recorded BE id must match the placed block entity's registry type. */
        private boolean typeMatches(String recordedId, net.minecraft.world.level.block.entity.BlockEntity be) {
            String expected = recordedId.indexOf(':') >= 0 ? recordedId
                    : LEGACY_BE_IDS.getOrDefault(recordedId, "");
            if (expected.isEmpty()) {
                return false;
            }
            var actual = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            return actual != null && expected.equals(actual.toString());
        }
    }
}
