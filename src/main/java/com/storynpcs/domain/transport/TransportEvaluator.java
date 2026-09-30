package com.storynpcs.domain.transport;

import java.util.Objects;
import java.util.Set;

import com.storynpcs.domain.common.NamespacedId;

/**
 * Server-authoritative transfer evaluation (P6-3). Every failure mode is
 * explicit: an invalid/unloaded/dangerous/locked destination fails BEFORE any
 * fee is charged. Cross-dimension transfers carry a timeout/recovery policy.
 */
public final class TransportEvaluator {

    public enum RejectReason {
        DESTINATION_UNKNOWN,
        DESTINATION_UNLOADED,
        DESTINATION_UNSAFE,
        DESTINATION_LOCKED,
        FEE_UNMET,
        DIMENSION_UNAVAILABLE
    }

    public sealed interface Evaluation {
        record Approved(TransportLocation location, int fee) implements Evaluation {}
        record Rejected(RejectReason reason, String detail) implements Evaluation {}
    }

    /** Cross-dimension transfer policy: bounded wait then recover instead of hanging. */
    public record DimensionPolicy(int transferTimeoutTicks, Recovery recovery) {
        public enum Recovery { RETURN_TO_ORIGIN, ABORT }
        public DimensionPolicy {
            if (transferTimeoutTicks <= 0) {
                throw new IllegalArgumentException("transferTimeoutTicks must be positive");
            }
            if (recovery == null) {
                throw new IllegalArgumentException("recovery is required");
            }
        }
        public static DimensionPolicy defaults() {
            return new DimensionPolicy(100, Recovery.RETURN_TO_ORIGIN);
        }
    }

    /** Facts the evaluator needs — supplied by the server, never the client. */
    public record DestinationFacts(
            boolean loaded,
            boolean safe,
            boolean dimensionAvailable) {}

    public Evaluation evaluate(TransportLocation location,
                               Set<NamespacedId> unlockedLocations,
                               boolean conditionsMet,
                               int playerBalance,
                               DestinationFacts facts) {
        Objects.requireNonNull(facts, "facts");
        if (location == null) {
            return new Evaluation.Rejected(RejectReason.DESTINATION_UNKNOWN, "no location");
        }
        if (unlockedLocations != null
                && !unlockedLocations.contains(location.getId())
                && !location.getUnlockConditions().isEmpty()
                && !conditionsMet) {
            return new Evaluation.Rejected(RejectReason.DESTINATION_LOCKED,
                    "unlock conditions not met for '" + location.getId() + "'");
        }
        if (!facts.dimensionAvailable()) {
            return new Evaluation.Rejected(RejectReason.DIMENSION_UNAVAILABLE,
                    "dimension '" + location.getDimensionId() + "' unavailable");
        }
        if (!facts.loaded()) {
            return new Evaluation.Rejected(RejectReason.DESTINATION_UNLOADED,
                    "destination chunk not loaded");
        }
        if (!facts.safe()) {
            return new Evaluation.Rejected(RejectReason.DESTINATION_UNSAFE,
                    "destination is not safe to receive a player");
        }
        if (playerBalance < location.getFee()) {
            return new Evaluation.Rejected(RejectReason.FEE_UNMET,
                    "fee " + location.getFee() + " exceeds balance " + playerBalance);
        }
        return new Evaluation.Approved(location, location.getFee());
    }

    /** Player UI listing: only unlocked or explicitly visible-when-locked destinations. */
    public java.util.List<TransportLocation> visibleFor(java.util.List<TransportLocation> locations,
                                                        Set<NamespacedId> unlocked) {
        return locations.stream()
                .filter(l -> l.isVisibleWhenLocked()
                        || unlocked == null
                        || unlocked.contains(l.getId())
                        || l.getUnlockConditions().isEmpty())
                .toList();
    }
}
