package com.storynpcs.api.event;

import com.storynpcs.domain.common.NamespacedId;

import java.util.UUID;

/**
 * Published when a template spawner's ownership ledger changes (P8-1): an
 * actor spawned, an owned actor left the ledger (death, cleanup, despawn, or
 * grace expiry), or a spawner's durable state was pruned after rule deletion.
 * Audit surface for the deterministic quota/interval/chunk rules.
 */
public record NpcSpawnerLifecycleEvent(
        NamespacedId spawnerId,
        UUID actorUuid,
        Kind kind
) implements StoryNpcsEvent {

    public enum Kind {
        SPAWNED,
        RELEASED_DEATH,
        RELEASED_UNLOAD_CLEANUP,
        RELEASED_DESPAWNED,
        RELEASED_MISSING_GRACE,
        STATE_PRUNED
    }
}
