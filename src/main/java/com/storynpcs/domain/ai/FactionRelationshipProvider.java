package com.storynpcs.domain.ai;

import com.storynpcs.domain.common.NamespacedId;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic faction-relationship contract used by targeting policy. The
 * provider is symmetric (a pair resolves identically in either direction) and
 * every unknown pair resolves to {@link Relationship#NEUTRAL} — never hostile by
 * accident and never dependent on load order. P6-1 supplies the full data-driven
 * matrix against this contract.
 */
public interface FactionRelationshipProvider {

    enum Relationship {
        HOSTILE,
        NEUTRAL,
        FRIENDLY
    }

    /**
     * Resolves the authored relationship from {@code source} toward {@code target}.
     * Must be total: any pair of faction ids — known or not — returns a value.
     */
    Relationship relationship(NamespacedId source, NamespacedId target);

    /** A provider with no authored pairs; every relationship is NEUTRAL. */
    static FactionRelationshipProvider neutral() {
        return (source, target) -> Relationship.NEUTRAL;
    }

    /**
     * Builds the provider from every faction's authored relationship matrix.
     * The matrix is symmetric at lookup time, so conflicting declarations
     * between two factions resolve to the MORE hostile standing — a faction
     * that declares another hostile is correctly met with hostility. Factions
     * are processed in sorted-id order so the merge never depends on load
     * order; unknown pairs resolve NEUTRAL.
     */
    static FactionRelationshipProvider fromFactions(
            java.util.Collection<com.storynpcs.domain.faction.Faction> factions) {
        Map<PairKey, Relationship> pairs = new HashMap<>();
        if (factions != null) {
            var ordered = factions.stream()
                    .filter(f -> f != null && f.getId() != null)
                    .sorted(java.util.Comparator.comparing(f -> f.getId().toString()))
                    .toList();
            for (var faction : ordered) {
                for (var entry : faction.getRelationships().entrySet()) {
                    NamespacedId other;
                    try {
                        other = NamespacedId.of(entry.getKey());
                    } catch (RuntimeException bad) {
                        continue; // malformed relationship key — skipped, never hostile
                    }
                    Relationship declared;
                    try {
                        declared = Relationship.valueOf(entry.getValue());
                    } catch (RuntimeException bad) {
                        continue; // unknown standing — skipped, never hostile
                    }
                    var key = PairKey.of(faction.getId(), other);
                    pairs.merge(key, declared, (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
                }
            }
        }
        return of(pairs);
    }

    /**
     * Builds a symmetric map-backed provider. Duplicate pair declarations with a
     * conflicting value fail fast; unknown pairs resolve to NEUTRAL.
     */
    static FactionRelationshipProvider of(Map<PairKey, Relationship> relationships) {
        Map<PairKey, Relationship> copy = new HashMap<>();
        if (relationships != null) {
            for (Map.Entry<PairKey, Relationship> entry : relationships.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("relationship entries cannot be null");
                }
                copy.put(PairKey.of(entry.getKey().first(), entry.getKey().second()), entry.getValue());
            }
        }
        return (source, target) -> {
            if (source == null || target == null) return Relationship.NEUTRAL;
            if (source.equals(target)) return Relationship.FRIENDLY;
            return copy.getOrDefault(PairKey.of(source, target), Relationship.NEUTRAL);
        };
    }

    /** Unordered faction pair; symmetric lookups resolve identically either way. */
    record PairKey(NamespacedId first, NamespacedId second) {
        public PairKey {
            if (first == null || second == null) {
                throw new IllegalArgumentException("pair key factions cannot be null");
            }
        }

        public static PairKey of(NamespacedId a, NamespacedId b) {
            if (a == null || b == null) {
                throw new IllegalArgumentException("pair key factions cannot be null");
            }
            return a.compareTo(b) <= 0 ? new PairKey(a, b) : new PairKey(b, a);
        }
    }
}
