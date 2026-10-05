package com.storynpcs.ai.combat;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.ai.FactionRelationshipProvider;
import com.storynpcs.domain.ai.TargetingPolicy;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.entity.StoryNpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic sight-based target acquisition (P3-3). Resolves the authored
 * {@code attackOnSight} contract into concrete candidates — factioned NPCs via
 * {@code targetFactionIds} plus the inter-faction relationship matrix, players
 * via their standing toward the NPC's faction and every listed target faction —
 * then feeds the winning target through the existing threat pipeline so melee
 * engagement, aggro events, and the 32-block leash all stay canonical.
 */
public class NpcAttackOnSightGoal extends Goal {

    /** Ticks between world scans — bounded, never per-tick. */
    private static final int SCAN_INTERVAL_TICKS = 10;
    /** Re-check the sensing budget at this cadence while the tier disables it. */
    private static final int TIER_RECHECK_TICKS = 20;
    /** Threat written for a sight-acquired target — enough to engage, decaying if it escapes. */
    private static final int SIGHT_THREAT = 200;

    private final StoryNpcEntity npc;
    private int scanDelay;
    private long cachedRegistryRevision = Long.MIN_VALUE;
    private FactionRelationshipProvider cachedRelationships = FactionRelationshipProvider.neutral();

    public NpcAttackOnSightGoal(StoryNpcEntity npc) {
        this.npc = npc;
        // Flagless: the goal only acquires targets — movement stays with the melee goal.
    }

    @Override
    public boolean canUse() {
        if (--scanDelay > 0) {
            return false;
        }
        // P4-1: the sensing budget owns sight-scan cadence. Disabled tiers
        // (DORMANT) never scan — re-check on a fixed cadence so a tier upgrade
        // re-arms acquisition; degraded tiers widen the interval.
        int sensingPeriod = npc.simulationCapabilityPeriod(
                com.storynpcs.sim.SimulationScheduler.Capability.SENSING);
        if (sensingPeriod < 0) {
            scanDelay = TIER_RECHECK_TICKS;
            return false;
        }
        scanDelay = Math.max(SCAN_INTERVAL_TICKS, sensingPeriod);
        if (!sensePreconditions()) {
            return false;
        }
        return acquireTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return false; // one-shot acquisition — the threat table owns engagement state
    }

    private boolean sensePreconditions() {
        if (!npc.isAlive() || npc.isRemoved() || npc.level().isClientSide) {
            return false;
        }
        var mod = StoryNpcsAccess.mod(npc);
        if (mod == null || mod.getRegistry() == null) {
            return false;
        }
        // An engaged NPC keeps its threat target — sight never churns combat.
        if (npc.getThreatManager().getCurrentTarget().isPresent()) {
            return false;
        }
        var defOpt = npc.getDefinition();
        if (defOpt.isEmpty() || defOpt.get().getAi() == null
                || !defOpt.get().getAi().isAttackOnSight()) {
            return false;
        }
        TacticalStance stance = npc.getState().getEffectiveTacticalStance(mod.getRegistry());
        if (stance == TacticalStance.PASSIVE) {
            return false;
        }
        // A faction authored passive never initiates hostile targeting.
        NamespacedId ownFactionId = ownFactionId();
        if (ownFactionId != null) {
            boolean passive = mod.getRegistry().getFaction(ownFactionId)
                    .map(com.storynpcs.domain.faction.Faction::isPassive).orElse(false);
            if (passive) {
                return false;
            }
        }
        return true;
    }

    private NamespacedId ownFactionId() {
        var mod = StoryNpcsAccess.mod(npc);
        return mod != null && mod.getRegistry() != null
                ? npc.getState().getFactionId(mod.getRegistry()).orElse(null)
                : null;
    }

    private FactionRelationshipProvider relationships() {
        var mod = StoryNpcsAccess.mod(npc);
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

    private boolean acquireTarget() {
        var mod = StoryNpcsAccess.mod(npc);
        var registry = mod != null ? mod.getRegistry() : null;
        var defOpt = npc.getDefinition();
        if (registry == null || defOpt.isEmpty() || !(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        NpcAi ai = defOpt.get().getAi();
        NamespacedId ownFactionId = ownFactionId();

        var followAttr = npc.getAttribute(Attributes.FOLLOW_RANGE);
        double range = followAttr != null ? followAttr.getValue() : 16.0;
        range = Math.min(64.0, Math.max(4.0, range));
        double rangeSq = range * range;

        var candidates = new ArrayList<TargetingPolicy.Candidate>();
        // P4-2 squad state, collected for free inside the scan the goal
        // already performs — no extra world query. Allies' engaged targets
        // are excluded from acquisition; idle allies join the allocation
        // round so simultaneous scans cannot claim the same target twice.
        var idleSquadmates = new LinkedHashSet<UUID>();
        var allyEngagedTargets = new LinkedHashSet<UUID>();
        var engagedAllies = new java.util.LinkedHashMap<UUID, UUID>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                npc.getBoundingBox().inflate(range),
                // A HIDE-resolved defeat statue is not a valid hostile target.
                e -> e != npc && e.isAlive() && !e.isRemoved()
                        && !(e instanceof com.storynpcs.entity.StoryNpcEntity sn
                        && sn.isHiddenDefeat()))) {
            double distSq = npc.distanceToSqr(entity);
            if (distSq > rangeSq || !npc.hasLineOfSight(entity)) {
                continue;
            }
            if (entity instanceof Player p && (p.isCreative() || p.isSpectator())) {
                continue;
            }
            if (entity instanceof StoryNpcEntity otherNpc) {
                NamespacedId candidateFaction = otherNpc.getState()
                        .getFactionId(registry).orElse(null);
                if (ownFactionId != null && ownFactionId.equals(candidateFaction)) {
                    otherNpc.getThreatManager().getCurrentTarget()
                            .ifPresentOrElse(t -> {
                                        allyEngagedTargets.add(t);
                                        engagedAllies.put(otherNpc.getUUID(), t);
                                    }, () -> idleSquadmates.add(otherNpc.getUUID()));
                }
                candidates.add(new TargetingPolicy.Candidate(
                        entity.getUUID(), candidateFaction, distSq,
                        entity.getHealth(), entity.getMaxHealth(),
                        threatOf(entity.getUUID()), false));
            } else if (entity instanceof Player player) {
                boolean hostileStanding = playerHostileStanding(
                        player.getUUID(), ownFactionId, ai, registry, mod);
                candidates.add(new TargetingPolicy.Candidate(
                        entity.getUUID(), null, distSq,
                        entity.getHealth(), entity.getMaxHealth(),
                        threatOf(entity.getUUID()), hostileStanding));
            }
            // Factionless mobs carry no standing — they can only be engaged
            // through the threat table (provocation), never on sight.
        }
        if (candidates.isEmpty()) {
            return false;
        }

        // P4-2: same-faction squad coordination — unique target allocation
        // through the shared coordinator instead of every scanner piling onto
        // the same best candidate. Targets already engaged by allies or
        // claimed in the coordinator are excluded before choosing.
        if (ownFactionId != null && (!idleSquadmates.isEmpty() || !allyEngagedTargets.isEmpty())) {
            // Squad allocation only ever sees policy-eligible candidates —
            // the authored targeting rules gate membership before assignment.
            var eligible = candidates.stream()
                    .filter(c -> TargetingPolicy.isEligible(
                            ai, ownFactionId, false, relationships(), c))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return acquireSquadTarget(mod, level, ai, eligible,
                    idleSquadmates, allyEngagedTargets, engagedAllies);
        }

        boolean ownPassive = false; // preconditions already excluded a passive own faction
        Optional<UUID> chosen = TargetingPolicy.chooseTarget(
                ai, ownFactionId, ownPassive, relationships(), candidates);
        if (chosen.isEmpty()) {
            return false;
        }
        npc.getThreatManager().addThreat(chosen.get(), SIGHT_THREAT);
        return true;
    }

    /**
     * Squad-coordinated acquisition: the (dimension, faction) coordinator
     * allocates distinct targets to the idle squad. Targets already engaged
     * by an ally, or claimed by another actor's live assignment, are removed
     * from my candidate set; an assignment I already hold and which is still
     * live is reused rather than churned. When the round allocates nothing
     * to me (candidates exhausted by higher-priority squadmates), I stay
     * idle — no duplicate targets, no lost threat.
     */
    private boolean acquireSquadTarget(StoryNpcs mod, ServerLevel level, NpcAi ai,
                                       java.util.List<TargetingPolicy.Candidate> candidates,
                                       Set<UUID> idleSquadmates, Set<UUID> allyEngagedTargets,
                                       java.util.Map<UUID, UUID> engagedAllies) {
        NamespacedId ownFactionId = ownFactionId();
        if (ownFactionId == null) {
            return false;
        }
        var coordinator = mod.squadCoordinator(
                level.dimension().location().toString(), ownFactionId);

        if (!allyEngagedTargets.isEmpty()) {
            candidates.removeIf(c -> allyEngagedTargets.contains(c.id()));
        }

        // Live target ids for pruning: my scan candidates plus whatever the
        // allies are already engaged on.
        var liveIds = new java.util.HashSet<UUID>();
        candidates.forEach(c -> liveIds.add(c.id()));
        liveIds.addAll(allyEngagedTargets);
        coordinator.unassigned(liveIds).forEach(coordinator::release);

        // A claim I already hold stays mine while the target remains live and
        // unengaged — prevents scan-to-scan target churn.
        var mine = coordinator.assignmentOf(npc.getUUID());
        if (mine.isPresent() && liveIds.contains(mine.get().targetId())
                && !allyEngagedTargets.contains(mine.get().targetId())) {
            npc.getThreatManager().addThreat(mine.get().targetId(), SIGHT_THREAT);
            return true;
        }

        // Exclude targets already claimed by other actors in this round.
        var claimedByOthers = new java.util.HashSet<UUID>();
        coordinator.assignments().values().forEach(a -> {
            if (!a.actorId().equals(npc.getUUID())) {
                claimedByOthers.add(a.targetId());
            }
        });
        if (!claimedByOthers.isEmpty()) {
            candidates.removeIf(c -> claimedByOthers.contains(c.id()));
        }
        if (candidates.isEmpty()) {
            return false;
        }

        var squad = new LinkedHashSet<UUID>(idleSquadmates);
        squad.add(npc.getUUID());
        var candidateIds = candidates.stream()
                .map(TargetingPolicy.Candidate::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var assignments = coordinator.coordinate(squad, candidateIds,
                this::threatOf, engagedAllies);
        return assignments.stream()
                .filter(a -> a.actorId().equals(npc.getUUID()))
                .findFirst()
                .map(a -> {
                    npc.getThreatManager().addThreat(a.targetId(), SIGHT_THREAT);
                    return true;
                })
                .orElse(false);
    }

    private int threatOf(UUID entityUuid) {
        var entry = npc.getThreatManager().getThreatTable().get(entityUuid);
        return entry != null ? entry : 0;
    }

    /**
     * A player is a hostile-standing target when their points against the NPC's
     * own faction — or any authored target faction — fall below that faction's
     * hostile threshold. Standing is read from durable progression; a missing
     * record resolves to each faction's authored default.
     */
    static boolean playerHostileStanding(UUID playerUuid, NamespacedId ownFactionId,
                                          NpcAi ai,
                                          com.storynpcs.yaml.DefinitionRegistry registry,
                                          StoryNpcs mod) {
        var progressionRepo = mod.getProgressionRepository();
        if (progressionRepo == null) {
            return false;
        }
        var progression = progressionRepo.getOrCreate(playerUuid);
        Set<NamespacedId> factionsToCheck = new LinkedHashSet<>(ai.getTargetFactionIds());
        if (ownFactionId != null) {
            factionsToCheck.add(ownFactionId);
        }
        for (NamespacedId factionId : factionsToCheck) {
            var faction = registry.getFaction(factionId);
            int hostileThreshold = faction
                    .map(com.storynpcs.domain.faction.Faction::getHostileThreshold).orElse(500);
            int defaultPoints = faction
                    .map(com.storynpcs.domain.faction.Faction::getDefaultPoints).orElse(1000);
            if (progression.getFactionScore(factionId, defaultPoints) < hostileThreshold) {
                return true;
            }
        }
        return false;
    }
}
