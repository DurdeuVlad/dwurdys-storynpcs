package com.storynpcs.ai.combat;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages combat aggression, threat tables, and accidental hit tolerance.
 */
public class ThreatManager {

    public enum CombatReaction {
        TOLERATED_WARN,
        ENGAGE_RETALIATE
    }

    public record StrikeRecord(int count, long lastTick) {}

    /** Receives (target, isAggro, reason) whenever the engaged target changes. */
    @FunctionalInterface
    public interface AggroEventSink {
        void onAggroChange(UUID targetUuid, boolean isAggro, String reason);
    }

    private final Map<UUID, StrikeRecord> strikes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> threatTable = new ConcurrentHashMap<>();
    private UUID currentTarget = null;
    private int aggroTimer = 0;
    // Authored calm-down window (NpcAi.aggroDurationTicks); the entity applies
    // it per definition refresh. Floor matches NpcAi's own lower bound.
    private int aggroDurationTicks = 400;
    private volatile AggroEventSink aggroEventSink;

    /** Optional sink for target-change reasons; a null sink keeps prior silent behavior. */
    public void setAggroEventSink(AggroEventSink sink) {
        this.aggroEventSink = sink;
    }

    private void setCurrentTarget(UUID target, String reason) {
        UUID previous = this.currentTarget;
        this.currentTarget = target;
        AggroEventSink sink = this.aggroEventSink;
        if (sink != null && !java.util.Objects.equals(previous, target)) {
            // Disengagements report the entity being released; engagements the new target.
            sink.onAggroChange(target != null ? target : previous, target != null, reason);
        }
    }

    /**
     * Evaluates an incoming hit against the strike tolerance window.
     *
     * @param attackerUuid    The attacking entity UUID
     * @param currentTick     Current game/world tick
     * @param strikeTolerance Number of accidental hits tolerated before retaliation
     * @param windowTicks     Time window in ticks before strike count decays
     * @return TOLERATED_WARN if hit was within tolerance, ENGAGE_RETALIATE if exceeded
     */
    public CombatReaction evaluateHit(UUID attackerUuid, long currentTick, int strikeTolerance, int windowTicks) {
        if (attackerUuid == null) {
            return CombatReaction.ENGAGE_RETALIATE;
        }

        StrikeRecord current = strikes.get(attackerUuid);
        int newCount = 1;

        if (current != null) {
            long delta = currentTick - current.lastTick();
            if (delta <= windowTicks) {
                newCount = current.count() + 1;
            }
        }

        strikes.put(attackerUuid, new StrikeRecord(newCount, currentTick));

        if (strikes.size() > 20) {
            long threshold = currentTick - (long) windowTicks * 3L;
            strikes.entrySet().removeIf(e -> e.getValue().lastTick() < threshold);
        }

        if (newCount <= strikeTolerance) {
            return CombatReaction.TOLERATED_WARN;
        } else {
            addThreat(attackerUuid, 100);
            return CombatReaction.ENGAGE_RETALIATE;
        }
    }

    /**
     * Sets the authored calm-down duration (game ticks) — a hit keeps the NPC
     * hostile for at least this long after the last threat write. A shorter
     * authored duration clamps a running timer (to a fresh window of the new
     * duration, not elapsed-since-write); a longer one leaves the current
     * window untouched until the next threat write re-arms it.
     */
    public void setAggroDurationTicks(int ticks) {
        this.aggroDurationTicks = Math.max(40, ticks);
        this.aggroTimer = Math.min(this.aggroTimer, this.aggroDurationTicks);
    }

    public int getAggroDurationTicks() {
        return aggroDurationTicks;
    }

    public void addThreat(UUID targetUuid, int amount) {
        if (targetUuid == null || amount <= 0) return;
        threatTable.merge(targetUuid, amount, (a, b) -> (int) Math.min(100_000, (long) a + b));
        this.aggroTimer = Math.max(this.aggroTimer, aggroDurationTicks);
        recalculateTarget();
    }

    public void recalculateTarget() {
        recalculateTarget("THREAT_PRIORITY_CHANGE");
    }

    private void recalculateTarget(String reason) {
        if (threatTable.isEmpty()) {
            setCurrentTarget(null, reason);
            return;
        }
        UUID highest = null;
        int max = 0;
        for (Map.Entry<UUID, Integer> entry : threatTable.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                highest = entry.getKey();
            }
        }
        setCurrentTarget(highest, reason);
    }

    /**
     * Advances the calm-down clock. {@code elapsedTicks} is the number of real
     * game ticks since the previous call — the entity drives this on a
     * seconds-cadence sensing schedule, so decrementing by one per invocation
     * would stretch the authored duration ~20x. Decay still applies once per
     * call after the window expires (by design — decay is a coarse pulse).
     */
    public void tick(int decayRate, int elapsedTicks) {
        if (threatTable.isEmpty()) {
            setCurrentTarget(null, "THREAT_CLEARED");
            this.aggroTimer = 0;
            return;
        }

        if (aggroTimer > 0) {
            aggroTimer = Math.max(0, aggroTimer - Math.max(1, elapsedTicks));
        }

        if (aggroTimer == 0) {
            // Decay threat when timer expires
            threatTable.replaceAll((k, v) -> Math.max(0, v - decayRate));
            threatTable.entrySet().removeIf(e -> e.getValue() <= 0);
            recalculateTarget("THREAT_DECAYED");
        }
    }

    public Optional<UUID> getCurrentTarget() {
        return Optional.ofNullable(currentTarget);
    }

    public int getStrikes(UUID attackerUuid) {
        StrikeRecord record = strikes.get(attackerUuid);
        return record != null ? record.count() : 0;
    }

    public void forgive(UUID targetUuid) {
        if (targetUuid != null) {
            strikes.remove(targetUuid);
            threatTable.remove(targetUuid);
            if (targetUuid.equals(currentTarget)) {
                recalculateTarget("TARGET_FORGIVEN");
            }
        }
    }

    public void clearAll() {
        strikes.clear();
        threatTable.clear();
        setCurrentTarget(null, "THREAT_CLEARED");
        aggroTimer = 0;
    }

    public Map<UUID, Integer> getThreatTable() {
        return Collections.unmodifiableMap(threatTable);
    }
}
