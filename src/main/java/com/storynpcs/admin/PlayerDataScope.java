package com.storynpcs.admin;

/**
 * Player-data operation scope (P9-4). SELF operations may only touch the
 * caller's own data; ADMIN operations require the admin capability and may
 * touch any player — the two scopes are never conflated.
 */
public enum PlayerDataScope {
    SELF,
    ADMIN;

    /** True when the scope permits operating on {@code targetPlayer}. */
    public boolean permits(java.util.UUID operatorUuid, java.util.UUID targetPlayerUuid) {
        return switch (this) {
            case SELF -> operatorUuid != null && operatorUuid.equals(targetPlayerUuid);
            case ADMIN -> true; // capability check happens at the request layer
        };
    }
}
