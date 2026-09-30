package com.storynpcs.domain.command;

import java.util.Objects;

/**
 * One target CustomNPCs command leaf's recorded parity status (issue #85 —
 * P9-3 command and suggestion parity: "unsupported commands report
 * intentional/unverified status").
 *
 * <p>The constructor enforces the acceptance criterion directly rather than
 * relying on convention: a {@link CommandParityStatus#SUPPORTED} entry must
 * name its StoryNPCs equivalent, and any other status must give a rationale.
 * A status can never be silently unexplained.
 */
public final class CommandParityEntry {

    private final String inventoryId;
    private final String targetSymbol;
    private final String targetOperation;
    private final CommandParityStatus status;
    private final String storynpcsEquivalent;
    private final String rationale;

    public CommandParityEntry(String inventoryId, String targetSymbol, String targetOperation,
                               CommandParityStatus status, String storynpcsEquivalent, String rationale) {
        this.inventoryId = requireNonBlank(inventoryId, "inventoryId");
        this.targetSymbol = requireNonBlank(targetSymbol, "targetSymbol");
        this.targetOperation = requireNonBlank(targetOperation, "targetOperation");
        this.status = Objects.requireNonNull(status, "status");

        if (status == CommandParityStatus.SUPPORTED) {
            this.storynpcsEquivalent = requireNonBlank(storynpcsEquivalent,
                    "storynpcsEquivalent is required for a SUPPORTED entry (" + inventoryId + ")");
            this.rationale = rationale;
        } else {
            this.rationale = requireNonBlank(rationale,
                    "rationale is required for a non-SUPPORTED entry (" + inventoryId + ")");
            this.storynpcsEquivalent = storynpcsEquivalent;
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
    public String getTargetOperation() { return targetOperation; }
    public CommandParityStatus getStatus() { return status; }
    public String getStorynpcsEquivalent() { return storynpcsEquivalent; }
    public String getRationale() { return rationale; }
}
