package com.storynpcs.domain.quest;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuestRepeatPolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private long at(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, UTC).toInstant().toEpochMilli();
    }

    @Test
    void onceNeverRestartsRegardlessOfElapsedTime() {
        long completedLongAgo = at(2000, 1, 1, 0);
        long farFuture = at(2100, 1, 1, 0);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.ONCE, completedLongAgo, farFuture, UTC)).isFalse();
    }

    @Test
    void onceWithZeroLegacyTimestampStillNeverRestarts() {
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.ONCE, 0L, at(2026, 1, 1, 0), UTC)).isFalse();
    }

    @Test
    void repeatableAlwaysRestartsImmediately() {
        long completedNow = at(2026, 1, 1, 12);
        long secondLater = completedNow + 1000;
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.REPEATABLE, completedNow, secondLater, UTC)).isTrue();
    }

    @Test
    void instantAlwaysRestartsImmediately() {
        long completedNow = at(2026, 1, 1, 12);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.INSTANT, completedNow, completedNow, UTC)).isTrue();
    }

    @Test
    void dailyBlocksWithinTheSameServerDay() {
        long completedMorning = at(2026, 3, 10, 1);
        long sameDayEvening = at(2026, 3, 10, 23);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.DAILY, completedMorning, sameDayEvening, UTC)).isFalse();
    }

    @Test
    void dailyAllowsOnceTheServerDayBoundaryPasses() {
        long completedLateNight = at(2026, 3, 10, 23);
        long nextDayJustAfterMidnight = at(2026, 3, 11, 0);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.DAILY, completedLateNight, nextDayJustAfterMidnight, UTC)).isTrue();
    }

    @Test
    void weeklyBlocksWithinTheSameIsoWeek() {
        // 2026-03-10 is a Tuesday; 2026-03-13 is the Friday of the same ISO week.
        long completedTuesday = at(2026, 3, 10, 8);
        long sameWeekFriday = at(2026, 3, 13, 8);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.WEEKLY, completedTuesday, sameWeekFriday, UTC)).isFalse();
    }

    @Test
    void weeklyAllowsOnceTheIsoWeekBoundaryPasses() {
        // 2026-03-13 is a Friday; 2026-03-16 is the following Monday (new ISO week).
        long completedFriday = at(2026, 3, 13, 8);
        long nextMonday = at(2026, 3, 16, 8);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.WEEKLY, completedFriday, nextMonday, UTC)).isTrue();
    }

    @Test
    void weeklyBoundaryIsCorrectAcrossACalendarYearTurn() {
        // 2025-12-31 (Wednesday) is ISO week 1 of 2026 (week-based year 2026),
        // not week 53 of calendar year 2025 — exercising the WEEK_BASED_YEAR fix.
        long completedNewYearsEve = at(2025, 12, 31, 8);
        long sameIsoWeekJan1 = at(2026, 1, 1, 8);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.WEEKLY, completedNewYearsEve, sameIsoWeekJan1, UTC)).isFalse();
    }

    @Test
    void resetMirrorsDailyBoundaryForPerPlayerRestartEligibility() {
        long completedMorning = at(2026, 3, 10, 1);
        long sameDayEvening = at(2026, 3, 10, 23);
        long nextDay = at(2026, 3, 11, 0);
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.RESET, completedMorning, sameDayEvening, UTC)).isFalse();
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.RESET, completedMorning, nextDay, UTC)).isTrue();
    }

    @Test
    void dailyWithZeroLegacyTimestampIsTreatedAsLongAgoAndAllowsRestart() {
        // Matches this repo's pre-existing behavior where DAILY was not yet
        // actually cooldown-gated: legacy save data with no recorded completion
        // time should not newly block a restart.
        assertThat(QuestRepeatPolicy.canRestart(Quest.RepeatType.DAILY, 0L, at(2026, 1, 1, 0), UTC)).isTrue();
    }
}
