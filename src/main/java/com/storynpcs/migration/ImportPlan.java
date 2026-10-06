package com.storynpcs.migration;

import java.util.List;
import java.util.Map;

import com.storynpcs.domain.common.NamespacedId;

/**
 * P11-1: deterministic, dry-runnable import plan. Steps are ordered by
 * (family, sourceName) so identical inputs always produce identical plans —
 * the plan is the contract a dry-run validates with zero writes.
 */
public record ImportPlan(ImportSource source, ConflictPolicy policy, List<Step> steps,
                         boolean sourceSupported, String abortReason,
                         SourceDocuments sourceDocuments) {

    public static final class SourceDocuments {
        private final Map<Integer, String> documents;

        private SourceDocuments(Map<Integer, String> documents) {
            this.documents = documents == null ? Map.of() : Map.copyOf(documents);
        }

        private static SourceDocuments empty() {
            return new SourceDocuments(Map.of());
        }

        private String get(int sequence) {
            return documents.get(sequence);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SourceDocuments source && documents.equals(source.documents);
        }

        @Override
        public int hashCode() {
            return documents.hashCode();
        }

        @Override
        public String toString() {
            return "SourceDocuments[documentCount=" + documents.size() + "]";
        }
    }

    /** One document decision — recorded whether or not it will be applied. */
    public record Step(
            int sequence,
            String family,
            String sourceName,
            NamespacedId definitionId,
            Resolution resolution,
            NamespacedId resolvedId,
            List<FieldMappingRegistry.FieldMapping> fieldMappings,
            List<String> referencedResources,
            String detail) {

        public enum Resolution {
            APPLY_NEW,       // no collision — new definition
            APPLY_RENAME,    // collision resolved to resolvedId
            APPLY_REPLACE,   // collision resolved by overwrite (rollback snapshot required)
            SKIP_CONFLICT,   // collision skipped per policy
            QUARANTINE,      // malformed/unknown-field document — never applied
            ABORT            // FAIL-policy collision or fatal source — apply() refuses all writes
        }

        /** Back-compat constructor — no referenced-resource rows. */
        public Step(int sequence, String family, String sourceName,
                    NamespacedId definitionId, Resolution resolution,
                    NamespacedId resolvedId,
                    List<FieldMappingRegistry.FieldMapping> fieldMappings,
                    String detail) {
            this(sequence, family, sourceName, definitionId, resolution,
                    resolvedId, fieldMappings, List.of(), detail);
        }

        public Step {
            if (sequence < 0) throw new IllegalArgumentException("sequence cannot be negative");
            if (family == null || family.isBlank()) throw new IllegalArgumentException("family required");
            if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName required");
            if (resolution == null) throw new IllegalArgumentException("resolution required");
            fieldMappings = fieldMappings == null ? List.of() : List.copyOf(fieldMappings);
            referencedResources = referencedResources == null
                    ? List.of() : List.copyOf(referencedResources);
            detail = detail == null ? "" : detail;
        }
    }

    public ImportPlan(ImportSource source, ConflictPolicy policy, List<Step> steps,
                      boolean sourceSupported, String abortReason) {
        this(source, policy, steps, sourceSupported, abortReason, SourceDocuments.empty());
    }

    ImportPlan(ImportSource source, ConflictPolicy policy, List<Step> steps,
               boolean sourceSupported, String abortReason, Map<Integer, String> sourceDocuments) {
        this(source, policy, steps, sourceSupported, abortReason, new SourceDocuments(sourceDocuments));
    }

    public ImportPlan {
        if (source == null) throw new IllegalArgumentException("source required");
        if (policy == null) throw new IllegalArgumentException("policy required");
        steps = steps == null ? List.of() : List.copyOf(steps);
        abortReason = abortReason == null ? "" : abortReason;
        sourceDocuments = sourceDocuments == null ? SourceDocuments.empty() : sourceDocuments;
    }

    String sourceDocument(int sequence) {
        return sourceDocuments.get(sequence);
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
