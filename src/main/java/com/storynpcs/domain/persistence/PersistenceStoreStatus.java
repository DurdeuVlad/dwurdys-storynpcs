package com.storynpcs.domain.persistence;

/**
 * Parity status of one target CustomNPCs persistence store category against
 * this codebase (issue #56 — P2-2 durable world/player/economy stores).
 */
public enum PersistenceStoreStatus {
    /** A StoryNPCs store durably persists the equivalent data. */
    MAPPED,
    /** Part of the target category is durably persisted; part is not. */
    PARTIALLY_MAPPED,
    /** A conscious architectural decision was made not to replicate this store as-is. */
    INTENTIONAL_DEVIATION,
    /** No StoryNPCs equivalent exists yet; not yet triaged as a deviation. */
    UNMAPPED
}
