package com.storynpcs.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * High-reliability ground path navigator for StoryNPCs that eliminates wedging, corner-sticking,
 * and positional stagnation via generous waypoint thresholds, clearance validation, and progressive unstick recovery.
 */
public class StoryNpcPathNavigator extends GroundPathNavigation {

    private final NpcStuckDetector stuckDetector = new NpcStuckDetector(20, 0.04);
    private int unstuckAttemptCooldown = 0;

    /**
     * P4-2: an outstanding queued path request, if any. While set, the
     * navigator reports itself in-progress so goals do not re-issue
     * overlapping requests. Execution payload — coordinates snapshot plus
     * speed — lives here; the scheduler queue only orders request ids.
     */
    private record PendingPath(java.util.UUID requestId, double x, double y, double z, double speed) {}

    private PendingPath pendingPath;
    private long navRevision;

    public StoryNpcPathNavigator(Mob mob, Level level) {
        super(mob, level);
    }

    /**
     * Enqueue the path computation on the mod's bounded scheduler instead of
     * computing synchronously (P4-2). Returns {@code true} when the request
     * was accepted; on {@code QUEUE_FULL} the request is dropped explicitly
     * (counted in {@code droppedRequests}) and the issuing goal re-issues on
     * its own repath throttle — degraded, never an unbounded synchronous
     * compute. Client-side calls compute synchronously (no queue).
     */
    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        if (shouldBypassQueue()) {
            return super.moveTo(x, y, z, speed);
        }
        return enqueue(x, y, z, speed);
    }

    @Override
    public boolean moveTo(net.minecraft.world.entity.Entity entity, double speed) {
        if (shouldBypassQueue()) {
            return super.moveTo(entity, speed);
        }
        // Snapshot the entity's current position — the queue contract carries
        // immutable targets, never live world references.
        return enqueue(entity.getX(), entity.getY(), entity.getZ(), speed);
    }

    private boolean shouldBypassQueue() {
        if (this.level.isClientSide
                || !(this.mob instanceof com.storynpcs.entity.StoryNpcEntity npc)) {
            return true;
        }
        var mod = com.storynpcs.StoryNpcsAccess.mod(npc);
        return mod == null || mod.getPathScheduler() == null;
    }

    /**
     * @return {@code true} when the request was queued (the deferred compute
     *         runs on the server-tick drain); {@code false} when the bounded
     *         queue rejected it — callers treat both as "handled" (no
     *         synchronous fallback), the goal's repath throttle retries.
     */
    private boolean enqueue(double x, double y, double z, double speed) {
        var npc = (com.storynpcs.entity.StoryNpcEntity) this.mob;
        var scheduler = com.storynpcs.StoryNpcsAccess.mod(npc).getPathScheduler();
        var requestId = java.util.UUID.randomUUID();
        var pending = new PendingPath(requestId, x, y, z, speed);
        // A newer moveTo supersedes: drop the actor's still-queued requests so
        // repath bursts cannot accumulate zombies toward QUEUE_FULL.
        scheduler.cancelActor(npc.getUUID());
        // Combat actors jump ambient traffic — deterministic priority order.
        int priority = npc.getThreatManager().getCurrentTarget().isPresent() ? 10 : 0;
        // Deterministic pseudo-key for the level so PathTarget stays honest;
        // the monotonic revision lets cancelStale drop superseded generations.
        var levelKey = java.util.UUID.nameUUIDFromBytes(
                this.level.dimension().location().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var outcome = scheduler.submit(new com.storynpcs.sim.PathScheduler.PathRequest(
                requestId, npc.getUUID(),
                new com.storynpcs.sim.PathScheduler.PathTarget(
                        net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(y),
                        net.minecraft.util.Mth.floor(z), levelKey),
                priority, ++this.navRevision, this.level.getGameTime()));
        if (outcome == com.storynpcs.sim.PathScheduler.SubmitOutcome.QUEUE_FULL) {
            // The superseded request was already purged by cancelActor — its
            // pending marker would otherwise pin isDone() false forever.
            this.pendingPath = null;
            return false; // explicit degrade — the bound is real
        }
        this.pendingPath = pending;
        return true;
    }

    /**
     * Server-tick drain entry: executes the queued request if it is still the
     * navigator's live pending entry (a newer submission supersedes; a
     * cancelled/removed actor's request resolves to nothing).
     */
    public boolean executePending(java.util.UUID requestId) {
        var pending = this.pendingPath;
        if (pending == null || !pending.requestId().equals(requestId)) {
            return false; // superseded or cancelled — drop explicitly
        }
        this.pendingPath = null;
        return super.moveTo(pending.x(), pending.y(), pending.z(), pending.speed());
    }

    @Override
    public void tick() {
        // A queued request reports not-done but holds no live Path — vanilla's
        // tick tail would dereference path.getNextEntityPos unconditionally.
        // Skip the tick while the pending window covers an absent/finished
        // path; a still-live in-flight path keeps ticking normally until the
        // drain swaps it.
        if (this.pendingPath != null && (this.path == null || this.path.isDone())) {
            return;
        }
        super.tick();
    }

    @Override
    public boolean isDone() {
        // A queued request is in-progress navigation: report not-done so
        // goals keep their repath throttle instead of re-issuing.
        return this.pendingPath == null && super.isDone();
    }

    @Override
    public boolean isInProgress() {
        return this.pendingPath != null || super.isInProgress();
    }

    @Override
    public void stop() {
        if (this.pendingPath != null) {
            this.pendingPath = null;
            if (this.mob instanceof com.storynpcs.entity.StoryNpcEntity npc) {
                var mod = com.storynpcs.StoryNpcsAccess.mod(npc);
                if (mod != null) {
                    mod.getPathScheduler().cancelActor(npc.getUUID());
                }
            }
        }
        super.stop();
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new StoryNpcNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    public NpcStuckDetector getStuckDetector() {
        return stuckDetector;
    }

    @Override
    protected void followThePath() {
        if (this.path == null || this.path.isDone()) {
            stuckDetector.reset();
            return;
        }

        Vec3 mobPos = this.getTempMobPos();
        int nodeCount = this.path.getNodeCount();
        int currentIndex = this.path.getNextNodeIndex();

        // 1. Waypoint Advancement with generous radius
        Vec3i currentPos = this.path.getNextNodePos();
        double dx = Math.abs(this.mob.getX() - (currentPos.getX() + 0.5));
        double dz = Math.abs(this.mob.getZ() - (currentPos.getZ() + 0.5));
        double dy = Math.abs(this.mob.getY() - currentPos.getY());

        double advanceRadius = Math.max(0.85D, (double) this.mob.getBbWidth() * 1.25D);

        boolean reachedWaypoint = (dx < advanceRadius && dz < advanceRadius && dy < 1.5D);

        // 2. Plane-Cross check (advance if entity has already passed the current node along the path)
        if (!reachedWaypoint && currentIndex + 1 < nodeCount) {
            Node currentNode = this.path.getNode(currentIndex);
            Node nextNode = this.path.getNode(currentIndex + 1);

            double pathVecX = nextNode.x - currentNode.x;
            double pathVecZ = nextNode.z - currentNode.z;
            double pathLenSq = pathVecX * pathVecX + pathVecZ * pathVecZ;

            if (pathLenSq > 0.1) {
                double toMobX = this.mob.getX() - (currentNode.x + 0.5);
                double toMobZ = this.mob.getZ() - (currentNode.z + 0.5);
                double projection = (toMobX * pathVecX + toMobZ * pathVecZ) / pathLenSq;

                // Mob has advanced beyond the perpendicular plane of the current node
                if (projection > 0.25) {
                    reachedWaypoint = true;
                }
            }
        }

        if (reachedWaypoint) {
            this.path.advance();
            stuckDetector.reset();
        }

        // 3. Safe Corner Shortcut Optimization (skip forward only with full swept-box clearance)
        if (!this.path.isDone()) {
            int lookAheadLimit = Math.min(this.path.getNextNodeIndex() + 4, nodeCount);
            for (int targetIdx = lookAheadLimit - 1; targetIdx > this.path.getNextNodeIndex(); targetIdx--) {
                Vec3 targetNodePos = this.path.getEntityPosAtNode(this.mob, targetIdx);
                if (PathClearanceValidator.isPathClear(this.level, mobPos, targetNodePos, this.mob.getBbWidth(), this.mob.getBbHeight())) {
                    this.path.setNextNodeIndex(targetIdx);
                    break;
                }
            }
        }

        // 4. Stuck Detection & Progressive Recovery Execution
        if (unstuckAttemptCooldown > 0) {
            unstuckAttemptCooldown--;
        }

        NpcStuckDetector.RecoveryAction action = stuckDetector.update(
                this.mob.getX(),
                this.mob.getY(),
                this.mob.getZ(),
                !this.isDone()
        );

        if (action != NpcStuckDetector.RecoveryAction.NONE && unstuckAttemptCooldown == 0) {
            handleRecoveryAction(action);
        }
    }

    private void handleRecoveryAction(NpcStuckDetector.RecoveryAction action) {
        switch (action) {
            case JUMP_ASSIST -> {
                // If stuck at a step or ledge, jump
                this.mob.getJumpControl().jump();
                this.unstuckAttemptCooldown = 5;
            }
            case ADVANCE_NODE -> {
                // Skip problematic waypoint
                if (this.path != null && !this.path.isDone()) {
                    this.path.advance();
                }
                this.unstuckAttemptCooldown = 8;
            }
            case REPATH -> {
                // Force path recalculation around obstacle
                this.recomputePath();
                this.unstuckAttemptCooldown = 15;
            }
            case NUDGE -> {
                // Apply evasive velocity impulse towards current target node
                if (this.path != null && !this.path.isDone()) {
                    Vec3i targetPos = this.path.getNextNodePos();
                    double nx = (targetPos.getX() + 0.5) - this.mob.getX();
                    double nz = (targetPos.getZ() + 0.5) - this.mob.getZ();
                    double len = Math.sqrt(nx * nx + nz * nz);
                    if (len > 0.01) {
                        this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(
                                (nx / len) * 0.2D,
                                0.25D,
                                (nz / len) * 0.2D
                        ));
                    }
                }
                this.unstuckAttemptCooldown = 10;
            }
            case EMERGENCY_UNSTUCK -> {
                // Teleport to nearest safe walkable block
                if (this.path != null && !this.path.isDone()) {
                    Vec3i targetPos = this.path.getNextNodePos();
                    if (tryEmergencyUnstuck(targetPos)) {
                        this.stuckDetector.reset();
                    }
                }
                this.unstuckAttemptCooldown = 20;
            }
        }
    }

    private boolean tryEmergencyUnstuck(Vec3i nearPos) {
        BlockPos.MutableBlockPos testPos = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 1; dy >= -1; dy--) {
                    testPos.set(nearPos.getX() + dx, nearPos.getY() + dy, nearPos.getZ() + dz);
                    if (isSafeStandPosition(testPos)) {
                        this.mob.moveTo(testPos.getX() + 0.5D, testPos.getY(), testPos.getZ() + 0.5D, this.mob.getYRot(), this.mob.getXRot());
                        this.mob.getNavigation().stop();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSafeStandPosition(BlockPos pos) {
        BlockPos below = pos.below();
        BlockState stateBelow = this.level.getBlockState(below);
        BlockState stateAt = this.level.getBlockState(pos);
        BlockState stateAbove = this.level.getBlockState(pos.above());

        if (!stateBelow.isSolid() || stateAt.isSolid() || stateAbove.isSolid()) {
            return false;
        }

        AABB box = this.mob.getBoundingBox().move(
                (pos.getX() + 0.5D) - this.mob.getX(),
                (double) pos.getY() - this.mob.getY(),
                (pos.getZ() + 0.5D) - this.mob.getZ()
        );

        return this.level.noCollision(this.mob, box);
    }
}
