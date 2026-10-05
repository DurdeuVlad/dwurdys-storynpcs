package com.storynpcs.ai;

import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Authored-behavior goals gated on {@link NpcAi} vocabulary flags (P3-3 / B6-B7
 * parity). Each vanilla-style primitive is wrapped so the authored flag is the
 * single on/off switch — goals are always registered, never rebuilt when a
 * definition refreshes, and degrade to a no-op when the flag is off or no
 * definition resolves.
 */
public final class NpcAuthoredBehaviorGoals {

    private NpcAuthoredBehaviorGoals() {}

    private static NpcAi ai(StoryNpcEntity npc) {
        return npc.getDefinition().map(d -> d.getAi()).orElse(null);
    }

    /** B6 "watch closest": tracks the nearest player while idle. */
    public static class WatchClosestGoal extends LookAtPlayerGoal {
        private final StoryNpcEntity npc;

        public WatchClosestGoal(StoryNpcEntity npc, float radius) {
            super(npc, Player.class, radius);
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            NpcAi ai = ai(npc);
            return ai != null && ai.isWatchClosest() && super.canUse();
        }
    }

    /** B7 "panic": runs randomly when hurt instead of retaliating. */
    public static class PanicOnHurtGoal extends PanicGoal {
        private final StoryNpcEntity npc;

        public PanicOnHurtGoal(StoryNpcEntity npc, double speedModifier) {
            super(npc, speedModifier);
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            NpcAi ai = ai(npc);
            return ai != null && ai.isPanicOnHurt() && super.canUse();
        }
    }

    /** B6 "door bust": breaks closed doors instead of opening them. */
    public static class DoorBustGoal extends BreakDoorGoal {
        private final StoryNpcEntity npc;

        public DoorBustGoal(StoryNpcEntity npc) {
            super(npc, difficulty -> difficulty != Difficulty.PEACEFUL);
            this.npc = npc;
        }

        @Override
        public boolean canUse() {
            NpcAi ai = ai(npc);
            return ai != null && ai.isDoorBust() && super.canUse();
        }
    }

    /**
     * B6 "find shade": when standing under open sky in daylight, path toward
     * the nearest roofed block inside the authored walking range.
     */
    public static class SeekShadeGoal extends Goal {
        private final StoryNpcEntity npc;
        private int scanCooldown;

        public SeekShadeGoal(StoryNpcEntity npc) {
            this.npc = npc;
            setFlags(java.util.EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            NpcAi ai = ai(npc);
            if (ai == null || !ai.isSeekShade()) {
                return false;
            }
            if (scanCooldown > 0) {
                scanCooldown--;
                return false;
            }
            scanCooldown = 20;
            // Seeking cover mid-combat would drag the NPC off its engagement —
            // shade seeking is an idle-only behavior.
            if (npc.getTarget() != null || npc.getThreatManager().getCurrentTarget().isPresent()) {
                return false;
            }
            if (!npc.level().isDay() || !npc.level().canSeeSky(npc.blockPosition())) {
                return false;
            }
            var shelter = findShelteredBlock(npc, ai.getWalkingRange());
            return shelter.isPresent() && npc.getNavigation().moveTo(
                    shelter.get().getX() + 0.5, shelter.get().getY(),
                    shelter.get().getZ() + 0.5, 0.8D);
        }

        @Override
        public boolean canContinueToUse() {
            return !npc.getNavigation().isDone();
        }
    }

    /**
     * B6 "move indoors": seeks a roofed position when it is night or raining,
     * gated on the authored flag.
     */
    public static class ShelterIndoorsGoal extends Goal {
        private final StoryNpcEntity npc;
        private int scanCooldown;

        public ShelterIndoorsGoal(StoryNpcEntity npc) {
            this.npc = npc;
            setFlags(java.util.EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            NpcAi ai = ai(npc);
            if (ai == null || !ai.isShelterIndoors()) {
                return false;
            }
            if (scanCooldown > 0) {
                scanCooldown--;
                return false;
            }
            scanCooldown = 20;
            if (npc.getTarget() != null || npc.getThreatManager().getCurrentTarget().isPresent()) {
                return false; // idle-only, like SeekShadeGoal
            }
            if (npc.level().isDay() && !npc.level().isRaining()
                    && !npc.level().canSeeSky(npc.blockPosition())) {
                return false; // already sheltered in calm daylight
            }
            var shelter = findShelteredBlock(npc, ai.getWalkingRange());
            return shelter.isPresent() && npc.getNavigation().moveTo(
                    shelter.get().getX() + 0.5, shelter.get().getY(),
                    shelter.get().getZ() + 0.5, 1.0D);
        }

        @Override
        public boolean canContinueToUse() {
            return !npc.getNavigation().isDone();
        }
    }

    /**
     * Nearest standable position not exposed to the sky inside the authored
     * walking range. Bounded: the scan box is at most {@code range} on each
     * horizontal axis and three blocks tall.
     */
    private static java.util.Optional<BlockPos> findShelteredBlock(StoryNpcEntity npc,
                                                                   int authoredRange) {
        int range = Math.max(1, Math.min(64, authoredRange));
        BlockPos origin = npc.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-range, -2, -range), origin.offset(range, 2, range))) {
            if (!npc.level().canSeeSky(pos)
                    && npc.level().getBlockState(pos).isAir()
                    && npc.getNavigation().isStableDestination(pos)) {
                double d = pos.distSqr(origin);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return java.util.Optional.ofNullable(best);
    }
}
