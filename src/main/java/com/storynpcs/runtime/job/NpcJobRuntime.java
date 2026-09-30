package com.storynpcs.runtime.job;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.job.JobInstance;
import com.storynpcs.domain.job.JobType;
import com.storynpcs.entity.StoryNpcEntity;

/**
 * Bounded per-tick job handlers (P6-4). The entity's tick calls {@link #run}
 * only when the {@link JobInstance}'s own {@code tickPeriod} says the handler
 * is due; every handler mutates the world on the server thread with explicit
 * per-cycle bounds — no job can spawn unbounded entities, chunks, or tasks.
 */
public final class NpcJobRuntime {

    /** FARMER: max crop-bonemeal applications per cycle. */
    private static final int FARMER_MAX_APPLY_PER_CYCLE = 4;
    /**
     * GUARD: threat written for a sight-acquired hostile — the same magnitude
     * {@code NpcAttackOnSightGoal} uses so engagement and decay behave
     * identically regardless of which system acquired the target.
     */
    private static final int GUARD_SIGHT_THREAT = 200;
    private static final int SPAWNER_MAX_OFFSET_TRIES = 8;

    private NpcJobRuntime() {}

    /**
     * Runs one due job cycle for the entity. Unsupported job types fail closed:
     * they log once per entity and perform no mutations — a configured but
     * unsupported job is never silently mistaken for a working one.
     */
    public static void run(StoryNpcEntity npc, JobInstance job) {
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive()) {
            return;
        }
        var config = job.getConfig();
        switch (config.getType()) {
            case HEALER -> runHealer(npc, level, config.getEffectRadiusBlocks());
            case BARD -> runBard(npc, level, config.getEffectRadiusBlocks());
            case GUARD -> runGuard(npc, level, config.getEffectRadiusBlocks());
            case FARMER -> runFarmer(npc, level, config.getWorkRadiusBlocks());
            case CHUNK_LOADER -> runChunkLoader(npc, level, config.getChunkRadius());
            case SPAWNER -> runSpawner(npc, level, config);
            case CONVERSATION, PUPPET ->
                    npc.dispatchScriptHook(com.storynpcs.script.ScriptHook.TICK);
            case ITEM_GIVER -> { /* interact-driven — handled in mobInteract */ }
            case FOLLOWER, BUILDER -> logUnsupportedOnce(npc, config.getType());
            default -> logUnsupportedOnce(npc, config.getType());
        }
    }

    /** HEALER job: small periodic heal to players inside the effect radius. */
    private static void runHealer(StoryNpcEntity npc, ServerLevel level, double radius) {
        double r2 = radius * radius;
        var box = npc.getBoundingBox().inflate(radius);
        for (var player : level.getEntitiesOfClass(Player.class, box,
                p -> p.isAlive() && p.distanceToSqr(npc) <= r2
                        && p.getHealth() < p.getMaxHealth())) {
            player.heal(1.0f);
        }
    }

    /** BARD job: brief regeneration pulse for players inside the effect radius. */
    private static void runBard(StoryNpcEntity npc, ServerLevel level, double radius) {
        double r2 = radius * radius;
        var box = npc.getBoundingBox().inflate(radius);
        for (var player : level.getEntitiesOfClass(Player.class, box,
                p -> p.isAlive() && p.distanceToSqr(npc) <= r2)) {
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0, true, true));
        }
    }

    /**
     * GUARD job: acquire the nearest hostile mob in range when the NPC is not
     * already fighting — engagement itself runs through the normal melee goal.
     * Acquisition feeds the canonical threat table ({@link
     * com.storynpcs.ai.combat.ThreatManager#addThreat}); writing only the
     * vanilla {@code Mob.target} field would be a dead write — no goal consumes
     * it.
     */
    private static void runGuard(StoryNpcEntity npc, ServerLevel level, double radius) {
        if (npc.getThreatManager().getCurrentTarget().isPresent()) {
            return;
        }
        double r2 = radius * radius;
        Monster nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (var monster : level.getEntitiesOfClass(Monster.class,
                npc.getBoundingBox().inflate(radius),
                m -> m.isAlive() && m.distanceToSqr(npc) <= r2)) {
            double d = monster.distanceToSqr(npc);
            if (d < nearestDist) {
                nearest = monster;
                nearestDist = d;
            }
        }
        if (nearest != null) {
            npc.getThreatManager().addThreat(nearest.getUUID(), GUARD_SIGHT_THREAT);
        }
    }

    /** FARMER job: bounded bonemeal application to crops in the work radius. */
    private static void runFarmer(StoryNpcEntity npc, ServerLevel level, double radius) {
        int bound = (int) Math.ceil(Math.min(radius, 16.0));
        var base = npc.blockPosition();
        int applied = 0;
        outer:
        for (int dx = -bound; dx <= bound; dx++) {
            for (int dz = -bound; dz <= bound; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    var pos = base.offset(dx, dy, dz);
                    var state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof CropBlock crop) || crop.isMaxAge(state)) {
                        continue;
                    }
                    if (state.getBlock() instanceof BonemealableBlock bonemealable
                            && bonemealable.isValidBonemealTarget(level, pos, state)) {
                        bonemealable.performBonemeal(level, level.random, pos, state);
                        if (++applied >= FARMER_MAX_APPLY_PER_CYCLE) {
                            break outer;
                        }
                    }
                }
            }
        }
    }

    /**
     * CHUNK_LOADER: furthest a loader's wanted set can reach, in chunks — the
     * same value {@link #runChunkLoader} clamps {@code chunkRadius} to.
     */
    private static final int CHUNK_LOADER_MAX_RADIUS = 3;

    /**
     * CHUNK_LOADER job: keep chunks in a bounded radius force-loaded.
     * Forcing is re-asserted every cycle (bounded — at most (2*MAX+1)^2 calls)
     * so a shared chunk released by a departed or moved loader is healed on the
     * survivor's next cycle rather than left permanently un-forced.
     */
    private static void runChunkLoader(StoryNpcEntity npc, ServerLevel level, int chunkRadius) {
        int radius = Math.max(0, Math.min(chunkRadius, CHUNK_LOADER_MAX_RADIUS));
        var center = npc.chunkPosition();
        var wanted = new java.util.HashSet<ChunkPos>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                wanted.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        var forced = npc.jobForcedChunks();
        for (var pos : wanted) {
            forced.add(pos);
            level.setChunkForced(pos.x, pos.z, true);
        }
        var it = forced.iterator();
        while (it.hasNext()) {
            var pos = it.next();
            if (!wanted.contains(pos)) {
                releaseForcedChunk(npc, level, pos);
                it.remove();
            }
        }
    }

    /**
     * Un-forces a chunk only when no other loaded loader still claims it.
     * Another loader can only want this chunk from inside its own clamped
     * radius, so a scan spanning {@code MAX_RADIUS + 1} chunks around the
     * position covers every possible claimant — the check is bounded, not a
     * world-wide sweep.
     */
    public static void releaseForcedChunk(StoryNpcEntity owner, ServerLevel level, ChunkPos pos) {
        double scanBlocks = (CHUNK_LOADER_MAX_RADIUS + 2.0) * 16.0;
        var scanBox = new AABB(pos.getMiddleBlockPosition((int) owner.getY())).inflate(scanBlocks);
        for (var other : level.getEntitiesOfClass(StoryNpcEntity.class, scanBox,
                e -> e != owner && e.jobForcedChunks().contains(pos))) {
            return;
        }
        level.setChunkForced(pos.x, pos.z, false);
    }

    /**
     * SPAWNER job: spawn up to {@code spawnCount} NPC entities per cycle, never
     * exceeding {@code maxSpawnedAlive} — ownership is stamped into each
     * spawned entity's persistent data so the bound survives reloads.
     */
    private static void runSpawner(StoryNpcEntity npc, ServerLevel level,
                                   com.storynpcs.domain.job.JobConfig config) {
        if (config.getSpawnDefinitionId() == null) {
            return;
        }
        var mod = StoryNpcsAccess.mod(level);
        if (mod == null) {
            return;
        }
        var defOpt = mod.getRegistry().getNpc(config.getSpawnDefinitionId());
        if (defOpt.isEmpty()) {
            return;
        }
        double radius = Math.max(2.0, Math.min(config.getWorkRadiusBlocks(), 32.0));
        var box = npc.getBoundingBox().inflate(radius * 2);
        int alive = 0;
        for (var e : level.getEntitiesOfClass(StoryNpcEntity.class, box, Entity::isAlive)) {
            if (npc.getUUID().equals(e.getPersistentData().getUUID("JobSpawner"))) {
                alive++;
            }
        }
        int toSpawn = Math.min(Math.max(1, config.getSpawnCount()),
                Math.max(0, config.getMaxSpawnedAlive() - alive));
        for (int i = 0; i < toSpawn; i++) {
            BlockPos pos = null;
            for (int attempt = 0; attempt < SPAWNER_MAX_OFFSET_TRIES; attempt++) {
                var candidate = npc.blockPosition().offset(
                        level.random.nextInt((int) radius * 2 + 1) - (int) radius,
                        0,
                        level.random.nextInt((int) radius * 2 + 1) - (int) radius);
                var ground = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        candidate);
                if (level.getWorldBorder().isWithinBounds(ground)
                        && !level.getBlockState(ground).isSolidRender(level, ground)) {
                    pos = ground;
                    break;
                }
            }
            if (pos == null) {
                break;
            }
            var spawned = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
            if (spawned == null) {
                break;
            }
            spawned.setDefinitionId(config.getSpawnDefinitionId().toString());
            spawned.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                    level.random.nextFloat() * 360.0f, 0.0f);
            spawned.getPersistentData().putUUID("JobSpawner", npc.getUUID());
            level.addFreshEntity(spawned);
        }
    }

    private static void logUnsupportedOnce(StoryNpcEntity npc, JobType type) {
        if (npc.markUnsupportedJobLogged(type)) {
            StoryNpcs.LOGGER.info(
                    "Job type {} on actor {} has no implemented handler — configured job is inert",
                    type, npc.getUUID());
        }
    }
}
