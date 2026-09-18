package com.storynpcs.ai;

import com.storynpcs.domain.ai.Waypoint;
import com.storynpcs.domain.ai.WaypointPath;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class NpcPatrolGoal extends Goal {

    private final StoryNpcEntity npc;
    private final double speedModifier;
    private int currentWaypointIndex = 0;
    private boolean movingForward = true;
    private int waitTicksRemaining = 0;
    private int repathDelay = 0;

    public NpcPatrolGoal(StoryNpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }

    public void setCurrentWaypointIndex(int currentWaypointIndex) {
        this.currentWaypointIndex = currentWaypointIndex;
    }

    public boolean isMovingForward() {
        return movingForward;
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive()) return false;
        var defOpt = npc.getDefinition();
        if (defOpt.isEmpty()) return false;

        NpcAi ai = defOpt.get().getAi();
        if (ai == null || ai.getMovementType() != NpcAi.MovementType.PATHING) {
            return false;
        }

        WaypointPath path = ai.getWaypointPath();
        return path != null && path.size() > 0;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.waitTicksRemaining = 0;
        this.repathDelay = 0;
        moveToCurrentWaypoint();
    }

    @Override
    public void tick() {
        if (waitTicksRemaining > 0) {
            waitTicksRemaining--;
            return;
        }

        var defOpt = npc.getDefinition();
        if (defOpt.isEmpty()) return;

        WaypointPath path = defOpt.get().getAi().getWaypointPath();
        if (path == null || path.size() == 0) return;

        Waypoint current = path.getWaypoint(currentWaypointIndex);
        if (current == null) return;

        double distSq = npc.distanceToSqr(current.x(), current.y(), current.z());

        if (distSq <= 2.25) { // within 1.5 blocks
            if (current.waitTicks() > 0 && waitTicksRemaining == 0) {
                waitTicksRemaining = current.waitTicks();
            }

            int[] next = path.computeNextIndex(currentWaypointIndex, movingForward);
            this.currentWaypointIndex = next[0];
            this.movingForward = (next[1] == 1);
            this.repathDelay = 0;

            moveToCurrentWaypoint();
        } else if (npc.getNavigation().isDone()) {
            if (--this.repathDelay <= 0) {
                this.repathDelay = 20; // Throttle to at most 1 repath attempt per second
                moveToCurrentWaypoint();
            }
        }
    }

    private void moveToCurrentWaypoint() {
        var defOpt = npc.getDefinition();
        if (defOpt.isEmpty()) return;

        WaypointPath path = defOpt.get().getAi().getWaypointPath();
        if (path != null && path.size() > 0) {
            Waypoint target = path.getWaypoint(currentWaypointIndex);
            if (target != null) {
                npc.getNavigation().moveTo(target.x(), target.y(), target.z(), speedModifier);
            }
        }
    }
}