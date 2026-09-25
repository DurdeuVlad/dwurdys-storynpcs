package com.storynpcs.domain.quest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;

/**
 * Pure restart-eligibility policy for {@link Quest.RepeatType} (issue #68 — quest
 * repeat modes). Default reset time zone is the server's system time zone, per
 * #68's acceptance criteria ("default reset timezone is server timezone and must
 * be displayed") — callers own actually displaying it; this class only computes
 * eligibility given an explicit zone.
 *
 * <p>{@link Quest.RepeatType#RESET}'s target semantics (a server-wide reset that
 * restarts progress for every player simultaneously) are not implemented here.
 * This policy only answers "can this one player restart now," which — for RESET
 * — currently mirrors {@link Quest.RepeatType#DAILY}'s day-boundary rule; the
 * server-wide simultaneous-reset behavior remains an explicit open gap.
 */
public final class QuestRepeatPolicy {

    private QuestRepeatPolicy() {}

    /**
     * Call this only for a quest already known to be COMPLETED for this player —
     * it decides restart eligibility given that fact, not whether it was ever
     * completed at all. {@code lastCompletedAtEpochMillis} of 0 (unset, e.g. save
     * data from before this field existed) is treated as "an unknown time in the
     * distant past": for DAILY/WEEKLY/RESET that resolves to "boundary has
     * elapsed, restart allowed" (matching this repo's pre-existing behavior,
     * where DAILY was not yet actually cooldown-gated); ONCE still never
     * restarts regardless of the timestamp.
     *
     * @param repeatType                  the quest's declared repeat mode
     * @param lastCompletedAtEpochMillis  the most recent COMPLETED transition's server-clock time
     * @param nowEpochMillis              the current server-clock time
     * @param zone                        the server's time zone (used for day/week boundary math)
     * @return whether this player may restart the quest now
     */
    public static boolean canRestart(
            Quest.RepeatType repeatType, long lastCompletedAtEpochMillis, long nowEpochMillis, ZoneId zone) {
        return switch (repeatType == null ? Quest.RepeatType.ONCE : repeatType) {
            case ONCE -> false;
            case REPEATABLE, INSTANT -> true;
            case DAILY, RESET -> !sameServerDay(lastCompletedAtEpochMillis, nowEpochMillis, zone);
            case WEEKLY -> !sameIsoWeek(lastCompletedAtEpochMillis, nowEpochMillis, zone);
        };
    }

    private static boolean sameServerDay(long aMillis, long bMillis, ZoneId zone) {
        ZonedDateTime a = Instant.ofEpochMilli(aMillis).atZone(zone);
        ZonedDateTime b = Instant.ofEpochMilli(bMillis).atZone(zone);
        return a.toLocalDate().equals(b.toLocalDate());
    }

    private static boolean sameIsoWeek(long aMillis, long bMillis, ZoneId zone) {
        ZonedDateTime a = Instant.ofEpochMilli(aMillis).atZone(zone);
        ZonedDateTime b = Instant.ofEpochMilli(bMillis).atZone(zone);
        // WEEK_BASED_YEAR (not calendar YEAR) is required here: the ISO week
        // containing Dec 31 can belong to week 1 of the following calendar year.
        return a.get(IsoFields.WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_BASED_YEAR)
                && a.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
