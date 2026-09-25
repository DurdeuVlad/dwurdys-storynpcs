package com.storynpcs.domain.companion;

/**
 * Pure wage-scheduling math for companion hiring (issue #74). Mirrors
 * {@code com.storynpcs.domain.quest.QuestRepeatPolicy}'s pure-function style.
 *
 * <p><b>Exactly-once and recoverable, by construction:</b> {@link #periodsOwed}
 * is a pure function of {@code (lastPaidAtEpochMillis, nowEpochMillis,
 * periodMillis)} — calling it any number of times with the same inputs always
 * returns the same answer, so nothing is ever double-counted from repeated
 * calls alone. The caller is responsible for advancing its stored
 * "last paid" timestamp by exactly {@code periodsPaid * periodMillis} via
 * {@link #advancePaidThrough} — never by jumping straight to "now" — so a
 * server that was offline through several periods pays exactly the periods
 * it owes on the next tick (catch-up/recovery), never more, never fewer, and
 * never the same period twice.
 */
public final class CompanionWagePolicy {

    private CompanionWagePolicy() {}

    /**
     * How many full wage periods have elapsed since the last payment. Zero if
     * {@code periodMillis} is non-positive (no charge schedule) or if less
     * than one full period has passed.
     */
    public static long periodsOwed(long lastPaidAtEpochMillis, long nowEpochMillis, long periodMillis) {
        if (periodMillis <= 0) return 0L;
        long elapsed = nowEpochMillis - lastPaidAtEpochMillis;
        if (elapsed <= 0) return 0L;
        return elapsed / periodMillis;
    }

    /**
     * The new "last paid" timestamp after successfully charging
     * {@code periodsPaid} periods. Always {@code lastPaidAtEpochMillis +
     * periodsPaid * periodMillis} — never {@code nowEpochMillis} — so a late
     * catch-up payment does not silently absorb partial progress toward the
     * next period.
     */
    public static long advancePaidThrough(long lastPaidAtEpochMillis, long periodsPaid, long periodMillis) {
        if (periodsPaid < 0) {
            throw new IllegalArgumentException("periodsPaid must not be negative");
        }
        return lastPaidAtEpochMillis + periodsPaid * periodMillis;
    }
}
