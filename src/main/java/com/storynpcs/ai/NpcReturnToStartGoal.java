package com.storynpcs.ai;

import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class NpcReturnToStartGoal extends Goal {

    private final StoryNpcEntity npc;
    private final double speedModifier;
    private int repathDelay = 0;

    public NpcReturnToStartGoal(StoryNpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (repathDelay > 0) {
            repathDelay--;
            return false;
        }
        if (!npc.isAlive()) return false;
        if (npc.getFollowerRole() != null && npc.getFollowerRole().getState() == com.storynpcs.domain.role.follower.FollowerRole.State.FOLLOWING) {
            return false;
        }
        var defOpt = npc.getDefinition();
        if (defOpt.isEmpty()) return false;

        NpcAi ai = defOpt.get().getAi();
        if (ai == null || !ai.isReturnToStart() || ai.getMovementType() != NpcAi.MovementType.WANDERING) {
            return false;
        }

        BlockPos start = npc.getStartPosition();
        if (start == null) return false;

        double distSq = npc.distanceToSqr(start.getX(), start.getY(), start.getZ());
        double maxDistSq = (double) ai.getWalkingRange() * (double) ai.getWalkingRange();

        return distSq > maxDistSq;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos start = npc.getStartPosition();
        if (start == null) return false;
        return !npc.getNavigation().isDone() && npc.distanceToSqr(start.getX(), start.getY(), start.getZ()) > 4.0;
    }

    @Override
    public void start() {
        BlockPos start = npc.getStartPosition();
        if (start != null) {
            npc.getNavigation().moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void tick() {
        if (npc.getNavigation().isDone()) {
            this.repathDelay = 40;
        }
    }

    @Override
    public void stop() {
        this.repathDelay = 40;
    }
}