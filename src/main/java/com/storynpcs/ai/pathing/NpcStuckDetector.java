package com.storynpcs.ai.pathing;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * High-precision stuck detector that tracks entity movement trajectory over a sliding window
 * to detect stalls, corner-sliding, and trapped conditions, triggering progressive recovery actions.
 */
public class NpcStuckDetector {

    public enum RecoveryAction {
        NONE,
        JUMP_ASSIST,
        ADVANCE_NODE,
        REPATH,
        NUDGE,
        EMERGENCY_UNSTUCK
    }

    public record PositionSample(double x, double y, double z) {
        public double distanceToSqr(double ox, double oy, double oz) {
            double dx = x - ox;
            double dy = y - oy;
            double dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final int windowSize;
    private final double stallDistanceThresholdSq;
    private final Deque<PositionSample> history;
    private int stalledTicks = 0;
    private RecoveryAction lastAction = RecoveryAction.NONE;

    public NpcStuckDetector() {
        this(20, 0.04); // 20 ticks (1s), moving less than 0.2 blocks in total over window
    }

    public NpcStuckDetector(int windowSize, double stallDistanceThresholdSq) {
        this.windowSize = Math.max(5, windowSize);
        this.stallDistanceThresholdSq = stallDistanceThresholdSq;
        this.history = new ArrayDeque<>(this.windowSize);
    }

    /**
     * Records the current position and evaluates whether the entity is stuck.
     *
     * @param x            Current X position
     * @param y            Current Y position
     * @param z            Current Z position
     * @param isNavigating Whether navigation currently has an active path
     * @return Suggested recovery action to unblock the entity
     */
    public RecoveryAction update(double x, double y, double z, boolean isNavigating) {
        if (!isNavigating) {
            reset();
            return RecoveryAction.NONE;
        }

        PositionSample current = new PositionSample(x, y, z);

        if (history.size() >= windowSize) {
            PositionSample oldest = history.pollFirst();
            double totalDisplacementSq = oldest.distanceToSqr(x, y, z);

            if (totalDisplacementSq < stallDistanceThresholdSq) {
                stalledTicks++;
            } else {
                // Moving well - decrease stall counter
                stalledTicks = Math.max(0, stalledTicks - 2);
            }
        }

        history.addLast(current);

        // Determine recovery action based on duration of stall
        RecoveryAction action = determineRecoveryAction(stalledTicks);
        this.lastAction = action;
        return action;
    }

    private RecoveryAction determineRecoveryAction(int stalls) {
        if (stalls >= 65) {
            return RecoveryAction.EMERGENCY_UNSTUCK;
        } else if (stalls == 50) {
            return RecoveryAction.NUDGE;
        } else if (stalls == 35) {
            return RecoveryAction.REPATH;
        } else if (stalls == 20) {
            return RecoveryAction.ADVANCE_NODE;
        } else if (stalls == 10) {
            return RecoveryAction.JUMP_ASSIST;
        }
        return RecoveryAction.NONE;
    }

    public void reset() {
        this.history.clear();
        this.stalledTicks = 0;
        this.lastAction = RecoveryAction.NONE;
    }

    public int getStalledTicks() {
        return stalledTicks;
    }

    public RecoveryAction getLastAction() {
        return lastAction;
    }

    public boolean isStuck() {
        return stalledTicks >= 10;
    }
}
