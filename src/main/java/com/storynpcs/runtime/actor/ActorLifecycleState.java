package com.storynpcs.runtime.actor;

/** State of a logical actor's current in-world projection. */
public enum ActorLifecycleState {
    UNPROJECTED,
    PROJECTED,
    UNLOADED,
    FAILED
}
