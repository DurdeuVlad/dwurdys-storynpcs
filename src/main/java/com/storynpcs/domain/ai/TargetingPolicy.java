package com.storynpcs.domain.ai;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure target-selection policy for attack-on-sight NPCs (P3-3). All inputs are
 * pre-resolved facts — the goal adapter is responsible for turning world
 * entities into {@link Candidate} records — so selection is deterministic,
 * bounded, and unit-testable without a running server.
 *
 * <p>Eligibility rules, in order:
 * <ul>
 *   <li>{@code attackOnSight} off, a missing definition, or a PASSIVE own
 *   faction → nothing is eligible.</li>
 *   <li>A candidate in the SAME faction as the NPC is never eligible, whatever
 *   the authored target list says — authored lists cannot cause friendly
 *   fire.</li>
 *   <li>A factioned candidate is eligible iff its faction is listed in
 *   {@code targetFactionIds} or resolves HOSTILE through the relationship
 *   provider. FRIENDLY/NEUTRAL factions are never engaged on sight.</li>
 *   <li>A factionless candidate (e.g. a player) is eligible iff the caller
 *   marked {@code hostileStanding} — the adapter resolves player faction
 *   standing against the NPC's own faction and every listed target
 *   faction.</li>
 * </ul>
 */
public final class TargetingPolicy {

    private TargetingPolicy() {}

    /**
     * @param id           entity UUID (stable identity for tie-breaking)
     * @param factionId    the candidate's faction, or null when factionless
     * @param distanceSq   squared distance to the NPC
     * @param health       current health
     * @param maxHealth    max health
     * @param threat       current threat-table entry (0 when none)
     * @param hostileStanding caller-resolved hostility flag for factionless
     *                        candidates (players); ignored for factioned ones
     */
    public record Candidate(UUID id, NamespacedId factionId, double distanceSq,
                            double health, double maxHealth, int threat,
                            boolean hostileStanding) {

        public Candidate {
            if (id == null) {
                throw new IllegalArgumentException("candidate id required");
            }
            if (distanceSq < 0) {
                throw new IllegalArgumentException("distanceSq cannot be negative");
            }
        }
    }

    /** Whether one candidate qualifies for attack-on-sight engagement. */
    public static boolean isEligible(NpcAi ai, NamespacedId ownFactionId,
                                     boolean ownFactionPassive,
                                     FactionRelationshipProvider relationships,
                                     Candidate candidate) {
        if (ai == null || !ai.isAttackOnSight() || candidate == null || ownFactionPassive) {
            return false;
        }
        NamespacedId targetFaction = candidate.factionId();
        if (targetFaction != null) {
            if (targetFaction.equals(ownFactionId)) {
                return false; // same faction — never eligible, authored list or not
            }
            if (ai.getTargetFactionIds().contains(targetFaction)) {
                return true;
            }
            var provider = relationships != null ? relationships : FactionRelationshipProvider.neutral();
            return ownFactionId != null
                    && provider.relationship(ownFactionId, targetFaction)
                            == FactionRelationshipProvider.Relationship.HOSTILE;
        }
        return candidate.hostileStanding();
    }

    /**
     * Select the engagement target from eligible candidates under the authored
     * priority. Deterministic: ties always resolve by distance, then UUID.
     */
    public static Optional<UUID> chooseTarget(NpcAi ai, NamespacedId ownFactionId,
                                              boolean ownFactionPassive,
                                              FactionRelationshipProvider relationships,
                                              List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        var eligible = candidates.stream()
                .filter(c -> isEligible(ai, ownFactionId, ownFactionPassive, relationships, c))
                .toList();
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        var priority = ai != null && ai.getTargetPriority() != null
                ? ai.getTargetPriority() : NpcAi.TargetPriority.NEAREST;
        return switch (priority) {
            case NEAREST -> eligible.stream().min(
                    java.util.Comparator.comparingDouble(Candidate::distanceSq)
                            .thenComparing(c -> c.id().toString()))
                    .map(Candidate::id);
            case WEAKEST -> eligible.stream().min(
                    java.util.Comparator.comparingDouble(Candidate::health)
                            .thenComparingDouble(Candidate::distanceSq)
                            .thenComparing(c -> c.id().toString()))
                    .map(Candidate::id);
            case STRONGEST -> eligible.stream().min(
                    java.util.Comparator.<Candidate>comparingDouble(c -> -c.maxHealth())
                            .thenComparingDouble(Candidate::distanceSq)
                            .thenComparing(c -> c.id().toString()))
                    .map(Candidate::id);
            case FIRST_THREAT -> selectFirstThreat(eligible);
        };
    }

    /**
     * Whether one candidate qualifies for authored "avoid" behavior (B7):
     * selector-matched entities the NPC flees rather than engages. Mirrors
     * {@link #isEligible} minus the {@code attackOnSight} and own-faction-
     * passive gates — avoidance is a passive policy and applies even to
     * NPCs that never fight. Same-faction candidates are still exempt.
     */
    public static boolean isEligibleForAvoidance(NpcAi ai, NamespacedId ownFactionId,
                                                 FactionRelationshipProvider relationships,
                                                 Candidate candidate) {
        if (ai == null || !ai.isAvoidTargets() || candidate == null) {
            return false;
        }
        NamespacedId targetFaction = candidate.factionId();
        if (targetFaction != null) {
            if (targetFaction.equals(ownFactionId)) {
                return false;
            }
            if (ai.getTargetFactionIds().contains(targetFaction)) {
                return true;
            }
            var provider = relationships != null ? relationships : FactionRelationshipProvider.neutral();
            return ownFactionId != null
                    && provider.relationship(ownFactionId, targetFaction)
                            == FactionRelationshipProvider.Relationship.HOSTILE;
        }
        return candidate.hostileStanding();
    }

    /**
     * FIRST_THREAT: prefer the candidate with the highest existing threat —
     * the provoker the NPC should answer first. With no threat data the
     * nearest hostile wins, keeping the policy total.
     */
    private static Optional<UUID> selectFirstThreat(List<Candidate> eligible) {
        var byThreat = eligible.stream()
                .filter(c -> c.threat() > 0)
                .min(java.util.Comparator.<Candidate>comparingInt(c -> -c.threat())
                        .thenComparingDouble(Candidate::distanceSq)
                        .thenComparing(c -> c.id().toString()));
        if (byThreat.isPresent()) {
            return byThreat.map(Candidate::id);
        }
        return eligible.stream()
                .min(java.util.Comparator.comparingDouble(Candidate::distanceSq)
                        .thenComparing(c -> c.id().toString()))
                .map(Candidate::id);
    }
}
