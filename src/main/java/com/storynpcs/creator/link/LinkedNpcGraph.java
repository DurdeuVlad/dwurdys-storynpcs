package com.storynpcs.creator.link;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Linked-NPC relationship graph (P8-5). Links are validated: no self-links,
 * no cycles, linked targets must exist in the actor set.
 */
public final class LinkedNpcGraph {

    private final Map<UUID, UUID> links = new LinkedHashMap<>();

    public enum Reject { SELF, CYCLE, TARGET_MISSING }

    /** Attempt a link; null = accepted. */
    public Reject link(UUID actor, UUID target, Set<UUID> knownActors) {
        if (actor == null || target == null || actor.equals(target)) {
            return Reject.SELF;
        }
        if (!knownActors.contains(target)) {
            return Reject.TARGET_MISSING;
        }
        // Cycle check: follow the target's chain — reaching actor would close a loop.
        UUID cursor = target;
        Set<UUID> seen = new java.util.HashSet<>();
        while (cursor != null) {
            if (cursor.equals(actor)) {
                return Reject.CYCLE;
            }
            if (!seen.add(cursor)) {
                break;
            }
            cursor = links.get(cursor);
        }
        links.put(actor, target);
        return null;
    }

    /** Rehydrate a persisted link with its original pair — restart restore seam. */
    public void restore(UUID actor, UUID target) {
        if (actor != null && target != null) {
            links.put(actor, target);
        }
    }

    public UUID targetOf(UUID actor) {
        return links.get(actor);
    }

    public boolean unlink(UUID actor) {
        return links.remove(actor) != null;
    }

    /** Remove all links to/from a departing actor — unload cleanup. */
    public int removeActor(UUID actor) {
        int removed = links.remove(actor) != null ? 1 : 0;
        var it = links.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().equals(actor)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    public Map<UUID, UUID> links() {
        return Map.copyOf(links);
    }
}
