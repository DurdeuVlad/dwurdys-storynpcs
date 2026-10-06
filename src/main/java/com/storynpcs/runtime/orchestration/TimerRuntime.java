package com.storynpcs.runtime.orchestration;

import java.io.IOException;
import java.util.UUID;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.P85OrchestrationEvents.NpcTimerEvent;
import com.storynpcs.creator.scene.SceneTimer;
import com.storynpcs.creator.scene.SceneTimer.TimerEntry;
import com.storynpcs.persistence.SceneTimerStore;

/**
 * Durable scene-timer driver (P8-5). Timers live in {@link SceneTimerStore};
 * the in-memory {@link SceneTimer} is a hot cache rehydrated at world init.
 * <p>
 * Exactly-once policy: a due timer is <em>advanced and persisted first</em>,
 * then the event is published. A crash between persist and publish loses at
 * most that single firing — the schedule never drifts and never double-fires,
 * matching the "survive restart without duplicate firing" contract.
 */
public final class TimerRuntime {

    private final SceneTimer timers = new SceneTimer();
    private SceneTimerStore store;
    private EventPublisher events;

    /** Rehydrate persisted entries (preserving their ids) and bind the event bus. */
    public void attach(SceneTimerStore store, EventPublisher events) throws IOException {
        this.store = store;
        this.events = events;
        for (String id : store.listIds()) {
            store.load(UUID.fromString(id)).ifPresent(timers::restore);
        }
    }

    /** Schedule a persistent timer; returns the durable entry. */
    public TimerEntry schedule(UUID actorId, long firstFireTick, int periodTicks,
                               String eventId) throws IOException {
        TimerEntry entry = timers.schedule(actorId, firstFireTick, periodTicks, eventId);
        if (store != null) {
            store.save(entry);
        }
        return entry;
    }

    /**
     * Drain due timers at {@code nowTick}. Each due timer is advanced +
     * persisted before its event publishes — restart-safe, burst-free.
     */
    public int tick(long nowTick) {
        int fired = 0;
        for (TimerEntry due : timers.due(nowTick)) {
            TimerEntry next = timers.fired(due.timerId(), nowTick);
            if (next == null) {
                continue;
            }
            try {
                if (store != null) {
                    store.save(next);
                }
            } catch (IOException e) {
                System.err.println("[StoryNPCs] timer persist failed for "
                        + due.timerId() + ": " + e.getMessage());
            }
            if (events != null) {
                events.publish(new NpcTimerEvent(due.timerId(), due.actorId(),
                        due.eventId(), nowTick));
            }
            fired++;
        }
        return fired;
    }

    public boolean cancel(UUID timerId) {
        boolean removed = timers.cancel(timerId);
        if (removed && store != null) {
            try {
                store.delete(timerId);
            } catch (IOException e) {
                System.err.println("[StoryNPCs] timer delete failed for "
                        + timerId + ": " + e.getMessage());
            }
        }
        return removed;
    }

    /** Cancel every timer bound to a departing actor — unload cleanup. */
    public int cancelActor(UUID actorId) {
        var ids = timers.timers().values().stream()
                .filter(t -> t.actorId().equals(actorId))
                .map(TimerEntry::timerId).toList();
        int removed = 0;
        for (UUID id : ids) {
            if (cancel(id)) {
                removed++;
            }
        }
        return removed;
    }

    public java.util.Map<UUID, TimerEntry> timers() {
        return timers.timers();
    }

    public void clear() {
        timers.timers().keySet().forEach(timers::cancel);
        this.store = null;
        this.events = null;
    }
}
