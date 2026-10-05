package com.storynpcs.domain.companion;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcInventory;
import com.storynpcs.domain.npc.TacticalStance;

/**
 * Data-driven companion configuration (P6-5): wages, stages, talents,
 * inventory, stance, and owner-lifecycle policy.
 */
public class CompanionProfile {

    public enum InsufficientFundsPolicy { PAUSE_SERVICE, DISMISS, KEEP_ANYWAY }

    public enum UnloadPolicy { PAUSE, DESPAWN, FOLLOW_OWNER_OFFLINE }

    @JsonProperty
    private int wageIntervalTicks = 24_000; // one Minecraft day

    @JsonProperty
    private int wageAmount = 0;

    @JsonProperty
    private InsufficientFundsPolicy insufficientFundsPolicy = InsufficientFundsPolicy.PAUSE_SERVICE;

    @JsonProperty
    private UnloadPolicy unloadPolicy = UnloadPolicy.PAUSE;

    @JsonProperty
    private TacticalStance stance = TacticalStance.GUARD;

    @JsonProperty
    private List<CompanionStage> stages = new ArrayList<>();

    @JsonProperty
    private List<Talent> talents = new ArrayList<>();

    /** Companion inventory uses the P3-4 container contract. */
    @JsonProperty
    private NpcInventory inventory = new NpcInventory();

    public CompanionProfile() {}

    public int getWageIntervalTicks() { return wageIntervalTicks; }
    public void setWageIntervalTicks(int wageIntervalTicks) {
        if (wageIntervalTicks < 20 || wageIntervalTicks > 2_400_000) {
            throw new IllegalArgumentException("wageIntervalTicks must be in [20,2400000]");
        }
        this.wageIntervalTicks = wageIntervalTicks;
    }

    public int getWageAmount() { return wageAmount; }
    public void setWageAmount(int wageAmount) {
        if (wageAmount < 0) {
            throw new IllegalArgumentException("wageAmount must be >= 0");
        }
        this.wageAmount = wageAmount;
    }

    public InsufficientFundsPolicy getInsufficientFundsPolicy() { return insufficientFundsPolicy; }
    public void setInsufficientFundsPolicy(InsufficientFundsPolicy p) {
        this.insufficientFundsPolicy = p == null ? InsufficientFundsPolicy.PAUSE_SERVICE : p;
    }

    public UnloadPolicy getUnloadPolicy() { return unloadPolicy; }
    public void setUnloadPolicy(UnloadPolicy unloadPolicy) {
        this.unloadPolicy = unloadPolicy == null ? UnloadPolicy.PAUSE : unloadPolicy;
    }

    public TacticalStance getStance() { return stance; }
    public void setStance(TacticalStance stance) { this.stance = stance; }

    public List<CompanionStage> getStages() { return List.copyOf(stages); }
    public void setStages(List<CompanionStage> stages) {
        if (stages != null && stages.size() > 16) {
            throw new IllegalArgumentException("stages cannot exceed 16");
        }
        this.stages = stages == null ? new ArrayList<>() : new ArrayList<>(stages);
    }

    public List<Talent> getTalents() { return List.copyOf(talents); }
    public void setTalents(List<Talent> talents) {
        if (talents != null && talents.size() > 32) {
            throw new IllegalArgumentException("talents cannot exceed 32");
        }
        this.talents = talents == null ? new ArrayList<>() : new ArrayList<>(talents);
    }

    public NpcInventory getInventory() { return inventory; }
    public void setInventory(NpcInventory inventory) {
        this.inventory = inventory == null ? new NpcInventory() : inventory;
    }

    /** The stage active at a given companion age, or null when none qualify. */
    public CompanionStage activeStage(long ageTicks) {
        CompanionStage best = null;
        for (CompanionStage s : stages) {
            if (ageTicks >= s.getMinAgeTicks() && (best == null || s.getMinAgeTicks() > best.getMinAgeTicks())) {
                best = s;
            }
        }
        return best;
    }

    /** A named progression stage with bounded stat multipliers. */
    public static class CompanionStage {
        @JsonProperty(required = true)
        private NamespacedId id;
        @JsonProperty
        private long minAgeTicks;
        @JsonProperty
        private double statMultiplier = 1.0;
        /**
         * How many authored talents take effect while this stage is active —
         * the bounded-effects rule. Talents beyond the slot cap are inert, not
         * silently stacked.
         */
        @JsonProperty
        private int talentSlots = 2;

        public CompanionStage() {}
        public CompanionStage(NamespacedId id, long minAgeTicks, double statMultiplier) {
            this.id = id;
            setMinAgeTicks(minAgeTicks);
            setStatMultiplier(statMultiplier);
        }

        public NamespacedId getId() { return id; }
        public void setId(NamespacedId id) { this.id = id; }
        public long getMinAgeTicks() { return minAgeTicks; }
        public void setMinAgeTicks(long minAgeTicks) {
            if (minAgeTicks < 0) throw new IllegalArgumentException("minAgeTicks must be >= 0");
            this.minAgeTicks = minAgeTicks;
        }
        public double getStatMultiplier() { return statMultiplier; }
        public void setStatMultiplier(double statMultiplier) {
            if (statMultiplier < 0.1 || statMultiplier > 10.0) {
                throw new IllegalArgumentException("statMultiplier must be in [0.1,10]");
            }
            this.statMultiplier = statMultiplier;
        }
        public int getTalentSlots() { return talentSlots; }
        public void setTalentSlots(int talentSlots) {
            if (talentSlots < 0 || talentSlots > 8) {
                throw new IllegalArgumentException("talentSlots must be in [0,8]");
            }
            this.talentSlots = talentSlots;
        }
    }

    /**
     * A bounded companion talent. {@code effect} is optional — a talent with
     * no effect is flavor/rank only; one with an effect contributes its rank
     * (≤ maxRank ≤ 10) toward the bounded bonus for that
     * {@link CompanionEffectType} while a stage slot is available.
     */
    public static class Talent {
        @JsonProperty(required = true)
        private NamespacedId id;
        @JsonProperty
        private int rank = 1;
        @JsonProperty
        private int maxRank = 3;
        @JsonProperty
        private CompanionEffectType effect;

        public Talent() {}
        public Talent(NamespacedId id, int rank, int maxRank) {
            this.id = id;
            setMaxRank(maxRank);
            setRank(rank);
        }

        public NamespacedId getId() { return id; }
        public void setId(NamespacedId id) { this.id = id; }
        public int getRank() { return rank; }
        public void setRank(int rank) {
            if (rank < 1 || rank > maxRank) {
                throw new IllegalArgumentException("rank must be in [1," + maxRank + "]");
            }
            this.rank = rank;
        }
        public int getMaxRank() { return maxRank; }
        public void setMaxRank(int maxRank) {
            if (maxRank < 1 || maxRank > 10) {
                throw new IllegalArgumentException("maxRank must be in [1,10]");
            }
            this.maxRank = maxRank;
        }
        public CompanionEffectType getEffect() { return effect; }
        public void setEffect(CompanionEffectType effect) { this.effect = effect; }
    }
}
