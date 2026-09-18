package com.storynpcs.ai;

import com.storynpcs.domain.role.follower.FollowerGroup;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationCalculator;
import com.storynpcs.domain.role.follower.FormationOffset;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * AI goal allowing NPCs with the FollowerRole to escort their owner in a tactical formation.
 */
public class NpcFollowFormationGoal extends Goal {

    private final StoryNpcEntity npc;
    private LivingEntity leader;
    private final double baseSpeed;
    private final double catchUpSpeed;
    private final float stopDistance;
    private final float teleportDistance;
    private int timeToRecalcPath = 0;
    private int timeStuck = 0;

    public NpcFollowFormationGoal(
            StoryNpcEntity npc,
            double baseSpeed,
            double catchUpSpeed,
            float stopDistance,
            float teleportDistance
    ) {
        this.npc = npc;
        this.baseSpeed = baseSpeed;
        this.catchUpSpeed = catchUpSpeed;
        this.stopDistance = stopDistance;
        this.teleportDistance = teleportDistance;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive()) return false;

        FollowerRole role = npc.getFollowerRole();
        if (role == null || role.getState() != FollowerRole.State.FOLLOWING) {
            return false;
        }

        if (role.getOwnerUuid() == null) {
            return false;
        }

        Player player = npc.level().getPlayerByUUID(role.getOwnerUuid());
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return false;
        }

        this.leader = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (leader == null || leader.isRemoved() || leader.level() != npc.level() || !leader.isAlive() || leader.isSpectator()) {
            return false;
        }

        FollowerRole role = npc.getFollowerRole();
        return role != null && role.getState() == FollowerRole.State.FOLLOWING;
    }

    @Override
    public void start() {
        this.timeToRecalcPath = 0;
        this.timeStuck = 0;
    }

    @Override
    public void stop() {
        this.leader = null;
        this.npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (leader == null) return;

        FollowerRole role = npc.getFollowerRole();
        if (role == null) return;

        // Calculate slot index (explicit or dynamically allocated from group)
        int slotIndex = role.getFormationSlot();
        if (slotIndex < 0) {
            slotIndex = FollowerGroup.getOrAssignSlot(role.getOwnerUuid(), npc.getUUID());
        }

        // Calculate formation offset
        FormationOffset offset = FormationCalculator.computeOffset(
                role.getFormation(),
                slotIndex,
                role.getFormationSpacing()
        );

        // Convert to absolute world position
        FormationCalculator.WorldPosition target = FormationCalculator.toWorldCoordinates(
                leader.getX(), leader.getY(), leader.getZ(),
                leader.getYRot(),
                offset
        );

        double targetX = target.x();
        double targetY = findGroundY(target.x(), target.y(), target.z());
        double targetZ = target.z();

        double distToLeaderSq = npc.distanceToSqr(leader);
        double teleportThresholdSq = teleportDistance * teleportDistance;

        // Teleport if too far or stranded across obstacles
        if (distToLeaderSq > teleportThresholdSq) {
            tryTeleportNear(targetX, targetY, targetZ);
            return;
        }

        double distToTargetSq = npc.distanceToSqr(targetX, targetY, targetZ);
        double stopDistSq = stopDistance * stopDistance;

        // Always face the leader or the direction the leader is facing
        this.npc.getLookControl().setLookAt(
                leader.getX(),
                leader.getEyeY(),
                leader.getZ(),
                10.0F,
                (float) npc.getMaxHeadXRot()
        );

        if (distToTargetSq <= stopDistSq) {
            // Already in formation slot - stop moving and align yaw
            if (!npc.getNavigation().isDone()) {
                npc.getNavigation().stop();
            }
            npc.setYRot(leader.getYRot());
            npc.yHeadRot = leader.getYRot();
        } else {
            // Determine movement speed (accelerate when falling behind or when leader is sprinting)
            double currentSpeed = (distToTargetSq > 36.0 || leader.isSprinting()) ? catchUpSpeed : baseSpeed;

            if (--timeToRecalcPath <= 0) {
                timeToRecalcPath = 10;
                npc.getNavigation().moveTo(targetX, targetY, targetZ, currentSpeed);
            }

            if (npc.getNavigation().isStuck()) {
                if (++timeStuck > 40) { // stuck for 2 seconds
                    tryTeleportNear(targetX, targetY, targetZ);
                    timeStuck = 0;
                }
            } else {
                timeStuck = 0;
            }
        }
    }

    private double findGroundY(double x, double y, double z) {
        Level level = npc.level();
        BlockPos base = BlockPos.containing(x, y, z);
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos test = base.offset(0, dy, 0);
            BlockState state = level.getBlockState(test);
            BlockState above = level.getBlockState(test.above());
            if (state.isSolid() && !above.isSolid()) {
                return test.getY() + 1.0;
            }
        }
        return y;
    }

    private boolean tryTeleportNear(double targetX, double targetY, double targetZ) {
        Level level = npc.level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 2; dy >= -2; dy--) {
                    pos.set(targetX + dx, targetY + dy, targetZ + dz);
                    if (isSafeTeleportTarget(level, pos)) {
                        npc.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, leader.getYRot(), 0.0F);
                        npc.getNavigation().stop();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSafeTeleportTarget(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState stateBelow = level.getBlockState(below);
        BlockState stateAt = level.getBlockState(pos);
        BlockState stateAbove = level.getBlockState(pos.above());

        if (!stateBelow.isSolid() || stateAt.isSolid() || stateAbove.isSolid()) {
            return false;
        }

        double dx = (pos.getX() + 0.5) - npc.getX();
        double dy = (double) pos.getY() - npc.getY();
        double dz = (pos.getZ() + 0.5) - npc.getZ();
        return level.noCollision(npc, npc.getBoundingBox().move(dx, dy, dz));
    }
}
