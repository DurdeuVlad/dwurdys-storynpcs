package com.storynpcs.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.sim.PathScheduler.PathRequest;
import com.storynpcs.sim.PathScheduler.PathTarget;
import com.storynpcs.sim.PathScheduler.SubmitOutcome;

class PathSchedulerTest {

    private static PathRequest request(String seed, int priority, long revision, long tick) {
        return new PathRequest(
                UUID.nameUUIDFromBytes((seed + "-req").getBytes()),
                UUID.nameUUIDFromBytes(seed.getBytes()),
                new PathTarget(1, 64, 1, UUID.nameUUIDFromBytes("overworld".getBytes())),
                priority, revision, tick);
    }

    @Test
    void queueIsBoundedAndOverflowIsExplicit() {
        PathScheduler scheduler = new PathScheduler(3);
        assertThat(scheduler.submit(request("a", 1, 1, 0))).isEqualTo(SubmitOutcome.QUEUED);
        assertThat(scheduler.submit(request("b", 1, 1, 1))).isEqualTo(SubmitOutcome.QUEUED);
        assertThat(scheduler.submit(request("c", 1, 1, 2))).isEqualTo(SubmitOutcome.QUEUED);
        assertThat(scheduler.submit(request("d", 1, 1, 3))).isEqualTo(SubmitOutcome.QUEUE_FULL);
        assertThat(scheduler.depth()).isEqualTo(3);
        assertThat(scheduler.droppedRequests()).isEqualTo(1);
    }

    @Test
    void pollOrdersByPriorityThenSubmissionTick() {
        PathScheduler scheduler = new PathScheduler();
        PathRequest low = request("low", 1, 1, 0);
        PathRequest highLate = request("high", 9, 1, 2);
        PathRequest highEarly = request("high2", 9, 1, 1);
        scheduler.submit(low);
        scheduler.submit(highLate);
        scheduler.submit(highEarly);
        assertThat(scheduler.poll()).contains(highEarly);
        assertThat(scheduler.poll()).contains(highLate);
        assertThat(scheduler.poll()).contains(low);
        assertThat(scheduler.poll()).isEmpty();
    }

    @Test
    void actorUnloadCancelsOwnedRequestsOnly() {
        PathScheduler scheduler = new PathScheduler();
        PathRequest mine = request("me", 1, 1, 0);
        PathRequest theirs = request("them", 1, 1, 0);
        scheduler.submit(mine);
        scheduler.submit(theirs);
        List<PathRequest> cancelled = scheduler.cancelActor(mine.actorId());
        assertThat(cancelled).containsExactly(mine);
        assertThat(scheduler.depth()).isEqualTo(1);
    }

    @Test
    void staleRevisionsAreCancelled() {
        PathScheduler scheduler = new PathScheduler();
        scheduler.submit(request("old", 1, 5, 0));
        scheduler.submit(request("new", 1, 6, 1));
        List<PathRequest> cancelled = scheduler.cancelStale(rev -> rev == 6);
        assertThat(cancelled).hasSize(1);
        assertThat(scheduler.depth()).isEqualTo(1);
    }

    @Test
    void defaultCapacityMatchesCertificationBound() {
        assertThat(PathScheduler.DEFAULT_CAPACITY).isEqualTo(1_024);
        assertThat(new PathScheduler().capacity()).isEqualTo(1_024);
    }
}
