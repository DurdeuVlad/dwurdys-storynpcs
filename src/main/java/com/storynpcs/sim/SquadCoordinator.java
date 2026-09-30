package com.storynpcs.sim;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Deterministic squad target allocation. Each candidate target is claimed by at
 * most one squad member (no duplicate targets); assignment order is by threat
 * score desc then actor id, so results are replay-stable. Bounded: both the
 * squad and the candidate list are capped — overflow members are left
 * unassigned rather than colliding.
 */
public final class SquadCoordinator {

    public static final int DEFAULT_MAX_SQUAD = 32;
    public static final int DEFAULT_MAX_CANDIDATES = 64;

    public record Assignment(UUID actorId, UUID targetId, double threatScore) {}

    private final int maxSquad;
    private final int maxCandidates;
    private final Map<UUID, Assignment> assignments = new LinkedHashMap<>();

    public SquadCoordinator() {
        this(DEFAULT_MAX_SQUAD, DEFAULT_MAX_CANDIDATES);
    }

    public SquadCoordinator(int maxSquad, int maxCandidates) {
        if (maxSquad <= 0 || maxCandidates <= 0) {
            throw new IllegalArgumentException("squad and candidate caps must be positive");
        }
        this.maxSquad = maxSquad;
        this.maxCandidates = maxCandidates;
    }

    /**
     * Assign unique targets to squad members. Deterministic: actors sorted by
     * threat desc then id; candidates sorted by threat desc then id; each
     * candidate claimed at most once. When candidates run out, remaining
     * members stay unassigned (explicit, observable via {@link #unassigned}).
     */
    public List<Assignment> coordinate(Set<UUID> squad, Set<UUID> candidates,
                                       ToDoubleFunction<UUID> threatScore) {
        List<UUID> boundedSquad = squad.stream()
                .sorted(Comparator.<UUID>comparingDouble(threatScore::applyAsDouble).reversed()
                        .thenComparing(UUID::toString))
                .limit(maxSquad)
                .toList();
        List<UUID> boundedCandidates = candidates.stream()
                .sorted(Comparator.<UUID>comparingDouble(threatScore::applyAsDouble).reversed()
                        .thenComparing(UUID::toString))
                .limit(maxCandidates)
                .toList();

        java.util.Set<UUID> claimed = new java.util.HashSet<>();
        java.util.Set<UUID> engaged = new java.util.HashSet<>();
        List<Assignment> result = new java.util.ArrayList<>();
        for (UUID actor : boundedSquad) {
            Optional<UUID> target = boundedCandidates.stream()
                    .filter(c -> !claimed.contains(c) && !engaged.contains(c))
                    .findFirst();
            target.ifPresent(t -> {
                claimed.add(t);
                engaged.add(t);
                Assignment a = new Assignment(actor, t, threatScore.applyAsDouble(t));
                assignments.put(actor, a);
                result.add(a);
            });
        }
        // Members without a target keep any previous assignment until retargeted;
        // actors no longer in the squad release theirs — otherwise despawned
        // members would accumulate in the assignment map forever.
        assignments.keySet().removeIf(actor -> !squad.contains(actor));
        return List.copyOf(result);
    }

    /** Release the actor's claim (target lost or actor despawned). */
    public Optional<Assignment> release(UUID actorId) {
        return Optional.ofNullable(assignments.remove(actorId));
    }

    public Optional<Assignment> assignmentOf(UUID actorId) {
        return Optional.ofNullable(assignments.get(actorId));
    }

    /** Actors holding assignments whose target is not in the live candidate set. */
    public List<UUID> unassigned(java.util.Set<UUID> liveCandidates) {
        return assignments.entrySet().stream()
                .filter(e -> !liveCandidates.contains(e.getValue().targetId()))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
    }

    public Map<UUID, Assignment> assignments() {
        return Map.copyOf(assignments);
    }
}
