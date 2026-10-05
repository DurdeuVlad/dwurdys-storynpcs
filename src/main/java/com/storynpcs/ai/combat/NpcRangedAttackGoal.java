package com.storynpcs.ai.combat;

import com.storynpcs.domain.npc.NpcStats;
import com.storynpcs.entity.NpcProjectileEntity;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.entity.StoryNpcRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Authored ranged attack (P3-2): fires {@link NpcProjectileEntity} volleys at
 * the threat-table target while it stays inside the authored range and line of
 * sight. Flagless like {@link NpcAttackOnSightGoal} — movement and melee stay
 * with {@link NpcMeleeAttackGoal}, so a ranged-capable NPC still closes and
 * strikes in melee when the target reaches it; the threat table remains the
 * single engagement authority.
 */
public class NpcRangedAttackGoal extends Goal {

    private final StoryNpcEntity npc;
    private LivingEntity target;
    private long windupUntilTick = Long.MIN_VALUE;
    private long nextVolleyTick = Long.MIN_VALUE;
    // P4-1: LOS raycasts run on the sensing budget — elapsed-tick throttle
    // with a cached result so deferred windows keep the last sighting. The
    // cache is keyed on the candidate: a mid-window threat-target flip always
    // re-raycasts rather than inheriting the previous target's verdict.
    private long lastLosTick = Long.MIN_VALUE;
    private boolean cachedLos = false;
    private java.util.UUID cachedLosTarget = null;

    public NpcRangedAttackGoal(StoryNpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive() || npc.isRemoved()) {
            return false;
        }
        var ranged = authoredRanged();
        if (ranged == null || !ranged.isEnabled() || ranged.getDamage() <= 0.0) {
            return false;
        }
        Optional<UUID> targetUuidOpt = npc.getThreatManager().getCurrentTarget();
        if (targetUuidOpt.isEmpty()) {
            return false;
        }
        if (!(npc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        var entity = serverLevel.getEntity(targetUuidOpt.get());
        if (entity instanceof com.storynpcs.entity.StoryNpcEntity sn && sn.isHiddenDefeat()) {
            npc.getThreatManager().forgive(targetUuidOpt.get());
            return false;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
            return false;
        }
        if (living instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        double rangeSq = ranged.getRange() * ranged.getRange();
        if (npc.distanceToSqr(living) > rangeSq) {
            return false;
        }
        if (!hasBudgetedLineOfSight(living)) {
            return false;
        }
        this.target = living;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive() || target.isRemoved()
                || (target instanceof com.storynpcs.entity.StoryNpcEntity sn && sn.isHiddenDefeat())) {
            return false;
        }
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        if (npc.getThreatManager().getCurrentTarget()
                .map(u -> !u.equals(target.getUUID())).orElse(true)) {
            return false;
        }
        var ranged = authoredRanged();
        if (ranged == null || !ranged.isEnabled()) {
            return false;
        }
        // Drift allowance: a target just outside max range can still be shot —
        // engagement/drop decisions stay with the melee goal's 32-block leash.
        double driftSq = Math.max(ranged.getRange() * ranged.getRange(), 32.0 * 32.0);
        return npc.distanceToSqr(target) <= driftSq
                && hasBudgetedLineOfSight(target);
    }

    /**
     * Sensing-budget-gated line of sight: disabled tiers never sight; degraded
     * tiers re-raycast at most once per tier period and reuse the cached
     * result in between.
     */
    private boolean hasBudgetedLineOfSight(LivingEntity candidate) {
        int sensingPeriod = npc.simulationCapabilityPeriod(
                com.storynpcs.sim.SimulationScheduler.Capability.SENSING);
        if (sensingPeriod < 0) {
            cachedLos = false;
            return false;
        }
        long now = npc.level().getGameTime();
        if (lastLosTick == Long.MIN_VALUE
                || now - lastLosTick >= Math.max(1, sensingPeriod)
                || !candidate.getUUID().equals(cachedLosTarget)) {
            lastLosTick = now;
            cachedLosTarget = candidate.getUUID();
            cachedLos = npc.getSensing().hasLineOfSight(candidate);
        }
        return cachedLos;
    }

    @Override
    public void start() {
        var ranged = authoredRanged();
        // delayTicks is the authored aim windup before the first volley.
        this.windupUntilTick = npc.level().getGameTime() + (ranged != null ? ranged.getDelayTicks() : 0);
        this.nextVolleyTick = Long.MIN_VALUE;
        this.cachedLos = true; // canUse just raycast-verified sight
        this.cachedLosTarget = this.target != null ? this.target.getUUID() : null;
    }

    @Override
    public void stop() {
        this.target = null;
        this.windupUntilTick = Long.MIN_VALUE;
        this.nextVolleyTick = Long.MIN_VALUE;
        this.lastLosTick = Long.MIN_VALUE;
        this.cachedLos = false;
        this.cachedLosTarget = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        var ranged = authoredRanged();
        if (ranged == null) {
            return;
        }
        npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        long now = npc.level().getGameTime();
        if (now < windupUntilTick || now < nextVolleyTick) {
            return;
        }
        // P4-1: volley cadence honors the combat budget — never faster than
        // authored, never firing at all where combat eval is disabled.
        int combatPeriod = npc.simulationCapabilityPeriod(
                com.storynpcs.sim.SimulationScheduler.Capability.COMBAT);
        if (combatPeriod < 0) {
            return;
        }
        nextVolleyTick = now + Math.max(ranged.getFireRateTicks(), combatPeriod);
        fireVolley(serverLevel, target, ranged);
    }

    private void fireVolley(ServerLevel level, LivingEntity living, NpcStats.Ranged ranged) {
        int shotCount = Math.max(1, ranged.getShotCount());
        for (int i = 0; i < shotCount; i++) {
            var projectile = new NpcProjectileEntity(StoryNpcRegistry.NPC_PROJECTILE.get(), level);
            projectile.configure(ranged, npc);
            projectile.setPos(npc.getX(), npc.getEyeY() - 0.1, npc.getZ());
            double dx = living.getX() - projectile.getX();
            double dz = living.getZ() - projectile.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            // Aim at mid-body; add the standard arc compensation only when the
            // authored projectile is gravity-affected.
            double dy = living.getY(0.5) - projectile.getY()
                    + (ranged.isGravityAffected() ? horizontal * 0.2 : 0.0);
            float inaccuracy = (100 - ranged.getAccuracyPercent()) * 0.15F;
            projectile.shoot(dx, dy, dz, (float) ranged.getProjectileSpeed(), inaccuracy);
            projectile.setNoGravity(!ranged.isGravityAffected());
            level.addFreshEntity(projectile);
        }
        npc.swing(InteractionHand.MAIN_HAND);
    }

    private NpcStats.Ranged authoredRanged() {
        return npc.getDefinition()
                .map(def -> def.getStats() != null ? def.getStats().getRanged() : null)
                .orElse(null);
    }
}
