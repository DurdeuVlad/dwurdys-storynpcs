package com.storynpcs.admin;

import java.util.Set;
import java.util.UUID;

/**
 * Untrusted remote-access claims (P9-4). These fields are not authorization
 * evidence until a server-owned session registry validates them.
 */
public record RemoteAccessProof(
        UUID sessionId,
        UUID operatorUuid,
        Set<String> capabilities,
        long issuedTick,
        long expiryTick) {

    public RemoteAccessProof {
        if (sessionId == null || operatorUuid == null) {
            throw new IllegalArgumentException("sessionId and operatorUuid are required");
        }
        if (issuedTick < 0 || expiryTick < issuedTick) {
            // Malformed validity windows must fail at construction — a proof
            // that is valid before its own issuance or after an inverted expiry
            // can never be allowed to reach a mutation check.
            throw new IllegalArgumentException(
                    "invalid validity window: issuedTick=" + issuedTick + ", expiryTick=" + expiryTick);
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public boolean validAt(long nowTick) {
        // Fail closed on both bounds: before issuance and after expiry.
        return nowTick >= 0 && nowTick >= issuedTick && nowTick <= expiryTick;
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    /** Full check: unexpired session + required capability. */
    public boolean permits(String capability, long nowTick) {
        return validAt(nowTick) && hasCapability(capability);
    }
}
