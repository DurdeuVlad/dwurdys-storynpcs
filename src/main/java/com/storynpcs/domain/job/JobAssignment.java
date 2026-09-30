package com.storynpcs.domain.job;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * An NPC's job assignment: which {@link JobType} it holds and the job's
 * lifecycle state (issue #73 — job capability inventory foundation).
 *
 * <p><b>Scope boundary:</b> this class is the shared lifecycle contract every
 * job handler will eventually plug into — start/pause/resume/stop, each a
 * validated state transition with no illegal jump (e.g. resuming a STOPPED
 * job). It intentionally does NOT implement any job's actual per-tick
 * behavior, a scheduler/tick-budget integration, or any entity/chunk/task
 * creation — those need live server hooks this environment cannot safely
 * exercise or verify, and are explicitly left for the job-by-job follow-up
 * work #73 still requires (per-job typed config, tick cost observability, and
 * the "no job creates unbounded entities/chunks/tasks" quota guarantee all
 * depend on that runtime integration existing first).
 */
public class JobAssignment {

    @JsonProperty(required = true)
    private JobType type;

    @JsonProperty
    private JobState state = JobState.IDLE;

    @JsonProperty
    private long assignedAtEpochMillis;

    @JsonProperty
    private long lastTransitionAtEpochMillis;

    public JobAssignment() {}

    public JobAssignment(JobType type) {
        this.type = Objects.requireNonNull(type, "type");
        long now = System.currentTimeMillis();
        this.assignedAtEpochMillis = now;
        this.lastTransitionAtEpochMillis = now;
    }

    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }

    public JobState getState() { return state; }
    public void setState(JobState state) { this.state = state; }

    public long getAssignedAtEpochMillis() { return assignedAtEpochMillis; }
    public void setAssignedAtEpochMillis(long assignedAtEpochMillis) { this.assignedAtEpochMillis = assignedAtEpochMillis; }

    public long getLastTransitionAtEpochMillis() { return lastTransitionAtEpochMillis; }
    public void setLastTransitionAtEpochMillis(long lastTransitionAtEpochMillis) { this.lastTransitionAtEpochMillis = lastTransitionAtEpochMillis; }

    /** IDLE -> RUNNING. Throws if not currently IDLE. */
    public void start() {
        requireState(JobState.IDLE, "start");
        transitionTo(JobState.RUNNING);
    }

    /** RUNNING -> PAUSED. Throws if not currently RUNNING. */
    public void pause() {
        requireState(JobState.RUNNING, "pause");
        transitionTo(JobState.PAUSED);
    }

    /** PAUSED -> RUNNING. Throws if not currently PAUSED. */
    public void resume() {
        requireState(JobState.PAUSED, "resume");
        transitionTo(JobState.RUNNING);
    }

    /**
     * Any non-STOPPED state -> STOPPED. Idempotent: stopping an already-stopped
     * job is a no-op, since actor-unload cleanup may call this more than once.
     */
    public void stop() {
        if (state == JobState.STOPPED) return;
        transitionTo(JobState.STOPPED);
    }

    private void requireState(JobState required, String action) {
        if (state != required) {
            throw new IllegalStateException(
                    "Cannot " + action + " a job assignment in state " + state + " (requires " + required + ")");
        }
    }

    private void transitionTo(JobState next) {
        state = next;
        lastTransitionAtEpochMillis = System.currentTimeMillis();
    }
}
