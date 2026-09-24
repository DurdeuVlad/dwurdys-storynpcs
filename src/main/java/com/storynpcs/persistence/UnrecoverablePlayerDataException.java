package com.storynpcs.persistence;

import java.util.UUID;

/**
 * Thrown when a durable player record exists on disk but cannot be loaded
 * (corrupt, future schema, or otherwise unrecoverable). The repository marks
 * the record blocked and refuses later writes until an operator clears the
 * quarantined artifacts, so an empty record can never silently overwrite
 * existing player data.
 */
public class UnrecoverablePlayerDataException extends IllegalStateException {
    private final String store;
    private final UUID playerUuid;

    public UnrecoverablePlayerDataException(String store, UUID playerUuid, String reason) {
        super(store + " data for " + playerUuid + " is unrecoverable and fail-closed: " + reason);
        this.store = store;
        this.playerUuid = playerUuid;
    }

    public String store() {
        return store;
    }

    public UUID playerUuid() {
        return playerUuid;
    }
}
