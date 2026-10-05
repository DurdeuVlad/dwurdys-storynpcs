package com.storynpcs.domain.job;

import java.util.UUID;

/**
 * A job running on an actor. Lifecycle is explicit: every job stops on actor
 * unload or removal — pause semantics are config-driven but state is never
 * silently dropped.
 */
public final class JobInstance {

    public enum State { RUNNING, PAUSED, STOPPED }

    private final UUID instanceId = UUID.randomUUID();
    private final JobConfig config;
    private final UUID actorId;
    private State state = State.RUNNING;
    private long lastRunTick = -1;
    private long ticksRun;
    /**
     * BUILDER: schematic resolved once per RUNNING stint — cleared on
     * pause/stop so a resume re-reads the current file rather than holding a
     * stale copy. Runtime-only; never serialized.
     */
    private transient com.storynpcs.domain.schematic.Schematic builderSchematic;

    public JobInstance(UUID actorId, JobConfig config) {
        this.actorId = actorId;
        this.config = config;
        config.validate();
    }

    public UUID getInstanceId() { return instanceId; }
    public JobConfig getConfig() { return config; }
    public UUID getActorId() { return actorId; }
    public State getState() { return state; }
    public long getTicksRun() { return ticksRun; }

    /** Whether the handler should run this tick — budget is observable via tickPeriod. */
    public boolean shouldRun(long tick) {
        return state == State.RUNNING && config.isEnabled() && tick - lastRunTick >= config.getTickPeriod();
    }

    public void markRan(long tick) {
        if (state != State.RUNNING) {
            throw new IllegalStateException("cannot run a " + state + " job");
        }
        lastRunTick = tick;
        ticksRun++;
    }

    /** Actor unload/removal: pause when configured, otherwise stop permanently. */
    public void onActorUnload() {
        if (state != State.STOPPED) {
            state = config.isPauseOnUnload() ? State.PAUSED : State.STOPPED;
        }
    }

    /** BUILDER schematic cache — runtime-only, cleared when not RUNNING. */
    public com.storynpcs.domain.schematic.Schematic getBuilderSchematic() {
        return builderSchematic;
    }
    public void setBuilderSchematic(com.storynpcs.domain.schematic.Schematic schematic) {
        this.builderSchematic = schematic;
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            builderSchematic = null;
        }
    }
    public void resume() { if (state == State.PAUSED) state = State.RUNNING; }
    public void stop() { state = State.STOPPED; builderSchematic = null; }
}
