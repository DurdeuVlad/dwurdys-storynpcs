package com.storynpcs.domain.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WaypointPath {

    public enum PatrolMode {
        LOOP,       // A -> B -> C -> A
        PING_PONG,  // A -> B -> C -> B -> A
        ONCE        // A -> B -> C -> stop
    }

    @JsonProperty
    private PatrolMode mode = PatrolMode.LOOP;

    @JsonProperty
    private List<Waypoint> waypoints = new ArrayList<>();

    public WaypointPath() {}

    public WaypointPath(PatrolMode mode, List<Waypoint> waypoints) {
        this.mode = mode != null ? mode : PatrolMode.LOOP;
        setWaypoints(waypoints);
    }

    public PatrolMode getMode() { return mode; }
    public void setMode(PatrolMode mode) { this.mode = mode; }

    public List<Waypoint> getWaypoints() { return Collections.unmodifiableList(waypoints); }
    public void setWaypoints(List<Waypoint> waypoints) {
        var next = waypoints != null ? new ArrayList<>(waypoints) : new ArrayList<Waypoint>();
        if (next.size() > MAX_WAYPOINTS) {
            next = new ArrayList<>(next.subList(0, MAX_WAYPOINTS));
        }
        this.waypoints = next;
    }

    /** P8-2: waypoints are bounded — the 65th add must be a diagnostic, not silent growth. */
    public static final int MAX_WAYPOINTS = 64;

    /** Returns false when the path is already at {@link #MAX_WAYPOINTS}. */
    public boolean addWaypoint(Waypoint waypoint) {
        if (waypoint == null || waypoints.size() >= MAX_WAYPOINTS) {
            return false;
        }
        waypoints.add(waypoint);
        return true;
    }

    /** Moves the waypoint at {@code index}; returns false for an invalid index. */
    public boolean moveWaypoint(int index, Waypoint waypoint) {
        if (waypoint == null || index < 0 || index >= waypoints.size()) {
            return false;
        }
        waypoints.set(index, waypoint);
        return true;
    }

    /** Removes the waypoint at {@code index}; returns false for an invalid index. */
    public boolean removeWaypoint(int index) {
        if (index < 0 || index >= waypoints.size()) {
            return false;
        }
        waypoints.remove(index);
        return true;
    }

    public int size() {
        return waypoints.size();
    }

    public Waypoint getWaypoint(int index) {
        if (index >= 0 && index < waypoints.size()) {
            return waypoints.get(index);
        }
        return null;
    }

    /**
     * Calculates the next index based on patrol mode and direction.
     * Returns a 2-element array: [nextIndex, isMovingForward (1 for true, 0 for false)].
     */
    public int[] computeNextIndex(int currentIndex, boolean movingForward) {
        if (waypoints.isEmpty()) {
            return new int[]{-1, 1};
        }
        if (waypoints.size() == 1) {
            return new int[]{0, 1};
        }

        switch (mode) {
            case LOOP:
                return new int[]{(currentIndex + 1) % waypoints.size(), 1};

            case PING_PONG:
                if (movingForward) {
                    if (currentIndex + 1 < waypoints.size()) {
                        return new int[]{currentIndex + 1, 1};
                    } else {
                        // Reached end, reverse direction
                        return new int[]{currentIndex - 1, 0};
                    }
                } else {
                    if (currentIndex - 1 >= 0) {
                        return new int[]{currentIndex - 1, 0};
                    } else {
                        // Reached start, reverse direction
                        return new int[]{currentIndex + 1, 1};
                    }
                }

            case ONCE:
            default:
                if (currentIndex + 1 < waypoints.size()) {
                    return new int[]{currentIndex + 1, 1};
                } else {
                    return new int[]{currentIndex, 1}; // Stay at last waypoint
                }
        }
    }
}