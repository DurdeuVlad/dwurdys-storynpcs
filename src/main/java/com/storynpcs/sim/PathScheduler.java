package com.storynpcs.sim;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bounded, prioritized path-request queue. Workers consume requests in
 * (priority desc, submission order asc) order; queue capacity is finite and
 * overflow returns an explicit {@link SubmitOutcome#QUEUE_FULL} rather than
 * silently dropping or growing unboundedly.
 *
 * <p>Requests carry an immutable {@link PathTarget} snapshot — workers never
 * receive live world references. Cancellation is by actor (unload) or by
 * revision predicate (stale plan), and is observable via the cancelled list.
 */
public final class PathScheduler {

    public static final int DEFAULT_CAPACITY = 1_024;

    /** Immutable target snapshot — never a live world object. */
    public record PathTarget(int x, int y, int z, UUID levelKey) {}

    public record PathRequest(
            UUID requestId,
            UUID actorId,
            PathTarget target,
            int priority,
            long revision,
            long submittedTick) {

        public PathRequest {
            if (requestId == null || actorId == null || target == null) {
                throw new IllegalArgumentException("requestId, actorId and target are required");
            }
        }
    }

    public enum SubmitOutcome { QUEUED, QUEUE_FULL }

    private final int capacity;
    private final Deque<PathRequest> queue = new ArrayDeque<>();
    private int droppedRequests;

    public PathScheduler() {
        this(DEFAULT_CAPACITY);
    }

    public PathScheduler(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public SubmitOutcome submit(PathRequest request) {
        if (queue.size() >= capacity) {
            droppedRequests++;
            return SubmitOutcome.QUEUE_FULL;
        }
        queue.addLast(request);
        return SubmitOutcome.QUEUED;
    }

    /** Next request by (priority desc, submittedTick asc, requestId asc) — deterministic. */
    public Optional<PathRequest> poll() {
        PathRequest best = null;
        Iterator<PathRequest> it = queue.iterator();
        while (it.hasNext()) {
            PathRequest candidate = it.next();
            if (best == null || comparator().compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        if (best != null) {
            queue.remove(best);
        }
        return Optional.ofNullable(best);
    }

    private static Comparator<PathRequest> comparator() {
        return Comparator.<PathRequest>comparingInt(r -> -r.priority())
                .thenComparingLong(PathRequest::submittedTick)
                .thenComparing(r -> r.requestId().toString());
    }

    /** Cancel every request owned by the actor (entity unload / despawn). Returns the cancelled requests. */
    public List<PathRequest> cancelActor(UUID actorId) {
        return cancelMatching(r -> r.actorId().equals(actorId));
    }

    /** Cancel requests whose revision is no longer current (stale plan). */
    public List<PathRequest> cancelStale(Predicate<Long> revisionIsCurrent) {
        return cancelMatching(r -> !revisionIsCurrent.test(r.revision()));
    }

    private List<PathRequest> cancelMatching(Predicate<PathRequest> matches) {
        List<PathRequest> cancelled = new java.util.ArrayList<>();
        queue.removeIf(r -> {
            if (matches.test(r)) {
                cancelled.add(r);
                return true;
            }
            return false;
        });
        return List.copyOf(cancelled);
    }

    public int depth() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public int droppedRequests() {
        return droppedRequests;
    }
}
