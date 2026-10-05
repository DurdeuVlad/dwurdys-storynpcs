package com.storynpcs.ai.combat;

import com.storynpcs.domain.ai.TacticalManeuver;
import com.storynpcs.domain.npc.NpcAi;
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
 * Authored tactics (leap, retreat, stalk, ambush, circle, hit-and-run) layer
 * onto the same engagement — the threat table remains the single engagement
 * authority.
 */
public class NpcMeleeAttackGoal extends Goal {

    private final StoryNpcEntity npc;
    private final double speedModifier;
    private LivingEntity target;
    private int attackCooldown = 0;
    private int repathDelay = 0;

    // ── authored tactical state (reset per engagement) ──────────────────────
    private boolean retreatLatched;
    private long hitRunUntilTick = Long.MIN_VALUE;
    private long stalkUntilTick = Long.MIN_VALUE;
    private int orbitDirection = 1;
    private long orbitSwapTick = Long.MIN_VALUE;
    private long leapReadyTick;

    public NpcMeleeAttackGoal(StoryNpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive() || npc.isRemoved()) return false;

        Optional<UUID> targetUuidOpt = npc.getThreatManager().getCurrentTarget();
        if (targetUuidOpt.isEmpty()) return false;

        UUID targetUuid = targetUuidOpt.get();

        // Support players AND living entities (e.g. hostile mobs attacking the town) (VULN-19)
        LivingEntity candidate = null;
        if (npc.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.entity.Entity entity = serverLevel.getEntity(targetUuid);
            if (entity instanceof LivingEntity living) {
                candidate = living;
            }
        } else {
            candidate = npc.level().getPlayerByUUID(targetUuid);
        }

        if (candidate instanceof com.storynpcs.entity.StoryNpcEntity sn && sn.isHiddenDefeat()) {
            // A resolved defeat statue is not attackable — drop the threat.
            npc.getThreatManager().forgive(targetUuid);
            return false;
        }
        if (candidate != null && candidate.isAlive() && !candidate.isRemoved()) {
            if (candidate instanceof Player p && (p.isCreative() || p.isSpectator())) {
                npc.getThreatManager().forgive(targetUuid);
                return false;
            }
            // Enforce maximum leash range (32 blocks) to prevent cross-world chasing / spawn camping (VULN-18)
            if (npc.distanceToSqr(candidate) <= 32.0 * 32.0) {
                this.target = candidate;
                return true;
            } else {
                npc.getThreatManager().forgive(targetUuid);
            }
        } else {
            // Target is dead or offline; clear from threat table to prevent AI lock (VULN-18)
            npc.getThreatManager().forgive(targetUuid);
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive() || target.isRemoved()) return false;
        if (target instanceof com.storynpcs.entity.StoryNpcEntity sn && sn.isHiddenDefeat()) return false;
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        // Combat leash: drop aggro if target escapes beyond 32 blocks (VULN-18)
        if (npc.distanceToSqr(target) > 32.0 * 32.0) {
            npc.getThreatManager().forgive(target.getUUID());
            return false;
        }
        return npc.getThreatManager().getCurrentTarget().map(u -> u.equals(target.getUUID())).orElse(false);
    }

    @Override
    public void start() {
        this.attackCooldown = 0;
        this.repathDelay = 0;
        this.retreatLatched = false;
        this.hitRunUntilTick = Long.MIN_VALUE;
        this.orbitDirection = 1;
        this.orbitSwapTick = Long.MIN_VALUE;
        var ai = npc.getDefinition().map(d -> d.getAi()).orElse(null);
        // A stalker creeps in for one bounded window, then commits to melee.
        this.stalkUntilTick = ai != null && ai.getTacticalBehavior() == NpcAi.TacticalBehavior.STALK
                ? npc.level().getGameTime() + TacticalManeuver.stalkWindowTicks(ai.getTacticalRadius())
                : Long.MIN_VALUE;
    }

    @Override
    public void stop() {
        this.target = null;
        this.npc.getNavigation().stop();
        this.stalkUntilTick = Long.MIN_VALUE;
        this.hitRunUntilTick = Long.MIN_VALUE;
        this.retreatLatched = false;
    }

    @Override
    public void tick() {
        if (target == null) return;

        this.npc.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSq = this.npc.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSq = getAttackReachSqr(target);
        long now = npc.level().getGameTime();

        var ai = npc.getDefinition().map(d -> d.getAi()).orElse(null);
        var behavior = ai != null ? ai.getTacticalBehavior() : NpcAi.TacticalBehavior.NONE;
        int tacticalRadius = ai != null ? ai.getTacticalRadius() : 8;
        double healthFraction = npc.getMaxHealth() > 0 ? npc.getHealth() / npc.getMaxHealth() : 1.0;

        // P4-1: tier budgets degrade the two expensive capabilities — PATHING
        // widens repath cadence, COMBAT widens attack cadence. Disabled
        // periods mean no new paths and no attacks; the last issued path is
        // allowed to finish rather than being snapped to a halt.
        int pathingPeriod = npc.simulationCapabilityPeriod(
                com.storynpcs.sim.SimulationScheduler.Capability.PATHING);
        int combatPeriod = npc.simulationCapabilityPeriod(
                com.storynpcs.sim.SimulationScheduler.Capability.COMBAT);

        var decision = TacticalManeuver.decide(behavior, healthFraction, distSq, reachSq,
                tacticalRadius, retreatLatched, now < hitRunUntilTick, now < stalkUntilTick);
        retreatLatched = decision.retreatLatched();

        switch (decision.move()) {
            case HOLD -> this.npc.getNavigation().stop();
            case RETREAT -> {
                if (--this.repathDelay <= 0) {
                    this.repathDelay = pathingPeriod > 0 ? Math.max(10, pathingPeriod) : 20;
                    if (pathingPeriod > 0) {
                        moveAwayFromTarget(tacticalRadius);
                    }
                }
            }
            case APPROACH_TO_RADIUS -> {
                if (--this.repathDelay <= 0) {
                    this.repathDelay = pathingPeriod > 0 ? Math.max(10, pathingPeriod) : 20;
                    if (pathingPeriod > 0) {
                        this.npc.getNavigation().moveTo(target, this.speedModifier * 0.75);
                    }
                }
            }
            case ORBIT -> {
                this.npc.getNavigation().stop();
                if (now >= orbitSwapTick) {
                    orbitSwapTick = now + TacticalManeuver.orbitSwapTicks();
                    orbitDirection = -orbitDirection;
                }
                this.npc.getMoveControl().strafe(0.0F, (float) (this.speedModifier * 0.8) * orbitDirection);
            }
            case ENGAGE -> {
                if (--this.repathDelay <= 0) {
                    this.repathDelay = pathingPeriod > 0 ? Math.max(10, pathingPeriod) : 20;
                    if (pathingPeriod > 0) {
                        this.npc.getNavigation().moveTo(target, this.speedModifier);
                        tryLeap(target, distSq, reachSq, now, ai);
                    }
                }
            }
        }

        if (attackCooldown > 0) {
            attackCooldown--;
        }

        if (decision.attackAllowed() && distSq <= reachSq && attackCooldown <= 0
                && combatPeriod > 0) {
            attackCooldown = Math.max(authoredAttackDelayTicks(), combatPeriod);
            this.npc.swing(InteractionHand.MAIN_HAND);
            boolean hit = this.npc.doHurtTarget(target);
            applyAuthoredOnHitEffect(target);
            if (hit) {
                this.npc.fireAbilitiesOnAttack(target);
            }
            if (behavior == NpcAi.TacticalBehavior.HIT_AND_RUN) {
                hitRunUntilTick = now + TacticalManeuver.hitAndRunBackoffTicks(tacticalRadius);
            }
        }
    }

    /** Directed leap toward the target when the authored contract enables it. */
    private void tryLeap(LivingEntity target, double distSq, double reachSq, long now, NpcAi ai) {
        if (ai == null || !ai.isLeapAtTarget() || !npc.onGround() || now < leapReadyTick) {
            return;
        }
        // Only leap to close a gap — never point-blank, never beyond a short bound.
        if (distSq <= reachSq * 1.5 || distSq > 64.0) {
            return;
        }
        double dx = target.getX() - npc.getX();
        double dz = target.getZ() - npc.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;
        npc.setDeltaMovement(npc.getDeltaMovement().add(dx / len * 0.45, 0.35, dz / len * 0.45));
        npc.hasImpulse = true;
        leapReadyTick = now + 60; // bounded cadence — one leap per 3s
    }

    /** Navigate directly away from the target out to the tactical radius. */
    private void moveAwayFromTarget(int tacticalRadius) {
        double dx = npc.getX() - target.getX();
        double dz = npc.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double radius = Math.max(1.0, tacticalRadius);
        double awayX = len > 0.001 ? npc.getX() + (dx / len) * radius : npc.getX() + radius;
        double awayZ = len > 0.001 ? npc.getZ() + (dz / len) * radius : npc.getZ();
        this.npc.getNavigation().moveTo(awayX, npc.getY(), awayZ, this.speedModifier * 1.1);
    }

    /** Authored on-hit effect (P3-2 stats.melee contract): applies after the strike lands. */
    private void applyAuthoredOnHitEffect(LivingEntity target) {
        var melee = npc.getDefinition()
                .map(def -> def.getStats() != null ? def.getStats().getMelee() : null)
                .orElse(null);
        if (melee == null || melee.getEffectId().isBlank() || melee.getEffectDurationTicks() <= 0) {
            return;
        }
        var rl = net.minecraft.resources.ResourceLocation.tryParse(melee.getEffectId());
        if (rl == null) {
            return;
        }
        net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(rl).ifPresent(holder ->
                target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        holder, melee.getEffectDurationTicks(), melee.getEffectAmplifier()), npc));
    }

    /** Server-authoritative attack cadence from the stats contract. */
    private int authoredAttackDelayTicks() {
        return npc.getDefinition()
                .map(def -> def.getStats() != null && def.getStats().getMelee() != null
                        ? def.getStats().getMelee().getAttackDelayTicks() : 20)
                .orElse(20);
    }

    private double getAttackReachSqr(LivingEntity attackTarget) {
        double authored = npc.getDefinition()
                .map(def -> def.getStats() != null && def.getStats().getMelee() != null
                        ? def.getStats().getMelee().getAttackRange() : 0.0)
                .orElse(0.0);
        if (authored > 0.0) {
            return authored * authored;
        }
        return (double) (this.npc.getBbWidth() * 2.0F * this.npc.getBbWidth() * 2.0F + attackTarget.getBbWidth());
    }
}
