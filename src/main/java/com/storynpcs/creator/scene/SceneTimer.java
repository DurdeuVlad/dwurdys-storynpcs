package com.storynpcs.creator.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent timer events for actors/scenes (P8-5). Timers are durable
 * definitions — they survive unload and restart with their next-fire tick, and
 * fire at most once per scheduled tick (no drift, no loss).
 */
public final class SceneTimer {

    public record TimerEntry(UUID timerId, UUID actorId, long nextFireTick, int periodTicks, String eventId) {
        public TimerEntry {
            if (periodTicks < 1) {
                throw new IllegalArgumentException("periodTicks must be >= 1");
            }
        }
    }

    private final Map<UUID, TimerEntry> timers = new LinkedHashMap<>();

    public TimerEntry schedule(UUID actorId, long firstFireTick, int periodTicks, String eventId) {
        TimerEntry entry = new TimerEntry(UUID.randomUUID(), actorId, firstFireTick, periodTicks, eventId);
        timers.put(entry.timerId(), entry);
        return entry;
    }

    /** Deterministic due set: all timers whose nextFireTick <= now, in schedule order. */
    public java.util.List<TimerEntry> due(long nowTick) {
        return timers.values().stream().filter(t -> t.nextFireTick() <= nowTick).toList();
    }

    /** Advance a fired timer — catches up missed periods without bursting. */
    public TimerEntry fired(UUID timerId, long nowTick) {
        TimerEntry t = timers.get(timerId);
        if (t == null) {
            return null;
        }
        long missed = Math.max(1, (nowTick - t.nextFireTick()) / t.periodTicks() + 1);
        TimerEntry next = new TimerEntry(t.timerId(), t.actorId(),
                t.nextFireTick() + missed * t.periodTicks(), t.periodTicks(), t.eventId());
        timers.put(timerId, next);
        return next;
    }

    public boolean cancel(UUID timerId) {
        return timers.remove(timerId) != null;
    }

    public int cancelActor(UUID actorId) {
        int removed = 0;
        var it = timers.values().iterator();
        while (it.hasNext()) {
            if (it.next().actorId().equals(actorId)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    public Map<UUID, TimerEntry> timers() {
        return Map.copyOf(timers);
    }
}
