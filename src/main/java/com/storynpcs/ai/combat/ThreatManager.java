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

    private final Map<UUID, StrikeRecord> strikes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> threatTable = new ConcurrentHashMap<>();
    private UUID currentTarget = null;
    private int aggroTimer = 0;

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

    public void addThreat(UUID targetUuid, int amount) {
        if (targetUuid == null || amount <= 0) return;
        threatTable.merge(targetUuid, amount, (a, b) -> (int) Math.min(100_000, (long) a + b));
        this.aggroTimer = Math.max(this.aggroTimer, 400); // 20s minimum
        recalculateTarget();
    }

    public void recalculateTarget() {
        if (threatTable.isEmpty()) {
            this.currentTarget = null;
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
        this.currentTarget = highest;
    }

    public void tick(int decayRate) {
        if (threatTable.isEmpty()) {
            this.currentTarget = null;
            this.aggroTimer = 0;
            return;
        }

        if (aggroTimer > 0) {
            aggroTimer--;
        }

        if (aggroTimer == 0) {
            // Decay threat when timer expires
            threatTable.replaceAll((k, v) -> Math.max(0, v - decayRate));
            threatTable.entrySet().removeIf(e -> e.getValue() <= 0);
            recalculateTarget();
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
                recalculateTarget();
            }
        }
    }

    public void clearAll() {
        strikes.clear();
        threatTable.clear();
        currentTarget = null;
        aggroTimer = 0;
    }

    public Map<UUID, Integer> getThreatTable() {
        return Collections.unmodifiableMap(threatTable);
    }
}
