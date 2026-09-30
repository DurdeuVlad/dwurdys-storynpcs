package com.storynpcs.migration;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.storynpcs.domain.common.NamespacedId;

/**
 * P11-1: deterministic import outcome. Records per-document results, field
 * mappings, conflicts, quarantines and rollback so every field-level decision
 * is auditable and linked to an evidence state.
 */
public record ImportReport(ImportSource source, ConflictPolicy policy, boolean dryRun,
                           List<StepResult> steps, String rollbackOutcome, String failureReason) {

    public record StepResult(String family, String sourceName, NamespacedId definitionId,
                             NamespacedId resolvedId, Outcome outcome,
                             List<FieldMappingRegistry.FieldMapping> fieldMappings, String detail) {

        public enum Outcome {
            WOULD_APPLY,   // dry-run only — no writes performed
            APPLIED,
            SKIPPED,
            QUARANTINED,
            FAILED,
            ROLLED_BACK
        }

        public StepResult {
            fieldMappings = fieldMappings == null ? List.of() : List.copyOf(fieldMappings);
            detail = detail == null ? "" : detail;
        }
    }

    public ImportReport {
        steps = steps == null ? List.of() : List.copyOf(steps);
        rollbackOutcome = rollbackOutcome == null ? "NOT_NEEDED" : rollbackOutcome;
        failureReason = failureReason == null ? "" : failureReason;
    }

    public boolean succeeded() {
        return failureReason.isBlank()
                && steps.stream().noneMatch(s -> s.outcome() == StepResult.Outcome.FAILED);
    }

    public long count(StepResult.Outcome outcome) {
        return steps.stream().filter(s -> s.outcome() == outcome).count();
    }

    /**
     * Deterministic summary table for CLI/UI output and golden-report tests —
     * keys sorted, no iteration-order dependence.
     */
    public Map<String, Object> summaryMap() {
        Map<String, Object> summary = new TreeMap<>();
        summary.put("applied", count(StepResult.Outcome.APPLIED));
        summary.put("conflictPolicy", policy.name());
        summary.put("dryRun", dryRun);
        summary.put("failed", count(StepResult.Outcome.FAILED));
        summary.put("quarantined", count(StepResult.Outcome.QUARANTINED));
        summary.put("rolledBack", count(StepResult.Outcome.ROLLED_BACK));
        summary.put("rollbackOutcome", rollbackOutcome);
        summary.put("skipped", count(StepResult.Outcome.SKIPPED));
        summary.put("source", source.sourceId());
        summary.put("sourceKind", source.kind().name());
        summary.put("steps", steps.size());
        summary.put("succeeded", succeeded());
        summary.put("wouldApply", count(StepResult.Outcome.WOULD_APPLY));
        return Map.copyOf(summary);
    }
}
