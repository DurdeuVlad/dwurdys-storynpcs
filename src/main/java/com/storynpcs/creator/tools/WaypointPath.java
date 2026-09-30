package com.storynpcs.creator.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A waypoint path (P8-2): ordered waypoints with traversal mode and editing
 * operations that fail explicitly on bad indices rather than corrupting state.
 */
public class WaypointPath {

    public enum TraversalMode { LOOP, PING_PONG, ONCE }
    public static final int MAX_WAYPOINTS = 64;

    @JsonProperty
    private List<double[]> waypoints = new ArrayList<>();

    @JsonProperty
    private TraversalMode mode = TraversalMode.LOOP;

    @JsonProperty
    private int pauseTicksPerWaypoint = 20;

    public WaypointPath() {}

    public List<double[]> getWaypoints() {
        return waypoints.stream().map(double[]::clone).toList();
    }
    public void setWaypoints(List<double[]> waypoints) {
        if (waypoints != null && waypoints.size() > MAX_WAYPOINTS) {
            throw new IllegalArgumentException("waypoints cannot exceed " + MAX_WAYPOINTS);
        }
        this.waypoints = new ArrayList<>();
        if (waypoints != null) {
            waypoints.forEach(w -> this.waypoints.add(validate(w)));
        }
    }

    public TraversalMode getMode() { return mode; }
    public void setMode(TraversalMode mode) { this.mode = mode == null ? TraversalMode.LOOP : mode; }

    public int getPauseTicksPerWaypoint() { return pauseTicksPerWaypoint; }
    public void setPauseTicksPerWaypoint(int pauseTicksPerWaypoint) {
        if (pauseTicksPerWaypoint < 0) {
            throw new IllegalArgumentException("pauseTicksPerWaypoint must be >= 0");
        }
        this.pauseTicksPerWaypoint = pauseTicksPerWaypoint;
    }

    public boolean add(double x, double y, double z) {
        if (waypoints.size() >= MAX_WAYPOINTS) {
            return false;
        }
        waypoints.add(new double[]{x, y, z});
        return true;
    }

    public boolean move(int index, double x, double y, double z) {
        if (index < 0 || index >= waypoints.size()) {
            return false;
        }
        waypoints.set(index, new double[]{x, y, z});
        return true;
    }

    public boolean delete(int index) {
        if (index < 0 || index >= waypoints.size()) {
            return false;
        }
        waypoints.remove(index);
        return true;
    }

    /** Next waypoint index under the traversal mode, or -1 when finished (ONCE at end). */
    public int nextIndex(int current, boolean forwardLeg) {
        int n = waypoints.size();
        if (n == 0) return -1;
        return switch (mode) {
            case LOOP -> (current + 1) % n;
            case ONCE -> current + 1 < n ? current + 1 : -1;
            case PING_PONG -> {
                int next = forwardLeg ? current + 1 : current - 1;
                yield next >= 0 && next < n ? next : -1;
            }
        };
    }

    private static double[] validate(double[] w) {
        if (w == null || w.length != 3) {
            throw new IllegalArgumentException("waypoint must be [x,y,z]");
        }
        for (double v : w) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("waypoint coordinates must be finite");
            }
        }
        return w.clone();
    }
}
