package com.storynpcs.domain.npc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-actor render projection cache. Each actor's resolved projection is recomputed
 * only when the display content changes (content fingerprint), so a server revision
 * pushed to the client produces a fresh projection while identical frames reuse the
 * cached one. No state is shared across actors.
 */
public final class DisplayProjectionCache {

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public DisplayProjection projectionFor(UUID actorId, NpcDisplay display) {
        if (actorId == null) {
            return DisplayProjectionResolver.resolve(display);
        }
        String fingerprint = DisplayProjectionResolver.fingerprint(display);
        Entry cached = entries.get(actorId);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.projection();
        }
        DisplayProjection resolved = DisplayProjectionResolver.resolve(display);
        entries.put(actorId, new Entry(fingerprint, resolved));
        return resolved;
    }

    public void invalidate(UUID actorId) {
        if (actorId != null) entries.remove(actorId);
    }

    public int size() {
        return entries.size();
    }

    private record Entry(String fingerprint, DisplayProjection projection) {}
}
