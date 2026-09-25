package com.storynpcs.service;

import com.storynpcs.domain.common.ValidationResult;

import java.util.List;

/** Machine-readable result for replay-safe canonical mutations. */
public record CanonicalMutationResult(
        boolean applied,
        boolean duplicate,
        long revision,
        ValidationResult diagnostics,
        List<String> events,
        String recoveryOutcome) {

    public CanonicalMutationResult(boolean applied, boolean duplicate, long revision,
                                   ValidationResult diagnostics) {
        this(applied, duplicate, revision, diagnostics, List.of(),
                applied ? "COMMITTED" : "REJECTED_NO_SIDE_EFFECTS");
    }

    public CanonicalMutationResult {
        diagnostics = diagnostics == null ? ValidationResult.valid() : diagnostics.copy();
        events = events == null ? List.of() : List.copyOf(events);
        recoveryOutcome = recoveryOutcome == null || recoveryOutcome.isBlank()
                ? "UNKNOWN" : recoveryOutcome;
    }

    /** Reports whether this response represents the first committed application of a request. */
    public boolean newlyApplied() {
        return applied && !duplicate;
    }

    CanonicalMutationResult snapshot() {
        return new CanonicalMutationResult(applied, duplicate, revision, diagnostics, events, recoveryOutcome);
    }

    public boolean hasErrors() {
        return diagnostics != null && diagnostics.hasErrors();
    }

    public String formatReport(int maxErrors) {
        return diagnostics == null ? "No diagnostics available." : diagnostics.formatReport(maxErrors);
    }

    public String formatReport() {
        return formatReport(10);
    }
}
