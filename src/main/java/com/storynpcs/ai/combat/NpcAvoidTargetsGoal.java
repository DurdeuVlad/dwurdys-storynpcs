package com.storynpcs.ai.combat;

import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.ai.FactionRelationshipProvider;
import com.storynpcs.domain.ai.TargetingPolicy;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * B7 "avoid" vocabulary (P3-3): the NPC flees selector-matched entities
 * instead of engaging them. Selection reuses the authored targeting
 * vocabulary — authored faction list, provider-hostile relationships, and
 * player hostile standing — through
 * {@link TargetingPolicy#isEligibleForAvoidance}. Scans on the same bounded
 * 10-tick cadence as {@link NpcAttackOnSightGoal} inside a fixed radius;
 * the faction-relationship provider is cached per registry revision.
 */
public class NpcAvoidTargetsGoal extends Goal {

    private static final double SCAN_RADIUS = 12.0;
    private static final int SCAN_COOLDOWN_TICKS = 10;
    private static final int FLEE_TICKS = 40;

    private final StoryNpcEntity npc;
    private FactionRelationshipProvider cachedRelationships = FactionRelationshipProvider.neutral();
    private long cachedRegistryRevision = -1;
    private int scanCooldown;
    private int fleeTicks;

    public NpcAvoidTargetsGoal(StoryNpcEntity npc) {
        this.npc = npc;
        setFlags(java.util.EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        var defOpt = npc.getDefinition();
        var ai = defOpt.map(d -> d.getAi()).orElse(null);
        if (ai == null || !ai.isAvoidTargets()) {
            return false;
        }
        if (scanCooldown > 0) {
            scanCooldown--;
            return fleeTicks > 0;
        }
        scanCooldown = SCAN_COOLDOWN_TICKS;
        LivingEntity threat = nearestAvoidable(ai);
        if (threat == null) {
            return fleeTicks > 0;
        }
        fleeTicks = FLEE_TICKS;
        fleeFrom(threat);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return fleeTicks > 0 && !npc.getNavigation().isDone();
    }

    @Override
    public void tick() {
        if (fleeTicks > 0) {
            fleeTicks--;
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
    }

    private LivingEntity nearestAvoidable(NpcAi ai) {
        var mod = StoryNpcsAccess.mod(npc);
        var registry = mod != null ? mod.getRegistry() : null;
        if (registry == null || !(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        NamespacedId ownFactionId = npc.getState().getFactionId(registry).orElse(null);
        LivingEntity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                npc.getBoundingBox().inflate(SCAN_RADIUS),
                e -> e != npc && e.isAlive() && !e.isRemoved()
                        && !(e instanceof Player p && (p.isCreative() || p.isSpectator()))
                        && !(e instanceof StoryNpcEntity sn && sn.isHiddenDefeat()))) {
            double distSq = npc.distanceToSqr(entity);
            TargetingPolicy.Candidate c;
            if (entity instanceof StoryNpcEntity other) {
                c = new TargetingPolicy.Candidate(entity.getUUID(),
                        other.getState().getFactionId(registry).orElse(null), distSq,
                        entity.getHealth(), entity.getMaxHealth(), 0, false);
            } else if (entity instanceof Player player) {
                boolean hostile = NpcAttackOnSightGoal.playerHostileStanding(
                        player.getUUID(), ownFactionId, ai, registry, mod);
                c = new TargetingPolicy.Candidate(entity.getUUID(), null, distSq,
                        entity.getHealth(), entity.getMaxHealth(), 0, hostile);
            } else {
                // Factionless mobs carry no standing — nothing to avoid.
                continue;
            }
            if (TargetingPolicy.isEligibleForAvoidance(ai, ownFactionId, relationships(mod), c)
                    && distSq < nearestSq) {
                nearestSq = distSq;
                nearest = entity;
            }
        }
        return nearest;
    }

    private void fleeFrom(LivingEntity threat) {
        double dx = npc.getX() - threat.getX();
        double dz = npc.getZ() - threat.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) {
            dx = 1.0;
            dz = 0.0;
            len = 1.0;
        }
        double awayX = npc.getX() + (dx / len) * SCAN_RADIUS;
        double awayZ = npc.getZ() + (dz / len) * SCAN_RADIUS;
        npc.getNavigation().moveTo(awayX, npc.getY(), awayZ, 1.2D);
    }

    private FactionRelationshipProvider relationships(com.storynpcs.StoryNpcs mod) {
        if (mod == null || mod.getRegistry() == null) {
            return FactionRelationshipProvider.neutral();
        }
        long revision = mod.getRegistry().revision();
        if (revision != cachedRegistryRevision) {
            cachedRelationships = FactionRelationshipProvider.fromFactions(
                    mod.getRegistry().getAllFactions());
            cachedRegistryRevision = revision;
        }
        return cachedRelationships;
    }
}
