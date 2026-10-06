package com.storynpcs.authoring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueGraphSerde;
import com.storynpcs.domain.faction.FactionSerde;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.quest.QuestSerde;
import com.storynpcs.service.CanonicalMutationResult;
import com.storynpcs.service.MutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical apply path for AI patch plans (P10-2). Every op goes through the
 * {@link StoryNpcsApplicationService} request boundary — per-definition
 * expected-revision checks, capability enforcement, and idempotent request
 * ids are inherited from P1-1/P1-4. A failing op rolls back everything the
 * plan already applied (snapshots restored through the same canonical ops),
 * so a failed apply leaves the prior revision intact.
 *
 * Apply scope is the definition spine — npc, dialogue, quest, faction — the
 * families with canonical create/mutate/replace/delete coverage. Other bundle
 * families reject explicitly with PATCH_APPLY_SCOPE; dry-run still validates
 * their schema.
 */
public final class PatchPlanApplier {

    private static final Set<String> APPLY_FAMILIES = Set.of("npc", "dialogue", "quest", "faction");
    private static final Set<String> APPLY_OPS = Set.of("create", "update", "set", "delete");

    /**
     * Scalar-leaf {@code set} paths: logical bundle field → JSON path inside
     * the family's serialized definition. Composite fields (display, stats,
     * nodes, objectives, ...) have no leaf path — they apply via whole-
     * definition {@code update}. A field missing here is a
     * PATCH_APPLY_FIELD diagnostic, never a silently-ignored write.
     */
    private static final java.util.Map<String, java.util.Map<String, String>> SET_PATHS =
            java.util.Map.of(
                    "npc", java.util.Map.of(
                            "name", "display.name",
                            "factionId", "factionId",
                            "dialogueId", "dialogueId"),
                    "dialogue", java.util.Map.of(
                            "title", "title",
                            "entryNodeId", "entryNodeId"),
                    "quest", java.util.Map.of(
                            "title", "title",
                            "description", "description",
                            "category", "category",
                            "repeatType", "repeatType"),
                    "faction", java.util.Map.of(
                            "name", "name",
                            "defaultPoints", "defaultPoints",
                            "hostileThreshold", "hostileThreshold",
                            "friendlyThreshold", "friendlyThreshold",
                            "color", "color",
                            "passive", "passive"));
    /** Payloads may be JSON or YAML text — the YAML reader accepts both. */
    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper(new YAMLFactory());
    /** Canonical family serdes consume JSON — normalized payloads write JSON. */
    private static final ObjectMapper PAYLOAD_WRITER = new ObjectMapper();

    private final StoryNpcsApplicationService service;
    private final com.storynpcs.yaml.DefinitionRegistry registry;
    private final PatchPlanValidator validator = new PatchPlanValidator();

    public PatchPlanApplier(StoryNpcsApplicationService service,
                            com.storynpcs.yaml.DefinitionRegistry registry) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
    }

    public record ApplyReport(
            ValidationResult diagnostics,
            List<PatchPlan.PatchOp> appliedOps,
            List<PatchPlan.PatchOp> rolledBackOps,
            boolean committed) {}

    /**
     * Applies {@code plan} after a mandatory dry-run. {@code permissionLevel}
     * is the caller's proven level (the command surface requires 2).
     */
    public ApplyReport apply(PatchPlan plan, SchemaBundle bundle,
                             Set<String> existingIds, long currentRevision,
                             int permissionLevel) {
        ValidationResult diagnostics = new ValidationResult();
        var dryRun = validator.dryRun(plan, bundle, existingIds, currentRevision);
        if (dryRun.diagnostics().hasErrors()) {
            dryRun.diagnostics().getDiagnostics().forEach(
                    d -> diagnostics.addError(d.code(), d.message()));
            return new ApplyReport(diagnostics, List.of(), List.of(), false);
        }

        List<PatchPlan.PatchOp> applied = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        for (PatchPlan.PatchOp op : plan.deduplicated().deterministicOrder()) {
            String loc = op.sourceLocation() == null ? "?" : op.sourceLocation();
            if (!APPLY_FAMILIES.contains(op.family())) {
                diagnostics.addError("PATCH_APPLY_SCOPE",
                        "family '" + op.family() + "' is outside the apply scope at " + loc
                                + " — use the canonical " + op.family() + " commands");
                return rollback(plan, applied, snapshots, diagnostics, permissionLevel);
            }
            if (!APPLY_OPS.contains(op.op())) {
                diagnostics.addError("PATCH_APPLY_OP",
                        "op '" + op.op() + "' cannot apply at " + loc
                                + " — apply supports create|update|set|delete only");
                return rollback(plan, applied, snapshots, diagnostics, permissionLevel);
            }
            NamespacedId id;
            try {
                id = NamespacedId.of(op.targetId());
            } catch (RuntimeException bad) {
                diagnostics.addError("PATCH_BAD_TARGET",
                        "invalid target id '" + op.targetId() + "' at " + loc);
                return rollback(plan, applied, snapshots, diagnostics, permissionLevel);
            }

            String snapshot = snapshot(op.family(), id);
            CanonicalMutationResult result;
            try {
                result = dispatch(op, id, snapshot, permissionLevel);
            } catch (UnsettableFieldException unsettable) {
                diagnostics.addError("PATCH_APPLY_FIELD",
                        unsettable.getMessage() + " (plan op at " + loc + ")");
                return rollback(plan, applied, snapshots, diagnostics, permissionLevel);
            }
            if (!result.applied()) {
                result.diagnostics().getDiagnostics().forEach(
                        d -> diagnostics.addError(d.code(),
                                d.message() + " (plan op at " + loc + ")"));
                return rollback(plan, applied, snapshots, diagnostics, permissionLevel);
            }
            // A delete of an absent target is an idempotent no-op — nothing to
            // roll back (restoring would delete a target that never existed).
            if (!(snapshot == null && "delete".equals(op.op()))) {
                applied.add(op);
                snapshots.add(snapshot);
            }
        }
        return new ApplyReport(diagnostics, List.copyOf(applied), List.of(), true);
    }

    /** Restores every applied op's pre-apply snapshot in reverse order. */
    private ApplyReport rollback(PatchPlan plan, List<PatchPlan.PatchOp> applied,
                                 List<String> snapshots, ValidationResult diagnostics,
                                 int permissionLevel) {
        List<PatchPlan.PatchOp> rolledBack = new ArrayList<>();
        for (int i = applied.size() - 1; i >= 0; i--) {
            PatchPlan.PatchOp op = applied.get(i);
            String snapshot = snapshots.get(i);
            NamespacedId id = NamespacedId.of(op.targetId());
            CanonicalMutationResult undo = snapshot == null
                    ? dispatchDelete(op.family(), id, permissionLevel)
                    : dispatchReplace(op.family(), id, snapshot, permissionLevel);
            if (undo.applied()) {
                rolledBack.add(op);
            } else {
                diagnostics.addError("ROLLBACK_INCOMPLETE",
                        "rollback of '" + op.op() + " " + op.targetId() + "' failed: "
                                + undo.diagnostics().formatReport(3));
            }
        }
        return new ApplyReport(diagnostics, List.copyOf(applied),
                List.copyOf(rolledBack), false);
    }

    private CanonicalMutationResult dispatch(PatchPlan.PatchOp op, NamespacedId id,
                                             String snapshot, int permissionLevel) {
        return switch (op.op()) {
            case "create" -> dispatchCreate(op, id, permissionLevel);
            case "update", "set" -> {
                if (snapshot == null) {
                    ValidationResult missing = ValidationResult.valid();
                    missing.addError("PATCH_TARGET_MISSING",
                            "cannot " + op.op() + " absent definition " + id);
                    yield new CanonicalMutationResult(false, false, 0, missing);
                }
                String json = "set".equals(op.op())
                        ? mergedFieldPayload(op.family(), id, snapshot, op.field(), op.payload())
                        : normalizedPayload(op.payload());
                if (json == null) {
                    ValidationResult bad = ValidationResult.valid();
                    bad.addError("PATCH_BAD_PAYLOAD",
                            "payload for " + op.op() + " " + id + " is not valid YAML/JSON");
                    yield new CanonicalMutationResult(false, false, 0, bad);
                }
                yield dispatchReplace(op.family(), id, json, permissionLevel);
            }
            case "delete" -> snapshot == null
                    ? noopSuccess()
                    : dispatchDelete(op.family(), id, permissionLevel);
            default -> throw new IllegalStateException("unreachable op " + op.op());
        };
    }

    private static CanonicalMutationResult noopSuccess() {
        return new CanonicalMutationResult(true, false, 0, ValidationResult.valid());
    }

    /** Parses the payload (JSON or YAML) into canonical family JSON. */
    private String normalizedPayload(String payload) {
        try {
            JsonNode tree = PAYLOAD_READER.readTree(payload == null ? "" : payload);
            return tree == null ? null : PAYLOAD_WRITER.writeValueAsString(tree);
        } catch (Exception e) {
            return null;
        }
    }

    /** Field-level set: overlay the scalar leaf resolved from {@code field}. */
    private String mergedFieldPayload(String family, NamespacedId id, String snapshotJson,
                                      String field, String payload) {
        String path = SET_PATHS.getOrDefault(family, java.util.Map.of()).get(field);
        if (path == null) {
            // No scalar leaf — signal via sentinel; caller converts to a
            // diagnostic that names the supported leaf fields.
            throw new UnsettableFieldException(family, field);
        }
        try {
            JsonNode base = PAYLOAD_READER.readTree(snapshotJson);
            JsonNode value = PAYLOAD_READER.readTree(payload == null ? "" : payload);
            if (!(base instanceof com.fasterxml.jackson.databind.node.ObjectNode root)) {
                return null;
            }
            var node = root;
            String[] segments = path.split("\\.");
            for (int i = 0; i < segments.length - 1; i++) {
                JsonNode child = node.get(segments[i]);
                if (!(child instanceof com.fasterxml.jackson.databind.node.ObjectNode childObj)) {
                    return null;
                }
                node = childObj;
            }
            node.set(segments[segments.length - 1],
                    value == null ? com.fasterxml.jackson.databind.node.TextNode.valueOf("") : value);
            return PAYLOAD_WRITER.writeValueAsString(root);
        } catch (UnsettableFieldException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /** Thrown when a set-op field has no scalar leaf path in its family. */
    private static final class UnsettableFieldException extends RuntimeException {
        UnsettableFieldException(String family, String field) {
            super("field '" + field + "' has no scalar set path in family '" + family
                    + "' — apply it via a whole-definition update");
        }
    }

    private MutationRequest request(String operation, String capability,
                                    NamespacedId id, int permissionLevel) {
        return new MutationRequest(operation, "command", capability, id,
                service.currentRevision(familyOf(operation), id), UUID.randomUUID(), permissionLevel);
    }

    private static String familyOf(String operation) {
        return operation.substring(0, operation.indexOf('.'));
    }

    private CanonicalMutationResult dispatchCreate(PatchPlan.PatchOp op, NamespacedId id,
                                                   int permissionLevel) {
        String json = normalizedPayload(op.payload());
        if (json == null) {
            ValidationResult bad = ValidationResult.valid();
            bad.addError("PATCH_BAD_PAYLOAD",
                    "create payload for " + id + " is not valid YAML/JSON");
            return new CanonicalMutationResult(false, false, 0, bad);
        }
        return switch (op.family()) {
            case "npc" -> NpcDefinitionSerde.fromJson(json)
                    .map(def -> service.createNpc(
                            request("npc.create", "npc.mutate", id, permissionLevel), def))
                    .orElseGet(() -> badPayload("npc", id));
            case "dialogue" -> DialogueGraphSerde.fromJson(json)
                    .map(def -> service.createDialogue(
                            request("dialogue.create", "dialogue.mutate", id, permissionLevel), def))
                    .orElseGet(() -> badPayload("dialogue", id));
            case "faction" -> FactionSerde.fromJson(json)
                    .map(def -> {
                        var created = service.createFaction(
                                request("faction.create", "faction.mutate", id, permissionLevel),
                                def.getName() == null ? id.getPath() : def.getName());
                        if (!created.applied()) {
                            return created;
                        }
                        return service.replaceFaction(
                                request("faction.replace", "faction.edit", id, permissionLevel), def);
                    })
                    .orElseGet(() -> badPayload("faction", id));
            case "quest" -> QuestSerde.fromJson(json)
                    .map(def -> {
                        var created = service.createQuest(
                                request("quest.create", "quest.mutate", id, permissionLevel),
                                def.getTitle() == null ? id.getPath() : def.getTitle());
                        if (!created.applied()) {
                            return created;
                        }
                        return service.replaceQuest(
                                request("quest.replace", "quest.edit", id, permissionLevel), def);
                    })
                    .orElseGet(() -> badPayload("quest", id));
            default -> throw new IllegalStateException("unreachable family " + op.family());
        };
    }

    private CanonicalMutationResult dispatchReplace(String family, NamespacedId id,
                                                    String json, int permissionLevel) {
        return switch (family) {
            case "npc" -> NpcDefinitionSerde.fromJson(json)
                    .map(def -> service.replaceNpc(
                            request("npc.replace", "npc.edit", id, permissionLevel), def))
                    .orElseGet(() -> badPayload(family, id));
            case "dialogue" -> DialogueGraphSerde.fromJson(json)
                    .map(def -> service.replaceDialogue(
                            request("dialogue.replace", "dialogue.edit", id, permissionLevel), def))
                    .orElseGet(() -> badPayload(family, id));
            case "quest" -> QuestSerde.fromJson(json)
                    .map(def -> service.replaceQuest(
                            request("quest.replace", "quest.edit", id, permissionLevel), def))
                    .orElseGet(() -> badPayload(family, id));
            case "faction" -> FactionSerde.fromJson(json)
                    .map(def -> service.replaceFaction(
                            request("faction.replace", "faction.edit", id, permissionLevel), def))
                    .orElseGet(() -> badPayload(family, id));
            default -> throw new IllegalStateException("unreachable family " + family);
        };
    }

    private CanonicalMutationResult dispatchDelete(String family, NamespacedId id,
                                                   int permissionLevel) {
        return switch (family) {
            case "npc" -> service.deleteNpc(request("npc.delete", "npc.delete", id, permissionLevel));
            case "dialogue" -> service.deleteDialogue(
                    request("dialogue.delete", "dialogue.delete", id, permissionLevel));
            case "quest" -> service.deleteQuest(request("quest.delete", "quest.delete", id, permissionLevel));
            case "faction" -> service.deleteFaction(
                    request("faction.delete", "faction.delete", id, permissionLevel));
            default -> throw new IllegalStateException("unreachable family " + family);
        };
    }

    private String snapshot(String family, NamespacedId id) {
        return switch (family) {
            case "npc" -> registry.getNpc(id).map(NpcDefinitionSerde::toJson).orElse(null);
            case "dialogue" -> registry.getDialogue(id).map(DialogueGraphSerde::toJson).orElse(null);
            case "quest" -> registry.getQuest(id).map(QuestSerde::toJson).orElse(null);
            case "faction" -> registry.getFaction(id).map(FactionSerde::toJson).orElse(null);
            default -> null;
        };
    }

    private static CanonicalMutationResult badPayload(String family, NamespacedId id) {
        ValidationResult bad = ValidationResult.valid();
        bad.addError("PATCH_BAD_PAYLOAD",
                "payload does not deserialize as a " + family + " definition for " + id);
        return new CanonicalMutationResult(false, false, 0, bad);
    }
}
