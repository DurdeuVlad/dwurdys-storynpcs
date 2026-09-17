package com.storynpcs.ai.combat;

import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * AI goal executing melee combat against the NPC's current threat target.
 */
public class NpcMeleeAttackGoal extends Goal {

    private final StoryNpcEntity npc;
    private final double speedModifier;
    private LivingEntity target;
    private int attackCooldown = 0;
    private int repathDelay = 0;

    public NpcMeleeAttackGoal(StoryNpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive()) return false;

        Optional<UUID> targetUuidOpt = npc.getThreatManager().getCurrentTarget();
        if (targetUuidOpt.isEmpty()) return false;

        UUID targetUuid = targetUuidOpt.get();
        Player player = npc.level().getPlayerByUUID(targetUuid);
        if (player != null && player.isAlive() && !player.isCreative() && !player.isSpectator()) {
            this.target = player;
            return true;
        }

        // Could be another living entity
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        return npc.getThreatManager().getCurrentTarget().map(u -> u.equals(target.getUUID())).orElse(false);
    }

    @Override
    public void start() {
        this.attackCooldown = 0;
        this.repathDelay = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        this.npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;

        this.npc.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSq = this.npc.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSq = getAttackReachSqr(target);

        if (--this.repathDelay <= 0) {
            this.repathDelay = 10;
            this.npc.getNavigation().moveTo(target, this.speedModifier);
        }

        if (attackCooldown > 0) {
            attackCooldown--;
        }

        if (distSq <= reachSq && attackCooldown <= 0) {
            attackCooldown = 20; // 1 second attack cadence
            this.npc.swing(InteractionHand.MAIN_HAND);
            this.npc.doHurtTarget(target);
        }
    }

    private double getAttackReachSqr(LivingEntity attackTarget) {
        return (double) (this.npc.getBbWidth() * 2.0F * this.npc.getBbWidth() * 2.0F + attackTarget.getBbWidth());
    }
}
