package com.storynpcs.domain.persistence;

import java.util.Objects;

/**
 * One target persistence-store category's recorded parity status (issue #56 —
 * P2-2: "the 18 target store categories are mapped to StoryNPCs stores").
 *
 * <p>The constructor enforces the mapping contract directly: a
 * {@link PersistenceStoreStatus#MAPPED} or {@link PersistenceStoreStatus#PARTIALLY_MAPPED}
 * entry must name its StoryNPCs equivalent, and any status other than
 * {@code MAPPED} must give a rationale. A status can never be silently
 * unexplained, mirroring {@code CommandParityEntry} (issue #85).
 */
public final class PersistenceStoreEntry {

    private final String inventoryId;
    private final String targetSymbol;
    private final String targetOwner;
    private final PersistenceStoreStatus status;
    private final String storynpcsEquivalent;
    private final String rationale;

    public PersistenceStoreEntry(String inventoryId, String targetSymbol, String targetOwner,
                                  PersistenceStoreStatus status, String storynpcsEquivalent, String rationale) {
        this.inventoryId = requireNonBlank(inventoryId, "inventoryId");
        this.targetSymbol = requireNonBlank(targetSymbol, "targetSymbol");
        this.targetOwner = requireNonBlank(targetOwner, "targetOwner");
        this.status = Objects.requireNonNull(status, "status");

        boolean equivalentRequired = status == PersistenceStoreStatus.MAPPED
                || status == PersistenceStoreStatus.PARTIALLY_MAPPED;
        if (equivalentRequired) {
            this.storynpcsEquivalent = requireNonBlank(storynpcsEquivalent,
                    "storynpcsEquivalent is required for a " + status + " entry (" + inventoryId + ")");
        } else {
            this.storynpcsEquivalent = storynpcsEquivalent;
        }

        if (status != PersistenceStoreStatus.MAPPED) {
            this.rationale = requireNonBlank(rationale,
                    "rationale is required for a non-MAPPED entry (" + inventoryId + ")");
        } else {
            this.rationale = rationale;
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public String getInventoryId() { return inventoryId; }
    public String getTargetSymbol() { return targetSymbol; }
    public String getTargetOwner() { return targetOwner; }
    public PersistenceStoreStatus getStatus() { return status; }
    public String getStorynpcsEquivalent() { return storynpcsEquivalent; }
    public String getRationale() { return rationale; }
}
