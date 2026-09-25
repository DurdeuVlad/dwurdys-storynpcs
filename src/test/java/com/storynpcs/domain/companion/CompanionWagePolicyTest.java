package com.storynpcs.domain.companion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanionWagePolicyTest {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    @Test
    void noPeriodsOwedBeforeAFullPeriodElapses() {
        assertThat(CompanionWagePolicy.periodsOwed(0L, DAY_MILLIS - 1, DAY_MILLIS)).isZero();
    }

    @Test
    void exactlyOnePeriodOwedAtTheBoundary() {
        assertThat(CompanionWagePolicy.periodsOwed(0L, DAY_MILLIS, DAY_MILLIS)).isEqualTo(1L);
    }

    @Test
    void catchUpPaysExactlyTheMissedPeriodsAfterBeingOfflineForSeveral() {
        long lastPaid = 0L;
        long now = DAY_MILLIS * 5 + 12345; // 5 full periods plus a partial one
        assertThat(CompanionWagePolicy.periodsOwed(lastPaid, now, DAY_MILLIS)).isEqualTo(5L);
    }

    @Test
    void callingPeriodsOwedRepeatedlyWithTheSameInputsNeverDoubleCounts() {
        long first = CompanionWagePolicy.periodsOwed(0L, DAY_MILLIS * 3, DAY_MILLIS);
        long second = CompanionWagePolicy.periodsOwed(0L, DAY_MILLIS * 3, DAY_MILLIS);
        assertThat(first).isEqualTo(second).isEqualTo(3L);
    }

    @Test
    void advancingPaidThroughLeavesPartialProgressTowardTheNextPeriodIntact() {
        long lastPaid = 0L;
        long now = DAY_MILLIS * 2 + 500; // 2 full periods + 500ms into the third
        long owed = CompanionWagePolicy.periodsOwed(lastPaid, now, DAY_MILLIS);
        long newLastPaid = CompanionWagePolicy.advancePaidThrough(lastPaid, owed, DAY_MILLIS);

        assertThat(owed).isEqualTo(2L);
        assertThat(newLastPaid).isEqualTo(DAY_MILLIS * 2);
        // Immediately re-checking owes nothing new yet -- the 500ms partial progress was preserved, not reset to "now".
        assertThat(CompanionWagePolicy.periodsOwed(newLastPaid, now, DAY_MILLIS)).isZero();
    }

    @Test
    void nonPositivePeriodMillisOwesNothing() {
        assertThat(CompanionWagePolicy.periodsOwed(0L, DAY_MILLIS, 0L)).isZero();
        assertThat(CompanionWagePolicy.periodsOwed(0L, DAY_MILLIS, -1L)).isZero();
    }

    @Test
    void negativePeriodsPaidIsRejected() {
        assertThatThrownBy(() -> CompanionWagePolicy.advancePaidThrough(0L, -1L, DAY_MILLIS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
