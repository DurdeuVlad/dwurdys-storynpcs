package com.storynpcs.migration;

import java.util.List;

import com.storynpcs.domain.common.NamespacedId;

/**
 * P11-1: deterministic, dry-runnable import plan. Steps are ordered by
 * (family, sourceName) so identical inputs always produce identical plans —
 * the plan is the contract a dry-run validates with zero writes.
 */
public record ImportPlan(ImportSource source, ConflictPolicy policy, List<Step> steps,
                         boolean sourceSupported, String abortReason) {

    /** One document decision — recorded whether or not it will be applied. */
    public record Step(
            int sequence,
            String family,
            String sourceName,
            NamespacedId definitionId,
            Resolution resolution,
            NamespacedId resolvedId,
            List<FieldMappingRegistry.FieldMapping> fieldMappings,
            String detail) {

        public enum Resolution {
            APPLY_NEW,       // no collision — new definition
            APPLY_RENAME,    // collision resolved to resolvedId
            APPLY_REPLACE,   // collision resolved by overwrite (rollback snapshot required)
            SKIP_CONFLICT,   // collision skipped per policy
            QUARANTINE,      // malformed/unknown-field document — never applied
            ABORT            // FAIL-policy collision or fatal source — apply() refuses all writes
        }

        public Step {
            if (sequence < 0) throw new IllegalArgumentException("sequence cannot be negative");
            if (family == null || family.isBlank()) throw new IllegalArgumentException("family required");
            if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName required");
            if (resolution == null) throw new IllegalArgumentException("resolution required");
            fieldMappings = fieldMappings == null ? List.of() : List.copyOf(fieldMappings);
            detail = detail == null ? "" : detail;
        }
    }

    public ImportPlan {
        if (source == null) throw new IllegalArgumentException("source required");
        if (policy == null) throw new IllegalArgumentException("policy required");
        steps = steps == null ? List.of() : List.copyOf(steps);
        abortReason = abortReason == null ? "" : abortReason;
    }

    /** True when apply() may write: supported source and no ABORT resolution. */
    public boolean applicable() {
        return sourceSupported && abortReason.isBlank()
                && steps.stream().noneMatch(s -> s.resolution() == Step.Resolution.ABORT);
    }

    public long countResolutions(Step.Resolution resolution) {
        return steps.stream().filter(s -> s.resolution() == resolution).count();
    }
}
