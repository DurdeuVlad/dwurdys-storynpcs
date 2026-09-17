package com.storynpcs.domain.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointPathTest {

    @Test
    @DisplayName("LOOP mode cycles continuously from start to end and wraps back")
    void testLoopMode() {
        WaypointPath path = new WaypointPath(
                WaypointPath.PatrolMode.LOOP,
                List.of(new Waypoint(0, 0, 0), new Waypoint(10, 0, 0), new Waypoint(20, 0, 0))
        );

        assertEquals(3, path.size());

        int[] step1 = path.computeNextIndex(0, true);
        assertEquals(1, step1[0]);

        int[] step2 = path.computeNextIndex(1, true);
        assertEquals(2, step2[0]);

        int[] step3 = path.computeNextIndex(2, true);
        assertEquals(0, step3[0], "LOOP must wrap back to 0");
    }

    @Test
    @DisplayName("PING_PONG mode reverses direction at both ends")
    void testPingPongMode() {
        WaypointPath path = new WaypointPath(
                WaypointPath.PatrolMode.PING_PONG,
                List.of(new Waypoint(0, 0, 0), new Waypoint(10, 0, 0), new Waypoint(20, 0, 0))
        );

        // 0 -> 1
        int[] s1 = path.computeNextIndex(0, true);
        assertEquals(1, s1[0]);
        assertEquals(1, s1[1]); // forward

        // 1 -> 2
        int[] s2 = path.computeNextIndex(1, true);
        assertEquals(2, s2[0]);
        assertEquals(1, s2[1]); // forward

        // At end (index 2), moving forward reverses direction to index 1, backward
        int[] s3 = path.computeNextIndex(2, true);
        assertEquals(1, s3[0]);
        assertEquals(0, s3[1]); // backward

        // 1 -> 0 (moving backward)
        int[] s4 = path.computeNextIndex(1, false);
        assertEquals(0, s4[0]);
        assertEquals(0, s4[1]); // backward

        // At start (index 0), moving backward reverses to index 1, forward
        int[] s5 = path.computeNextIndex(0, false);
        assertEquals(1, s5[0]);
        assertEquals(1, s5[1]); // forward
    }

    @Test
    @DisplayName("ONCE mode traverses to the end and remains on the terminal waypoint")
    void testOnceMode() {
        WaypointPath path = new WaypointPath(
                WaypointPath.PatrolMode.ONCE,
                List.of(new Waypoint(0, 0, 0, 20), new Waypoint(5, 0, 5, 40))
        );

        int[] s1 = path.computeNextIndex(0, true);
        assertEquals(1, s1[0]);

        int[] s2 = path.computeNextIndex(1, true);
        assertEquals(1, s2[0], "ONCE mode must stay at terminal waypoint");

        assertEquals(40, path.getWaypoint(1).waitTicks());
    }

    @Test
    @DisplayName("Empty and single-waypoint edge cases handled safely")
    void testEdgeCases() {
        WaypointPath empty = new WaypointPath();
        assertEquals(0, empty.size());
        assertEquals(-1, empty.computeNextIndex(0, true)[0]);

        WaypointPath single = new WaypointPath(WaypointPath.PatrolMode.LOOP, List.of(new Waypoint(1, 2, 3)));
        assertEquals(1, single.size());
        assertEquals(0, single.computeNextIndex(0, true)[0]);
    }
}