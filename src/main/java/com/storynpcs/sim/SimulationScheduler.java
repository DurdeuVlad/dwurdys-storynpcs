package com.storynpcs.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assigns simulation tiers to actors deterministically (sorted by actor id),
 * records observable {@link TierTransition}s, and gates per-capability work via
 * {@link TierBudgets}. DORMANT actors keep durable state with no live world
 * references — the scheduler never calls back into the world.
 */
public final class SimulationScheduler {

    public enum Capability { SENSING, PATHING, ANIMATION, COMBAT, PERSISTENCE }

    public record ActorInput(UUID actorId, double distanceBlocks, boolean inCombat) {}

    public record TierTransition(UUID actorId, SimulationTier from, SimulationTier to, String reason) {}

    public record ActorSimulationState(UUID actorId, SimulationTier tier, boolean inCombat) {
        public boolean dormant() {
            return tier == SimulationTier.DORMANT || tier == SimulationTier.UNLOADED;
        }
    }

    private final SimulationTierPolicy policy;
    private final TierBudgets budgets;
    public static final int MAX_TRANSITION_HISTORY = 4_096;

    private final Map<UUID, ActorSimulationState> states = new LinkedHashMap<>();
    private final Deque<TierTransition> transitions = new ArrayDeque<>();

    public SimulationScheduler(SimulationTierPolicy policy, TierBudgets budgets) {
        this.policy = policy;
        this.budgets = budgets;
    }

    /** Re-evaluate tiers for all actors. Transitions are recorded in sorted-id order for determinism. */
    public List<TierTransition> evaluate(List<ActorInput> actors) {
        List<ActorInput> sorted = actors.stream()
                .sorted(Comparator.comparing(a -> a.actorId().toString()))
                .toList();
        List<TierTransition> fresh = new ArrayList<>();
        for (ActorInput actor : sorted) {
            SimulationTier next = policy.tierFor(actor.distanceBlocks(), actor.inCombat());
            ActorSimulationState prev = states.get(actor.actorId());
            if (prev == null) {
                states.put(actor.actorId(), new ActorSimulationState(actor.actorId(), next, actor.inCombat()));
                fresh.add(new TierTransition(actor.actorId(), null, next, "SPAWNED"));
            } else if (prev.tier() != next) {
                states.put(actor.actorId(), new ActorSimulationState(actor.actorId(), next, actor.inCombat()));
                String reason = actor.inCombat() && prev.tier().ordinal() > SimulationTier.DISTANT.ordinal()
                        ? "COMBAT_ENGAGE" : "DISTANCE";
                fresh.add(new TierTransition(actor.actorId(), prev.tier(), next, reason));
            } else if (prev.inCombat() != actor.inCombat()) {
                states.put(actor.actorId(), new ActorSimulationState(actor.actorId(), next, actor.inCombat()));
            }
        }
        // Actors not reported this evaluation are treated as unloaded.
        Set<UUID> reported = new HashSet<>();
        sorted.forEach(actor -> reported.add(actor.actorId()));
        for (UUID existing : List.copyOf(states.keySet())) {
            if (!reported.contains(existing)) {
                ActorSimulationState prev = states.remove(existing);
                if (prev.tier() != SimulationTier.UNLOADED) {
                    fresh.add(new TierTransition(existing, prev.tier(), SimulationTier.UNLOADED, "DESPAWNED"));
                }
            }
        }
        transitions.addAll(fresh);
        while (transitions.size() > MAX_TRANSITION_HISTORY) {
            transitions.removeFirst();
        }
        return List.copyOf(fresh);
    }

    /** Deterministic capability gating: runs on ticks where {@code tick % period == 0}; -1 disables. */
    public boolean shouldRun(UUID actorId, Capability capability, long tick) {
        ActorSimulationState state = states.get(actorId);
        if (state == null || state.tier() == SimulationTier.UNLOADED) {
            return false;
        }
        int period = period(state.tier(), capability);
        return period > 0 && tick % period == 0;
    }

    private int period(SimulationTier tier, Capability capability) {
        TierBudgets.Budget b = budgets.forTier(tier);
        return switch (capability) {
            case SENSING -> b.sensingTicks();
            case PATHING -> b.pathingTicks();
            case ANIMATION -> b.animationTicks();
            case COMBAT -> b.combatEvalTicks();
            case PERSISTENCE -> b.persistenceTicks();
        };
    }

    public ActorSimulationState stateOf(UUID actorId) {
        return states.get(actorId);
    }

    public Map<UUID, ActorSimulationState> states() {
        return Map.copyOf(states);
    }

    public List<TierTransition> transitions() {
        return List.copyOf(transitions);
    }

    public Map<SimulationTier, Integer> tierCounts() {
        Map<SimulationTier, Integer> counts = new EnumMap<>(SimulationTier.class);
        for (SimulationTier t : SimulationTier.values()) {
            counts.put(t, 0);
        }
        for (ActorSimulationState s : states.values()) {
            counts.merge(s.tier(), 1, Integer::sum);
        }
        return counts;
    }
}
