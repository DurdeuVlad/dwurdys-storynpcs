package com.storynpcs.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.storynpcs.creator.template.NpcTemplate;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.migration.ImportPlan.Step;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/**
 * P11-1: deterministic, dry-runnable definition importer.
 *
 * <p>A package is a family-keyed set of documents ({@code family -> file ->
 * content}) mirroring {@code definitions/<family>/*.yaml}. {@link #plan}
 * classifies every field, detects collisions against an {@link ImportSink},
 * and produces an ordered {@link ImportPlan} — no writes. {@link #apply}
 * executes the plan through the sink (the canonical service boundary in
 * production) with snapshot/rollback so a failed import leaves prior state
 * unchanged. Unsupported source kinds never reach the sink.</p>
 *
 * <p>Instances are not thread-safe; imports are serialized by the owning
 * lifecycle like other definition writes.</p>
 */
public final class DefinitionImporter {

    /**
     * Canonical-boundary view of destination state. Production wires this to
     * {@code StoryNpcsApplicationService} + {@code DefinitionRegistry};
     * tests use fakes. Every mutation the importer performs goes through here.
     */
    public interface ImportSink {
        boolean contains(String family, NamespacedId id);
        /** Current definition for rollback snapshots, or null when absent. */
        Object snapshot(String family, NamespacedId id);
        /** Persist through the canonical save path; throws on failure. */
        void save(String family, NamespacedId id, Object definition);
        /** Rollback for definitions this import newly added. */
        void delete(String family, NamespacedId id);
        /** Rollback for definitions this import replaced. */
        void restore(String family, NamespacedId id, Object prior);
        /** Template-package apply targets (P8-1 library). */
        void saveTemplate(NpcTemplate template);
        void deleteTemplate(NamespacedId id);
        NpcTemplate snapshotTemplate(NamespacedId id);
    }

    // Transport locations are loadable via YamlDefinitionLoader (transports/)
    // but intentionally not importable here yet — the sink lacks transport
    // save/delete/snapshot plumbing (P6-3 scope), so packages carrying them
    // quarantine fail-closed rather than partially apply.
    private static final List<String> FAMILIES = List.of("npc", "dialogue", "quest", "faction", "template");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** Full conflict-aware plan; source documents are snapshotted into the returned plan. */
    public ImportPlan plan(ImportSource source, ConflictPolicy policy,
                           Map<String, Map<String, String>> documents, ImportSink sink) {
        if (ImportSource.support(source.kind()) != ImportSource.Support.SUPPORTED) {
            return new ImportPlan(source, policy, List.of(), false,
                    ImportSource.unsupportedReason(source.kind()));
        }
        List<Step> steps = new ArrayList<>();
        Map<Integer, String> sourceDocuments = new LinkedHashMap<>();
        // Scratch registry: probe loads accumulate package IDs so intra-package
        // duplicates are rejected exactly like a definitions-directory load.
        YamlDefinitionLoader probeLoader = new YamlDefinitionLoader(new DefinitionRegistry());
        // IDs claimed by this plan (APPLY_NEW + renamed) — renames within one
        // plan can never collide with each other.
        java.util.Set<String> claimed = new java.util.HashSet<>();
        int seq = 0;
        Map<String, List<FieldMappingRegistry.FieldMapping>> cnpcFields = Map.of();
        if (source.kind() == ImportSource.Kind.CUSTOMNPCS_TEXT_EXPORT) {
            // #194: CNPC text exports arrive as SNBT keyed by export family
            // ("clones", "dialogs", "quests", ...). Clone docs translate to
            // normalized npc YAML; every other family quarantines with an
            // explicit re-scope reason rather than guessing a mapping.
            Map<String, Map<String, String>> normalized = new LinkedHashMap<>();
            Map<String, List<FieldMappingRegistry.FieldMapping>> translated = new LinkedHashMap<>();
            for (String family : sortedKeys(documents)) {
                for (String file : sortedKeys(documents.get(family))) {
                    if (!"clones".equals(family)) {
                        steps.add(new Step(seq++, family, file, null, Step.Resolution.QUARANTINE,
                                null, List.of(), List.of(),
                                "CustomNPCs '" + family + "' text export not mapped — "
                                        + "clone files only (#194 scope)"));
                        continue;
                    }
                    try {
                        var translation = CnpcTextExportTranslator.translateClone(
                                documents.get(family).get(file));
                        normalized.computeIfAbsent("npc", k -> new LinkedHashMap<>())
                                .put(file, translation.yaml());
                        translated.put(file, translation.mappings());
                    } catch (Exception e) {
                        steps.add(new Step(seq++, family, file, null, Step.Resolution.QUARANTINE,
                                null, List.of(), List.of(),
                                "unparseable CNPC SNBT clone: " + e.getMessage()));
                    }
                }
            }
            documents = normalized;
            cnpcFields = translated;
        }
        for (String family : sortedKeys(documents)) {
            for (String file : sortedKeys(documents.get(family))) {
                Step step = FAMILIES.contains(family)
                        ? planDocument(seq, family, file, documents.get(family).get(file),
                                probeLoader, sourceDocuments)
                        : new Step(seq, family, file, null, Step.Resolution.QUARANTINE, null,
                                List.of(), "unknown definition family — nothing applied from this document");
                List<FieldMappingRegistry.FieldMapping> cnpc = cnpcFields.get(file);
                if (cnpc != null && step.fieldMappings() != null) {
                    List<FieldMappingRegistry.FieldMapping> merged = new ArrayList<>(cnpc);
                    merged.addAll(step.fieldMappings());
                    step = new Step(step.sequence(), step.family(), step.sourceName(),
                            step.definitionId(), step.resolution(), step.resolvedId(),
                            merged, step.referencedResources(), step.detail());
                }
                steps.add(resolveConflict(step, policy, sink, claimed));
                seq++;
            }
        }
        boolean abort = steps.stream().anyMatch(s -> s.resolution() == Step.Resolution.ABORT);
        return new ImportPlan(source, policy, List.copyOf(steps), true,
                abort ? "conflict under FAIL policy — import aborted before any write" : "",
                sourceDocuments);
    }

    private Step planDocument(int seq, String family, String file, String content,
                              YamlDefinitionLoader probeLoader, Map<Integer, String> sourceDocuments) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(content == null ? "" : content);
        } catch (IOException e) {
            return new Step(seq, family, file, null, Step.Resolution.QUARANTINE, null, List.of(),
                    "unparseable document: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return new Step(seq, family, file, null, Step.Resolution.QUARANTINE, null, List.of(),
                    "document root is not a YAML mapping");
        }
        List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);
        List<FieldMappingRegistry.FieldMapping> mappings = FieldMappingRegistry.classify(family, fields);
        // Scripts/assets are surfaced even for quarantined documents — the
        // report must show what an import would have pulled in.
        List<String> resources = ReferencedResources.extract(root);
        if (FieldMappingRegistry.hasFatal(mappings)) {
            return new Step(seq, family, file, null, Step.Resolution.QUARANTINE, null, mappings,
                    resources, "document declares fields outside the mapping registry");
        }
        NamespacedId id = extractId(root);
        if (id == null) {
            return new Step(seq, family, file, null, Step.Resolution.QUARANTINE, null, mappings,
                    resources, "document has no valid namespaced id");
        }
        ValidationResult probe = new ValidationResult();
        Object definition = probeLoad(probeLoader, family, content, file, probe);
        if (definition == null || probe.hasErrors()) {
            return new Step(seq, family, file, id, Step.Resolution.QUARANTINE, null, mappings,
                    resources, "schema validation failed: " + oneLine(probe.formatReport()));
        }
        sourceDocuments.put(seq, content == null ? "" : content);
        return new Step(seq, family, file, id, Step.Resolution.APPLY_NEW, id, mappings,
                resources, "");
    }

    private Step resolveConflict(Step s, ConflictPolicy policy, ImportSink sink,
                                 java.util.Set<String> claimed) {
        if (s.resolution() != Step.Resolution.APPLY_NEW || s.definitionId() == null) return s;
        String key = s.family() + "|" + s.definitionId();
        if (!sink.contains(s.family(), s.definitionId()) && !claimed.contains(key)) {
            claimed.add(key);
            return s;
        }
        return switch (policy) {
            case FAIL -> new Step(s.sequence(), s.family(), s.sourceName(), s.definitionId(),
                    Step.Resolution.ABORT, null, s.fieldMappings(), s.referencedResources(),
                    "definition id already exists and policy is FAIL");
            case SKIP -> new Step(s.sequence(), s.family(), s.sourceName(), s.definitionId(),
                    Step.Resolution.SKIP_CONFLICT, null, s.fieldMappings(), s.referencedResources(),
                    "definition id already exists — existing content kept");
            case REPLACE -> new Step(s.sequence(), s.family(), s.sourceName(), s.definitionId(),
                    Step.Resolution.APPLY_REPLACE, s.definitionId(), s.fieldMappings(),
                    s.referencedResources(),
                    "definition id already exists — will overwrite with rollback snapshot");
            case RENAME -> {
                NamespacedId renamed = nextFreeName(s.family(), s.definitionId(), sink, claimed);
                claimed.add(s.family() + "|" + renamed);
                yield new Step(s.sequence(), s.family(), s.sourceName(), s.definitionId(),
                        Step.Resolution.APPLY_RENAME, renamed, s.fieldMappings(),
                        s.referencedResources(), "renamed to avoid collision");
            }
        };
    }

    private NamespacedId nextFreeName(String family, NamespacedId base, ImportSink sink,
                                      java.util.Set<String> claimed) {
        for (int n = 2; n < 10_000; n++) {
            NamespacedId candidate = NamespacedId.of(base + "__import" + n);
            if (!sink.contains(family, candidate)
                    && !claimed.contains(family + "|" + candidate)) return candidate;
        }
        throw new IllegalStateException("no free rename slot for " + base);
    }

    /** Dry-run: the report the same plan would produce — zero sink writes. */
    public ImportReport dryRun(ImportPlan plan) {
        List<ImportReport.StepResult> results = new ArrayList<>();
        for (Step s : plan.steps()) {
            ImportReport.StepResult.Outcome outcome = switch (s.resolution()) {
                case APPLY_NEW, APPLY_RENAME, APPLY_REPLACE -> plan.applicable()
                        ? ImportReport.StepResult.Outcome.WOULD_APPLY
                        : ImportReport.StepResult.Outcome.FAILED;
                case SKIP_CONFLICT -> ImportReport.StepResult.Outcome.SKIPPED;
                case QUARANTINE -> ImportReport.StepResult.Outcome.QUARANTINED;
                case ABORT -> ImportReport.StepResult.Outcome.FAILED;
            };
            results.add(new ImportReport.StepResult(s.family(), s.sourceName(), s.definitionId(),
                    s.resolvedId(), outcome, s.fieldMappings(), s.referencedResources(), s.detail()));
        }
        return new ImportReport(plan.source(), plan.policy(), true, List.copyOf(results),
                "NOT_NEEDED", plan.applicable() ? "" : plan.abortReason());
    }

    /**
     * Execute the plan through the sink. Refuses to write when the plan is
     * not applicable; on a mid-apply failure, deletes definitions this run
     * added and restores snapshots for ones it replaced.
     */
    public ImportReport apply(ImportPlan plan, ImportSink sink) {
        List<ImportReport.StepResult> results =
                new ArrayList<>(java.util.Collections.nCopies(plan.steps().size(), null));
        if (!plan.applicable()) {
            for (int i = 0; i < plan.steps().size(); i++) {
                Step s = plan.steps().get(i);
                var outcome = s.resolution() == Step.Resolution.QUARANTINE
                        ? ImportReport.StepResult.Outcome.QUARANTINED
                        : ImportReport.StepResult.Outcome.FAILED;
                results.set(i, new ImportReport.StepResult(s.family(), s.sourceName(),
                        s.definitionId(), s.resolvedId(), outcome, s.fieldMappings(),
                        s.referencedResources(),
                        s.resolution() == Step.Resolution.ABORT ? s.detail() : "import aborted — no writes"));
            }
            return new ImportReport(plan.source(), plan.policy(), false, List.copyOf(results),
                    "NOT_NEEDED", plan.abortReason().isBlank()
                            ? "source unsupported: " + ImportSource.unsupportedReason(plan.source().kind())
                            : plan.abortReason());
        }
        Step staleTarget = plan.steps().stream()
                .filter(step -> step.resolution() == Step.Resolution.APPLY_NEW
                        || step.resolution() == Step.Resolution.APPLY_RENAME)
                .filter(step -> sink.contains(step.family(), step.resolvedId()))
                .findFirst()
                .orElse(null);
        if (staleTarget != null) {
            return stalePlanReport(plan, staleTarget);
        }
        List<Integer> applied = new ArrayList<>();
        List<Runnable> undo = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            Step s = plan.steps().get(i);
            switch (s.resolution()) {
                case SKIP_CONFLICT -> results.set(i, new ImportReport.StepResult(
                        s.family(), s.sourceName(), s.definitionId(), null,
                        ImportReport.StepResult.Outcome.SKIPPED, s.fieldMappings(),
                        s.referencedResources(), s.detail()));
                case QUARANTINE -> results.set(i, new ImportReport.StepResult(
                        s.family(), s.sourceName(), s.definitionId(), null,
                        ImportReport.StepResult.Outcome.QUARANTINED, s.fieldMappings(),
                        s.referencedResources(), s.detail()));
                case ABORT -> results.set(i, new ImportReport.StepResult(
                        s.family(), s.sourceName(), s.definitionId(), null,
                        ImportReport.StepResult.Outcome.FAILED, s.fieldMappings(),
                        s.referencedResources(), s.detail()));
                default -> {
                    try {
                        Object prior = s.resolution() == Step.Resolution.APPLY_REPLACE
                                ? snapshotOf(s, sink) : null;
                        saveStep(plan, s, sink);
                        applied.add(i);
                        final Object snapshot = prior;
                        undo.add(() -> rollbackStep(s, sink, snapshot));
                        results.set(i, new ImportReport.StepResult(
                                s.family(), s.sourceName(), s.definitionId(), s.resolvedId(),
                                ImportReport.StepResult.Outcome.APPLIED, s.fieldMappings(),
                                s.referencedResources(), s.detail()));
                    } catch (RuntimeException e) {
                        results.set(i, new ImportReport.StepResult(
                                s.family(), s.sourceName(), s.definitionId(), s.resolvedId(),
                                ImportReport.StepResult.Outcome.FAILED, s.fieldMappings(),
                                s.referencedResources(),
                                "apply failed: " + e.getMessage()));
                        boolean restored = rollbackApplied(plan, results, applied, undo);
                        for (int k = i + 1; k < plan.steps().size(); k++) {
                            Step rest = plan.steps().get(k);
                            results.set(k, new ImportReport.StepResult(rest.family(), rest.sourceName(),
                                    rest.definitionId(), rest.resolvedId(),
                                    ImportReport.StepResult.Outcome.SKIPPED, rest.fieldMappings(),
                                    rest.referencedResources(),
                                    "not attempted — earlier step failed"));
                        }
                        return new ImportReport(plan.source(), plan.policy(), false,
                                List.copyOf(results), restored ? "RESTORED" : "ROLLBACK_INCOMPLETE",
                                "step " + s.sequence() + " failed: " + e.getMessage());
                    }
                }
            }
        }
        return new ImportReport(plan.source(), plan.policy(), false, List.copyOf(results),
                "NOT_NEEDED", "");
    }

    private boolean rollbackApplied(ImportPlan plan, List<ImportReport.StepResult> results,
                                    List<Integer> applied, List<Runnable> undo) {
        boolean restored = true;
        for (int u = applied.size() - 1; u >= 0; u--) {
            int index = applied.get(u);
            ImportReport.StepResult previous = results.get(index);
            try {
                undo.get(u).run();
                results.set(index, new ImportReport.StepResult(previous.family(), previous.sourceName(),
                        previous.definitionId(), previous.resolvedId(),
                        ImportReport.StepResult.Outcome.ROLLED_BACK, previous.fieldMappings(),
                        previous.referencedResources(),
                        "rolled back after later failure"));
            } catch (RuntimeException undoError) {
                restored = false;
                results.set(index, new ImportReport.StepResult(previous.family(), previous.sourceName(),
                        previous.definitionId(), previous.resolvedId(),
                        ImportReport.StepResult.Outcome.FAILED, previous.fieldMappings(),
                        previous.referencedResources(),
                        "rollback failed: " + undoError.getMessage()));
            }
        }
        return restored;
    }

    private Object snapshotOf(Step s, ImportSink sink) {
        return "template".equals(s.family())
                ? sink.snapshotTemplate(s.resolvedId())
                : sink.snapshot(s.family(), s.resolvedId());
    }

    private void saveStep(ImportPlan plan, Step step, ImportSink sink) {
        String content = plan.sourceDocument(step.sequence());
        if (content == null) {
            throw new IllegalStateException("plan step " + step.sequence() + " has no source document");
        }
        ValidationResult validation = new ValidationResult();
        Object definition = probeLoad(new YamlDefinitionLoader(new DefinitionRegistry()),
                step.family(), content, step.sourceName(), validation);
        if (definition == null || validation.hasErrors()) {
            throw new IllegalStateException("plan step " + step.sequence()
                    + " no longer parses: " + oneLine(validation.formatReport()));
        }
        if (step.resolution() == Step.Resolution.APPLY_RENAME) {
            retargetId(definition, step.family(), step.resolvedId());
        }
        if ("template".equals(step.family())) {
            sink.saveTemplate((NpcTemplate) definition);
        } else {
            sink.save(step.family(), step.resolvedId(), definition);
        }
    }

    private ImportReport stalePlanReport(ImportPlan plan, Step conflictingStep) {
        List<ImportReport.StepResult> results = new ArrayList<>();
        for (Step step : plan.steps()) {
            ImportReport.StepResult.Outcome outcome = switch (step.resolution()) {
                case SKIP_CONFLICT -> ImportReport.StepResult.Outcome.SKIPPED;
                case QUARANTINE -> ImportReport.StepResult.Outcome.QUARANTINED;
                case APPLY_NEW, APPLY_RENAME, APPLY_REPLACE, ABORT -> ImportReport.StepResult.Outcome.FAILED;
            };
            String detail = step == conflictingStep
                    ? "destination changed after planning; create a new plan"
                    : "no writes performed because a destination changed after planning";
            results.add(new ImportReport.StepResult(step.family(), step.sourceName(), step.definitionId(),
                    step.resolvedId(), outcome, step.fieldMappings(),
                    step.referencedResources(), detail));
        }
        return new ImportReport(plan.source(), plan.policy(), false, List.copyOf(results),
                "NOT_NEEDED", "destination changed after planning; no writes were performed");
    }

    private void rollbackStep(Step s, ImportSink sink, Object prior) {
        if (s.resolution() == Step.Resolution.APPLY_REPLACE && prior != null) {
            sink.restore(s.family(), s.resolvedId(), prior);
        } else if ("template".equals(s.family())) {
            sink.deleteTemplate(s.resolvedId());
        } else {
            sink.delete(s.family(), s.resolvedId());
        }
    }

    private Object probeLoad(YamlDefinitionLoader loader, String family, String content,
                             String file, ValidationResult result) {
        return switch (family) {
            case "npc" -> loader.loadNpc(content, file, result);
            case "dialogue" -> loader.loadDialogue(content, file, result);
            case "quest" -> loader.loadQuest(content, file, result);
            case "faction" -> loader.loadFaction(content, file, result);
            case "template" -> loader.loadTemplate(content, file, result);
            default -> null;
        };
    }

    private NamespacedId extractId(JsonNode root) {
        JsonNode id = root.get("id");
        if (id == null || !id.isTextual()) return null;
        try {
            return NamespacedId.of(id.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void retargetId(Object definition, String family, NamespacedId newId) {
        switch (family) {
            case "npc" -> ((NpcDefinition) definition).setId(newId);
            case "dialogue" -> ((DialogueGraph) definition).setId(newId);
            case "quest" -> ((Quest) definition).setId(newId);
            case "faction" -> ((Faction) definition).setId(newId);
            case "template" -> ((NpcTemplate) definition).setId(newId);
            default -> { }
        }
    }

    private static String oneLine(String report) {
        return report == null ? "" : report.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static List<String> sortedKeys(Map<String, ?> map) {
        return map.keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }
}
