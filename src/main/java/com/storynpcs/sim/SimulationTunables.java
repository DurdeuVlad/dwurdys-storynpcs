package com.storynpcs.sim;

import com.storynpcs.admin.RuntimeTunables;
import com.storynpcs.admin.RuntimeTunablesView;
import java.util.EnumMap;
import java.util.Locale;

/**
 * P4-1 (#62) live-resolved simulation policy and budgets. Wraps the bounded
 * {@link RuntimeTunablesView} so a committed config transaction takes effect
 * on the next use — the same "consumers resolve values per call" contract as
 * the script/schematic tunables — without re-parsing the map every tick:
 * resolved objects are cached on the view's revision and rebuilt only when a
 * commit lands. All keys carry validated bounds; an out-of-band hand edit
 * falls back to the key default via {@code longValue}.
 */
public final class SimulationTunables {

    private final RuntimeTunablesView view;

    private long resolvedRevision = Long.MIN_VALUE;
    private SimulationTierPolicy policy = SimulationTierPolicy.defaults();
    private TierBudgets budgets = TierBudgets.defaults();

    public SimulationTunables(RuntimeTunablesView view) {
        this.view = view;
    }

    public synchronized SimulationTierPolicy policy() {
        resolveIfStale();
        return policy;
    }

    public synchronized TierBudgets budgets() {
        resolveIfStale();
        return budgets;
    }

    private void resolveIfStale() {
        long revision = view != null ? view.revision() : 0L;
        if (revision == resolvedRevision) {
            return;
        }
        try {
            policy = new SimulationTierPolicy(
                    band(RuntimeTunables.SIM_BAND_ACTIVE, 64L),
                    band(RuntimeTunables.SIM_BAND_NEARBY, 128L),
                    band(RuntimeTunables.SIM_BAND_DISTANT, 256L),
                    band(RuntimeTunables.SIM_BAND_DORMANT, 512L));
        } catch (IllegalArgumentException corrupt) {
            // Committed values always satisfy ordering (validate rejects the
            // commit otherwise); this only fires on a corrupt store, which
            // must not crash the server tick — keep the previous policy.
        }
        EnumMap<SimulationTier, TierBudgets.Budget> map = new EnumMap<>(SimulationTier.class);
        for (SimulationTier tier : SimulationTier.values()) {
            String name = tier.name().toLowerCase(Locale.ROOT);
            map.put(tier, new TierBudgets.Budget(
                    period(name, "sensing", budgets.forTier(tier).sensingTicks()),
                    period(name, "pathing", budgets.forTier(tier).pathingTicks()),
                    period(name, "animation", budgets.forTier(tier).animationTicks()),
                    period(name, "combat", budgets.forTier(tier).combatEvalTicks()),
                    period(name, "persistence", budgets.forTier(tier).persistenceTicks())));
        }
        budgets = new TierBudgets(map);
        resolvedRevision = revision;
    }

    private double band(String key, double fallback) {
        if (view == null) {
            return fallback;
        }
        long v = view.longValue(key);
        // longValue already applies the key default on parse failure — an
        // out-of-order commit can't exist (validate rejects it), but a
        // belt-and-suspenders clamp keeps a corrupt store from crashing the
        // policy record invariant.
        return Math.max(1.0, v);
    }

    private int period(String tier, String capability, int fallback) {
        if (view == null) {
            return fallback;
        }
        long v = view.longValue(RuntimeTunables.simBudgetKey(tier, capability));
        // Budget contract: -1 disables, >=1 is a tick period; 0 is invalid.
        return v == 0 ? fallback : (int) Math.min(Integer.MAX_VALUE, v);
    }
}
