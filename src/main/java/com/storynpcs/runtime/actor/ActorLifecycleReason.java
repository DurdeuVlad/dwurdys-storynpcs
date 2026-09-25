package com.storynpcs.runtime.actor;

/**
 * Why a logical NPC actor changed projection or lifecycle state.
 *
 * <p>The reason is part of the runtime contract.  Consumers must not infer
 * whether an entity was killed, unloaded, or replaced from a missing UUID.</p>
 */
public enum ActorLifecycleReason {
    SPAWNED,
    DESPAWNED,
    UNLOADED,
    RELOADED,
    REPLACED,
    PROJECTION_REFRESHED,
    PROJECTION_FAILED,
    RETRY_SUCCEEDED,
    RESTORED
}
