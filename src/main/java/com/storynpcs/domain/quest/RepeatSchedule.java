package com.storynpcs.domain.quest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Explicit clock/timezone policy for DAILY and WEEKLY repeat boundaries.
 * Deterministic: given the same completion instant, "now" instant, and policy,
 * the answer is always identical — no implicit server-timezone lookups.
 */
public record RepeatSchedule(ZoneId zone, int dayStartHour, java.time.DayOfWeek weekStartDay) {

    public RepeatSchedule {
        if (zone == null) {
            throw new IllegalArgumentException("zone is required for repeat boundaries");
        }
        if (dayStartHour < 0 || dayStartHour > 23) {
            throw new IllegalArgumentException("dayStartHour must be in [0,23]");
        }
        if (weekStartDay == null) {
            throw new IllegalArgumentException("weekStartDay is required");
        }
    }

    /** UTC midnight day boundaries, Monday week start — the default policy. */
    public static RepeatSchedule utcDefault() {
        return new RepeatSchedule(ZoneId.of("UTC"), 0, java.time.DayOfWeek.MONDAY);
    }

    /**
     * Whether the quest may be completed again given the last completion instant.
     * NORMAL/RESET/INSTANT do not consult the clock here — RESET is governed by
     * the reset token, INSTANT/NORMAL semantics are handled by callers.
     */
    public boolean canRepeat(Quest.RepeatType repeatType, Instant lastCompleted, Instant now) {
        if (repeatType == null || lastCompleted == null) {
            return true;
        }
        return switch (repeatType) {
            case NORMAL -> false;
            case REPEATABLE, INSTANT -> true;
            case DAILY -> boundaryFor(lastCompleted).isBefore(boundaryFor(now));
            case WEEKLY -> weekBoundaryFor(lastCompleted).isBefore(weekBoundaryFor(now));
            case RESET -> false; // only an explicit reset re-opens it
        };
    }

    /** The most recent day-boundary instant at or before {@code instant}. */
    public ZonedDateTime boundaryFor(Instant instant) {
        ZonedDateTime zoned = instant.atZone(zone).truncatedTo(ChronoUnit.DAYS).withHour(dayStartHour);
        if (zoned.isAfter(instant.atZone(zone))) {
            zoned = zoned.minusDays(1);
        }
        return zoned;
    }

    /** The most recent week-boundary instant at or before {@code instant}. */
    public ZonedDateTime weekBoundaryFor(Instant instant) {
        ZonedDateTime day = boundaryFor(instant);
        int diff = day.getDayOfWeek().getValue() - weekStartDay.getValue();
        if (diff < 0) {
            diff += 7;
        }
        return day.minusDays(diff);
    }
}
