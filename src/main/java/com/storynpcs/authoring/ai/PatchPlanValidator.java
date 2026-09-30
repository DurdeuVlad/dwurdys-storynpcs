package com.storynpcs.authoring.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;

/**
 * Dry-run patch-plan validation (P10-2). Reports errors/warnings/dependencies
 * WITHOUT writing anything — apply is a separate step that routes through the
 * canonical boundary and is expected-revision-checked.
 */
public final class PatchPlanValidator {

    public record DryRunReport(
            ValidationResult diagnostics,
            List<String> operationSummary,
            List<NamespacedId> dependencies) {}

    public DryRunReport dryRun(PatchPlan plan, SchemaBundle bundle,
                               Set<String> existingIds, long currentRevision) {
        ValidationResult result = new ValidationResult();
        List<NamespacedId> dependencies = new java.util.ArrayList<>();

        if (plan.baseRevision() != currentRevision) {
            result.addError("PATCH_STALE_BASE",
                    "plan base revision " + plan.baseRevision() + " != current " + currentRevision);
        }
        for (PatchPlan.PatchOp op : plan.deduplicated().ops()) {
            String loc = op.sourceLocation() == null ? "?" : op.sourceLocation();
            if (op.op() == null || op.op().isBlank()) {
                result.addError("PATCH_BAD_OP", "empty op at " + loc);
                continue;
            }
            if (!bundle.families().containsKey(op.family())) {
                result.addError("PATCH_UNKNOWN_FAMILY",
                        "unknown family '" + op.family() + "' at " + loc);
                continue;
            }
            var family = bundle.families().get(op.family());
            if (!family.operations().contains(op.op())
                    && !Set.of("set").contains(op.op())) {
                result.addError("PATCH_UNSUPPORTED_OP",
                        "op '" + op.op() + "' unsupported for family '" + op.family() + "' at " + loc);
            }
            if (op.field() != null && !op.field().isBlank()) {
                for (String issue : bundle.unsupported(op.family(), op.field())) {
                    result.addError("PATCH_UNSUPPORTED_FIELD", issue + " at " + loc);
                }
            }
            try {
                NamespacedId id = NamespacedId.of(op.targetId());
                switch (op.op()) {
                    case "create" -> {
                        if (existingIds.contains(id.toString())) {
                            result.addWarning("PATCH_ALREADY_EXISTS",
                                    "target already exists at " + loc + ": " + id);
                        }
                    }
                    case "update", "delete", "set", "grant" -> {
                        if (!existingIds.contains(id.toString())) {
                            result.addWarning("PATCH_TARGET_MISSING",
                                    "target not present at " + loc + ": " + id);
                        }
                    }
                    default -> { /* unknown ops already reported */ }
                }
                if ("create".equals(op.op()) && op.payload() != null
                        && op.payload().contains("storynpcs:")) {
                    // Cross-references inside payloads become dependencies.
                    var matcher = java.util.regex.Pattern.compile("storynpcs:[a-z0-9_./-]+")
                            .matcher(op.payload());
                    while (matcher.find()) {
                        dependencies.add(NamespacedId.of(matcher.group()));
                    }
                }
            } catch (RuntimeException bad) {
                result.addError("PATCH_BAD_TARGET",
                        "invalid target id '" + op.targetId() + "' at " + loc);
            }
        }
        return new DryRunReport(result,
                plan.deterministicOrder().stream()
                        .map(o -> o.op() + " " + o.family() + " " + o.targetId()
                                + (o.field().isBlank() ? "" : "." + o.field()))
                        .toList(),
                dependencies.stream().distinct().toList());
    }
}
