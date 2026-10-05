package com.storynpcs.domain.npc;

/**
 * Server-side emote lifecycle for {@link com.storynpcs.entity.StoryNpcEntity}
 * (issue #58). An emote is a transient animation with a bounded duration:
 * starting a new emote interrupts the previous one, ticking past the duration
 * returns the state to {@link NpcEmote#NONE}, and the tick counter is the
 * authoritative progress value mirrored to clients.
 *
 * <p>Mutable by design — the entity owns exactly one instance and updates it
 * under its tick path.
 */
public final class NpcEmoteState {

    private NpcEmote emote = NpcEmote.NONE;
    private int remainingTicks;
    private int totalTicks;

    public NpcEmote current() { return emote; }
    public int remainingTicks() { return remainingTicks; }
    public int totalTicks() { return totalTicks; }
    public boolean isActive() { return emote != NpcEmote.NONE && remainingTicks > 0; }

    /**
     * Starts (or interrupts to) an emote for {@code durationTicks}, clamped to
     * {@link NpcEmote#MAX_DURATION_TICKS}. Starting {@link NpcEmote#NONE} is a
     * clean stop and returns {@code true} only if an emote was active.
     */
    public boolean start(NpcEmote next, int durationTicks) {
        NpcEmote resolved = next == null ? NpcEmote.NONE : next;
        boolean wasActive = isActive();
        if (resolved == NpcEmote.NONE) {
            emote = NpcEmote.NONE;
            remainingTicks = 0;
            totalTicks = 0;
            return wasActive;
        }
        int duration = durationTicks <= 0
                ? NpcEmote.DEFAULT_DURATION_TICKS
                : Math.min(durationTicks, NpcEmote.MAX_DURATION_TICKS);
        boolean changed = resolved != emote || duration != totalTicks || !wasActive;
        emote = resolved;
        totalTicks = duration;
        remainingTicks = duration;
        return changed;
    }

    /** Advances one tick; returns {@code true} when this tick ended the emote. */
    public boolean tick() {
        if (!isActive()) return false;
        remainingTicks--;
        if (remainingTicks <= 0) {
            emote = NpcEmote.NONE;
            remainingTicks = 0;
            totalTicks = 0;
            return true;
        }
        return false;
    }

    /**
     * Normalized progress in {@code [0,1]} for the client animator: 0 at
     * emote start, 1 at expiry.
     */
    public float progress() {
        return totalTicks <= 0 ? 0f : 1f - (remainingTicks / (float) totalTicks);
    }
}
