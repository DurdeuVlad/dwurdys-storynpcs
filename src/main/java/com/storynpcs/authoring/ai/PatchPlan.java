package com.storynpcs.authoring.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;

/**
 * Deterministic, idempotent AI patch plan (P10-2). Ops are keyed
 * (family+target+op+payloadHash) — replaying a plan never double-applies an op.
 * Every op names its source location so errors trace back to generated text.
 */
public record PatchPlan(
        @JsonProperty String planId,
        @JsonProperty long baseRevision,
        @JsonProperty List<PatchOp> ops) {

    public PatchPlan {
        ops = ops == null ? List.of() : List.copyOf(ops);
    }

    /** A single patch operation against a definition family. */
    public record PatchOp(
            @JsonProperty String op,          // create|update|delete|set|grant
            @JsonProperty String family,      // npc|dialogue|quest|...
            @JsonProperty String targetId,    // namespaced id
            @JsonProperty String field,       // field being mutated ("" for whole-def ops)
            @JsonProperty String payload,     // serialized value
            @JsonProperty String sourceLocation) { // generated-text provenance

        /** Idempotency key: identical ops collapse regardless of order. */
        public String idempotencyKey() {
            return op + "|" + family + "|" + targetId + "|" + field + "|" + Integer.toHexString(
                    payload == null ? 0 : payload.hashCode());
        }
    }

    /** Deterministic op order: sorted by idempotency key — replays are identical. */
    public List<PatchOp> deterministicOrder() {
        return ops.stream()
                .sorted(Comparator.comparing(PatchOp::idempotencyKey))
                .toList();
    }

    /**
     * Apply order: deterministic, and on the same (family,target) creates run
     * before updates/sets and deletes run last — a plan that creates a
     * definition and then sets fields on it applies in dependency order.
     * Cross-target order stays keyed (deterministic replays).
     */
    public List<PatchOp> applicationOrder() {
        return deduplicated().ops().stream()
                .sorted(Comparator.comparing(PatchOp::family)
                        .thenComparing(PatchOp::targetId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(o -> switch (o.op() == null ? "" : o.op()) {
                            case "create" -> 0;
                            case "update", "set", "grant" -> 1;
                            case "delete" -> 2;
                            default -> 3;
                        })
                        .thenComparing(PatchOp::idempotencyKey))
                .toList();
    }

    /** Duplicate ops collapse to one — same key = same effect. */
    public PatchPlan deduplicated() {
        var seen = new java.util.LinkedHashSet<String>();
        List<PatchOp> deduped = new ArrayList<>();
        for (PatchOp op : deterministicOrder()) {
            if (seen.add(op.idempotencyKey())) {
                deduped.add(op);
            }
        }
        return new PatchPlan(planId, baseRevision, deduped);
    }
}
