package com.storynpcs.domain.companion;

import java.util.UUID;

/**
 * Exactly-once wage charging (P6-5). Each wage period is charged at most once —
 * the period index is persisted so a crash mid-charge resumes without double-
 * charging, and a completed charge is never replayed.
 */
public final class WageLedger {

    public enum ChargeOutcome { CHARGED, ALREADY_CHARGED, INSUFFICIENT_FUNDS }

    private long lastChargedPeriod = -1;

    public long getLastChargedPeriod() { return lastChargedPeriod; }
    public void setLastChargedPeriod(long p) { this.lastChargedPeriod = p; }

    /** Current wage period for a hire timestamp under the configured interval. */
    public static long periodFor(long hireTick, long nowTick, int intervalTicks) {
        return Math.max(0, (nowTick - hireTick) / intervalTicks);
    }

    /**
     * Attempt the charge for the current period. Exactly-once: a period already
     * charged returns ALREADY_CHARGED without consulting the payer — a durable
     * commit marks the period before/with the charge.
     */
    public ChargeOutcome charge(long period, int wageAmount, Payer payer, UUID companionId) {
        if (period <= lastChargedPeriod) {
            return ChargeOutcome.ALREADY_CHARGED;
        }
        if (wageAmount <= 0) {
            lastChargedPeriod = period; // free period — still exactly-once
            return ChargeOutcome.CHARGED;
        }
        if (!payer.tryPay(companionId, wageAmount)) {
            return ChargeOutcome.INSUFFICIENT_FUNDS;
        }
        lastChargedPeriod = period;
        return ChargeOutcome.CHARGED;
    }

    /** Payment sink — implemented by the canonical economy service. */
    public interface Payer {
        boolean tryPay(UUID companionId, int amount);
    }
}
