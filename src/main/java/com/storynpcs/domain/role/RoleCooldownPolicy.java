package com.storynpcs.domain.role;

/**
 * Pure cooldown-elapsed check shared by every role whose ability is rate-limited
 * (healer casts, bard performances, and any future role with the same shape).
 * Mirrors {@code com.storynpcs.domain.quest.QuestRepeatPolicy}'s pure-function style.
 */
public final class RoleCooldownPolicy {

    private RoleCooldownPolicy() {}

    /**
     * True if enough time has passed since {@code lastActivatedAtEpochMillis} for the
     * ability to fire again, given a cooldown of {@code cooldownMillis}.
     *
     * <p>A {@code lastActivatedAtEpochMillis} of 0 or less is treated as "never
     * activated" and always allows activation, regardless of cooldown length. A
     * negative {@code cooldownMillis} is treated as no cooldown (always ready).
     */
    public static boolean canActivate(long cooldownMillis, long lastActivatedAtEpochMillis, long nowEpochMillis) {
        if (lastActivatedAtEpochMillis <= 0) {
            return true;
        }
        if (cooldownMillis <= 0) {
            return true;
        }
        return nowEpochMillis - lastActivatedAtEpochMillis >= cooldownMillis;
    }
}
