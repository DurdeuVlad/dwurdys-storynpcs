package com.storynpcs.runtime.orchestration;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.P85OrchestrationEvents.NpcLinkEvent;
import com.storynpcs.creator.link.LinkedNpcGraph;
import com.storynpcs.persistence.LinkedNpcStore;
import com.storynpcs.persistence.LinkedNpcStore.LinkRecord;

/**
 * Durable linked-NPC driver (P8-5). The {@link LinkedNpcGraph} validates
 * SELF/CYCLE/TARGET_MISSING; this runtime persists accepted links, rehydrates
 * them at world init, and cleans links whose actors leave the world.
 * <p>
 * Live semantic: a linked actor follows its target — each tick pass re-issues
 * a bounded move when the pair drifts beyond {@value #FOLLOW_DISTANCE}.
 */
public final class LinkedNpcRuntime {

    /** A link whose target is missing survives this many ticks before cleanup. */
    public static final int MISSING_TARGET_GRACE_TICKS = 100;
    /** Beyond this distance the linked actor re-issues a follow move. */
    public static final double FOLLOW_DISTANCE = 8.0;

    private final LinkedNpcGraph graph = new LinkedNpcGraph();
    private final java.util.Map<UUID, Integer> missingTargetSince = new java.util.HashMap<>();
    private LinkedNpcStore store;
    private EventPublisher events;

    public void attach(LinkedNpcStore store, EventPublisher events) throws IOException {
        this.store = store;
        this.events = events;
        for (String id : store.listIds()) {
            store.load(UUID.fromString(id)).ifPresent(
                    r -> graph.restore(r.actorUuid(), r.targetUuid()));
        }
    }

    /**
     * Attempt a link. {@code knownActors} is the live actor set; rejection is
     * reported, never persisted.
     */
    public LinkedNpcGraph.Reject link(UUID actor, UUID target, Set<UUID> knownActors) {
        var reject = graph.link(actor, target, knownActors);
        if (reject == null) {
            persist(actor, target);
            publish(actor, target, NpcLinkEvent.Action.LINKED);
        }
        return reject;
    }

    public boolean unlink(UUID actor) {
        boolean removed = graph.unlink(actor);
        if (removed) {
            try {
                if (store != null) {
                    store.delete(actor);
                }
            } catch (IOException e) {
                System.err.println("[StoryNPCs] link delete failed for " + actor + ": " + e.getMessage());
            }
            publish(actor, graph.targetOf(actor), NpcLinkEvent.Action.UNLINKED);
        }
        return removed;
    }

    /** Drop all links to/from a departing actor — unload cleanup. */
    public int removeActor(UUID actor) {
        int removed = 0;
        for (Map.Entry<UUID, UUID> e : Map.copyOf(graph.links()).entrySet()) {
            if (e.getKey().equals(actor) || e.getValue().equals(actor)) {
                unlink(e.getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Tick pass: links whose targets have been missing past the grace window
     * are cleaned up (durable + audit); surviving links drive the follow move.
     *
     * @param lookup  resolves an actor UUID to a live entity view
     * @param nowTick current server tick
     */
    public int tick(java.util.function.Function<UUID, ActorView> lookup, long nowTick) {
        int followed = 0;
        for (Map.Entry<UUID, UUID> e : Map.copyOf(graph.links()).entrySet()) {
            ActorView target = lookup.apply(e.getValue());
            if (target == null) {
                int since = missingTargetSince.merge(e.getKey(), 0, Integer::sum);
                if (since >= MISSING_TARGET_GRACE_TICKS) {
                    missingTargetSince.remove(e.getKey());
                    unlink(e.getKey());
                    publish(e.getKey(), e.getValue(), NpcLinkEvent.Action.TARGET_MISSING_CLEANUP);
                } else {
                    missingTargetSince.put(e.getKey(), since + 1);
                }
                continue;
            }
            missingTargetSince.remove(e.getKey());
            ActorView actor = lookup.apply(e.getKey());
            if (actor == null || actor.distanceTo(target) <= FOLLOW_DISTANCE) {
                continue;
            }
            actor.moveToward(target);
            followed++;
        }
        return followed;
    }

    private void persist(UUID actor, UUID target) {
        if (store != null) {
            try {
                store.save(new LinkRecord(actor, target));
            } catch (IOException e) {
                System.err.println("[StoryNPCs] link persist failed for " + actor + ": " + e.getMessage());
            }
        }
    }

    private void publish(UUID actor, UUID target, NpcLinkEvent.Action action) {
        if (events != null && target != null) {
            events.publish(new NpcLinkEvent(actor, target, action));
        }
    }

    public Map<UUID, UUID> links() {
        return graph.links();
    }

    public void clear() {
        for (UUID actor : Map.copyOf(graph.links()).keySet()) {
            graph.unlink(actor);
        }
        missingTargetSince.clear();
        this.store = null;
        this.events = null;
    }

    /** Narrow live-entity seam — GameTest and server tick both satisfy it. */
    public interface ActorView {
        net.minecraft.world.phys.Vec3 position();
        double distanceTo(ActorView other);
        void moveToward(ActorView target);
    }
}
