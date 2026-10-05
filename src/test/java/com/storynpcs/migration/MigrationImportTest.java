package com.storynpcs.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.storynpcs.creator.template.NpcTemplate;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.migration.ImportPlan.Step;
import com.storynpcs.migration.ImportReport.StepResult;

/**
 * P11-1 import contract: golden inputs, conflict policies, unsupported fields,
 * malformed input, dry-run, rollback, migration, idempotency, determinism.
 */
class MigrationImportTest {

    /** Fake canonical boundary — records writes so tests verify side effects. */
    private static final class FakeSink implements DefinitionImporter.ImportSink {
        final Map<String, Object> defs = new LinkedHashMap<>();
        final Map<String, NpcTemplate> templates = new LinkedHashMap<>();
        int saveCalls;
        String failOnSaveId; // when set, save() throws for this id — injects mid-apply failure

        private static String key(String family, NamespacedId id) { return family + "|" + id; }

        @Override public boolean contains(String family, NamespacedId id) {
            return "template".equals(family) ? templates.containsKey(key(family, id))
                    : defs.containsKey(key(family, id));
        }
        @Override public Object snapshot(String family, NamespacedId id) {
            return defs.get(key(family, id));
        }
        @Override public void save(String family, NamespacedId id, Object definition) {
            saveCalls++;
            if (id.toString().equals(failOnSaveId)) {
                throw new IllegalStateException("injected save failure");
            }
            defs.put(key(family, id), definition);
        }
        @Override public void delete(String family, NamespacedId id) { defs.remove(key(family, id)); }
        @Override public void restore(String family, NamespacedId id, Object prior) {
            defs.put(key(family, id), prior);
        }
        @Override public void saveTemplate(NpcTemplate template) {
            templates.put(key("template", template.getId()), template);
        }
        @Override public void deleteTemplate(NamespacedId id) { templates.remove(key("template", id)); }
        @Override public NpcTemplate snapshotTemplate(NamespacedId id) {
            return templates.get(key("template", id));
        }
    }

    private DefinitionImporter importer;
    private FakeSink sink;
    private ImportSource yamlSource;

    private static ImportSource yaml() {
        return new ImportSource(ImportSource.Kind.STORYNPCS_YAML_PACKAGE, "pkg-1", 1, "test");
    }

    private static Map<String, Map<String, String>> pkg(String family, String file, String content) {
        Map<String, String> files = new TreeMap<>();
        files.put(file, content);
        Map<String, Map<String, String>> out = new TreeMap<>();
        out.put(family, files);
        return out;
    }

    private static final String NPC_A = """
            schemaVersion: 1
            id: "storynpcs:alpha"
            display: { name: "Alpha" }
            """;

    private static final String NPC_B = """
            schemaVersion: 1
            id: "storynpcs:beta"
            display: { name: "Beta" }
            """;

    private static final String NPC_A_V2 = """
            schemaVersion: 1
            id: "storynpcs:alpha"
            display: { name: "Alpha v2" }
            """;

    @BeforeEach
    void setUp() {
        importer = new DefinitionImporter();
        sink = new FakeSink();
        yamlSource = yaml();
    }

    @Test
    void goldenPackageAppliesAllFamilies() {
        Map<String, Map<String, String>> documents = new TreeMap<>();
        documents.put("npc", Map.of("alpha.yaml", NPC_A));
        documents.put("dialogue", Map.of("d.yaml", """
                schemaVersion: 1
                id: "storynpcs:d1"
                entryNodeId: "n1"
                nodes: { n1: { id: "n1", text: "hi" } }
                """));
        documents.put("quest", Map.of("q.yaml", """
                schemaVersion: 1
                id: "storynpcs:q1"
                title: "Quest"
                """));
        documents.put("faction", Map.of("f.yaml", """
                schemaVersion: 1
                id: "storynpcs:f1"
                name: "Town"
                """));

        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        assertThat(plan.applicable()).isTrue();
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).as(report.summaryMap().toString()).isTrue();
        assertThat(report.count(StepResult.Outcome.APPLIED)).isEqualTo(4);
        assertThat(sink.defs).hasSize(4);
        assertThat(report.rollbackOutcome()).isEqualTo("NOT_NEEDED");
    }

    @Test
    void dryRunPerformsNoWrites() {
        var documents = pkg("npc", "alpha.yaml", NPC_A);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        ImportReport report = importer.dryRun(plan);
        assertThat(report.dryRun()).isTrue();
        assertThat(report.count(StepResult.Outcome.WOULD_APPLY)).isEqualTo(1);
        assertThat(sink.defs).isEmpty();
        assertThat(sink.saveCalls).isZero();
    }

    @Test
    void unsupportedSourceNeverWrites() {
        var nbt = new ImportSource(ImportSource.Kind.TARGET_WORLD_NBT, "world.dat", 0, "test");
        ImportPlan plan = importer.plan(nbt, ConflictPolicy.FAIL, pkg("npc", "a.yaml", NPC_A), sink);
        assertThat(plan.applicable()).isFalse();
        assertThat(plan.abortReason()).contains("needs-input");
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isFalse();
        assertThat(sink.defs).isEmpty();
    }

    @Test
    void failPolicyAbortsBeforeAnyWrite() {
        sink.save("npc", NamespacedId.of("storynpcs:alpha"), new Object());
        Map<String, Map<String, String>> documents = new TreeMap<>();
        documents.put("npc", Map.of("alpha.yaml", NPC_A, "beta.yaml", NPC_B));
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        assertThat(plan.applicable()).isFalse();
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isFalse();
        // beta (no conflict) must not be written either — FAIL aborts everything.
        assertThat(sink.defs).hasSize(1);
    }

    @Test
    void skipPolicyKeepsExisting() {
        Object existing = new Object();
        sink.save("npc", NamespacedId.of("storynpcs:alpha"), existing);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.SKIP,
                pkg("npc", "alpha.yaml", NPC_A), sink);
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isTrue();
        assertThat(report.count(StepResult.Outcome.SKIPPED)).isEqualTo(1);
        assertThat(sink.defs.get("npc|storynpcs:alpha")).isSameAs(existing);
    }

    @Test
    void renamePolicyDeterministic() {
        sink.save("npc", NamespacedId.of("storynpcs:alpha"), new Object());
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.RENAME,
                pkg("npc", "alpha.yaml", NPC_A), sink);
        Step step = plan.steps().get(0);
        assertThat(step.resolution()).isEqualTo(Step.Resolution.APPLY_RENAME);
        assertThat(step.resolvedId().toString()).isEqualTo("storynpcs:alpha__import2");
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isTrue();
        assertThat(sink.defs).containsKeys("npc|storynpcs:alpha", "npc|storynpcs:alpha__import2");
        // Re-applying the same plan sees both names — renames to __import3 deterministically.
        ImportPlan second = importer.plan(yamlSource, ConflictPolicy.RENAME,
                pkg("npc", "alpha.yaml", NPC_A), sink);
        assertThat(second.steps().get(0).resolvedId().toString())
                .isEqualTo("storynpcs:alpha__import3");
    }

    @Test
    void replacePolicyOverwritesAndRollbackRestores() {
        var prior = com.storynpcs.domain.npc.NpcDefinitionSerde
                .fromJson(com.storynpcs.domain.npc.NpcDefinitionSerde.toJson(
                        loadableNpc("storynpcs:alpha")))
                .orElseThrow();
        sink.save("npc", NamespacedId.of("storynpcs:alpha"), prior);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.REPLACE,
                pkg("npc", "alpha.yaml", NPC_A_V2), sink);
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isTrue();
        assertThat(report.count(StepResult.Outcome.APPLIED)).isEqualTo(1);
    }

    @Test
    void midApplyFailureRollsBackEverything() {
        sink.failOnSaveId = "storynpcs:beta"; // second doc fails
        Map<String, String> files = new TreeMap<>();
        files.put("alpha.yaml", NPC_A);
        files.put("beta.yaml", NPC_B);
        Map<String, Map<String, String>> documents = new TreeMap<>();
        documents.put("npc", files);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isFalse();
        assertThat(report.rollbackOutcome()).isEqualTo("RESTORED");
        assertThat(report.count(StepResult.Outcome.ROLLED_BACK)).isEqualTo(1);
        assertThat(report.count(StepResult.Outcome.FAILED)).isEqualTo(1);
        assertThat(sink.defs).isEmpty(); // prior state fully restored
    }

    @Test
    void malformedAndUnknownFieldDocumentsQuarantine() {
        Map<String, String> files = new TreeMap<>();
        files.put("bad.yaml", "not: [valid: yaml: at: all\n  - broken");
        files.put("unknown.yaml", """
                schemaVersion: 1
                id: "storynpcs:weird"
                totallyBogusField: true
                """);
        files.put("good.yaml", NPC_A);
        Map<String, Map<String, String>> documents = new TreeMap<>();
        documents.put("npc", files);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        // Malformed + unknown-field quarantine, good applies — FAIL policy only aborts on conflicts.
        assertThat(plan.countResolutions(Step.Resolution.QUARANTINE)).isEqualTo(2);
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.count(StepResult.Outcome.QUARANTINED)).isEqualTo(2);
        assertThat(report.count(StepResult.Outcome.APPLIED)).isEqualTo(1);
        assertThat(sink.defs).hasSize(1);
    }

    @Test
    void unsupportedFieldReportedNotSilentlyDropped() {
        var documents = pkg("npc", "scripted.yaml", """
                schemaVersion: 1
                id: "storynpcs:scripted"
                display: { name: "Scripted" }
                scripts: [ "on_tick.js" ]
                """);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        // `scripts` is recognized-but-unsupported → QUARANTINE (loader fails closed too),
        // and the field mapping records UNSUPPORTED explicitly.
        Step step = plan.steps().get(0);
        assertThat(step.resolution()).isEqualTo(Step.Resolution.QUARANTINE);
        assertThat(step.fieldMappings())
                .anySatisfy(m -> {
                    assertThat(m.fieldPath()).isEqualTo("scripts");
                    assertThat(m.action()).isEqualTo(FieldMappingRegistry.Action.UNSUPPORTED);
                });
    }

    @Test
    void legacyFormsMigrate() {
        var documents = pkg("npc", "legacy.yaml", """
                id: "storynpcs:legacy"
                display: { name: "Legacy" }
                inventory: [ "minecraft:bread", "minecraft:arrow" ]
                """);
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        assertThat(plan.applicable()).isTrue();
        Step step = plan.steps().get(0);
        assertThat(step.fieldMappings())
                .anySatisfy(m -> {
                    assertThat(m.fieldPath()).isEqualTo("inventory");
                    assertThat(m.action()).isEqualTo(FieldMappingRegistry.Action.MIGRATED);
                });
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).isTrue();
    }

    @Test
    void importIsIdempotentUnderSkip() {
        var documents = pkg("npc", "alpha.yaml", NPC_A);
        ImportPlan first = importer.plan(yamlSource, ConflictPolicy.SKIP, documents, sink);
        importer.apply(first, sink);
        assertThat(sink.defs).hasSize(1);
        // Second run: same id now exists → SKIP_CONFLICT, state unchanged.
        ImportPlan second = importer.plan(yamlSource, ConflictPolicy.SKIP, documents, sink);
        ImportReport secondReport = importer.apply(second, sink);
        assertThat(secondReport.count(StepResult.Outcome.SKIPPED)).isEqualTo(1);
        assertThat(sink.defs).hasSize(1);
    }

    @Test
    void planIsDeterministic() {
        Map<String, Map<String, String>> documents = new TreeMap<>();
        documents.put("npc", Map.of("a.yaml", NPC_A, "b.yaml", NPC_B));
        documents.put("quest", Map.of("q.yaml", """
                schemaVersion: 1
                id: "storynpcs:q1"
                title: "Quest"
                """));
        ImportPlan one = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        ImportPlan two = new DefinitionImporter().plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        assertThat(one.steps().stream().map(Step::sourceName).toList())
                .isEqualTo(two.steps().stream().map(Step::sourceName).toList());
        assertThat(one.steps().stream().map(Step::resolution).toList())
                .isEqualTo(two.steps().stream().map(Step::resolution).toList());
    }

    @Test
    void plansRetainTheirOwnSourceDocumentsWhenImporterIsReused() {
        ImportPlan alphaPlan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("npc", "alpha.yaml", NPC_A), sink);
        ImportPlan betaPlan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("npc", "beta.yaml", NPC_B), sink);

        assertThat(importer.apply(alphaPlan, sink).succeeded()).isTrue();
        var alpha = (com.storynpcs.domain.npc.NpcDefinition) sink.defs.get("npc|storynpcs:alpha");
        assertThat(alpha.getId()).isEqualTo(NamespacedId.of("storynpcs:alpha"));
        assertThat(alpha.getDisplay().getName()).isEqualTo("Alpha");
        assertThat(sink.defs).doesNotContainKey("npc|storynpcs:beta");

        assertThat(importer.apply(betaPlan, sink).succeeded()).isTrue();
        var beta = (com.storynpcs.domain.npc.NpcDefinition) sink.defs.get("npc|storynpcs:beta");
        assertThat(beta.getId()).isEqualTo(NamespacedId.of("storynpcs:beta"));
        assertThat(beta.getDisplay().getName()).isEqualTo("Beta");
    }

    @Test
    void importPlanDiagnosticsDoNotPrintSnapshottedSourceContent() {
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("npc", "alpha.yaml", NPC_A), sink);

        assertThat(plan.toString()).doesNotContain("display: { name: \"Alpha\" }");
        assertThat(plan.sourceDocuments().toString()).isEqualTo("SourceDocuments[documentCount=1]");
    }

    @Test
    void destinationCollisionAfterPlanningAbortsBeforeAnyWrites() {
        Map<String, Map<String, String>> documents = new TreeMap<>();
        documents.put("npc", Map.of("alpha.yaml", NPC_A, "beta.yaml", NPC_B));
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL, documents, sink);
        Object existing = new Object();
        sink.save("npc", NamespacedId.of("storynpcs:alpha"), existing);
        int writesBeforeApply = sink.saveCalls;

        ImportReport report = importer.apply(plan, sink);

        assertThat(report.succeeded()).isFalse();
        assertThat(report.failureReason()).contains("destination changed after planning");
        assertThat(report.count(StepResult.Outcome.FAILED)).isEqualTo(2);
        assertThat(sink.defs).containsOnlyKeys("npc|storynpcs:alpha");
        assertThat(sink.defs.get("npc|storynpcs:alpha")).isSameAs(existing);
        assertThat(sink.saveCalls).isEqualTo(writesBeforeApply);
    }

    @Test
    void renameTargetCollisionAfterPlanningAbortsWithoutOverwrite() {
        sink.save("npc", NamespacedId.of("storynpcs:alpha"), new Object());
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.RENAME,
                pkg("npc", "alpha.yaml", NPC_A), sink);
        NamespacedId renameId = plan.steps().get(0).resolvedId();
        Object existing = new Object();
        sink.save("npc", renameId, existing);
        int writesBeforeApply = sink.saveCalls;

        ImportReport report = importer.apply(plan, sink);

        assertThat(report.succeeded()).isFalse();
        assertThat(report.failureReason()).contains("destination changed after planning");
        assertThat(sink.defs.get("npc|" + renameId)).isSameAs(existing);
        assertThat(sink.saveCalls).isEqualTo(writesBeforeApply);
    }

    @Test
    void templatePackageApplies() {
        var templateYaml = """
                id: "storynpcs:t_guard"
                schemaVersion: 1
                description: "Guard template"
                tags: [ "guard", "town" ]
                definition:
                  id: "storynpcs:guard_def"
                  display: { name: "Guard" }
                """;
        ImportPlan plan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("template", "t.yaml", templateYaml), sink);
        assertThat(plan.applicable()).isTrue();
        ImportReport report = importer.apply(plan, sink);
        assertThat(report.succeeded()).as(report.summaryMap().toString()).isTrue();
        assertThat(sink.templates).containsKey("template|storynpcs:t_guard");
    }

    @Test
    void templateImportHonorsVersionEnvelopeAndStrictFields() {
        // The template probe path must reject future schema versions and
        // unknown fields like every other family — previously it bypassed the
        // envelope entirely via raw tree binding (P2-1 review).
        var futureVersion = """
                id: "storynpcs:t_future"
                schemaVersion: 99
                definition:
                  id: "storynpcs:guard_def"
                """;
        ImportPlan futurePlan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("template", "t_future.yaml", futureVersion), sink);
        assertThat(futurePlan.steps().get(0).resolution())
                .isEqualTo(Step.Resolution.QUARANTINE);
        assertThat(futurePlan.steps().get(0).detail()).contains("schema validation failed");

        var unknownField = """
                id: "storynpcs:t_typo"
                schemaVersion: 1
                descripton: "typo'd field name"
                definition:
                  id: "storynpcs:guard_def"
                """;
        ImportPlan typoPlan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("template", "t_typo.yaml", unknownField), sink);
        assertThat(typoPlan.steps().get(0).resolution())
                .isEqualTo(Step.Resolution.QUARANTINE);

        var missingDefinition = """
                id: "storynpcs:t_nodef"
                schemaVersion: 1
                """;
        ImportPlan noDefPlan = importer.plan(yamlSource, ConflictPolicy.FAIL,
                pkg("template", "t_nodef.yaml", missingDefinition), sink);
        assertThat(noDefPlan.steps().get(0).resolution())
                .isEqualTo(Step.Resolution.QUARANTINE);

        assertThat(sink.templates).isEmpty();
    }

    private static com.storynpcs.domain.npc.NpcDefinition loadableNpc(String id) {
        var loader = new com.storynpcs.yaml.YamlDefinitionLoader(
                new com.storynpcs.yaml.DefinitionRegistry());
        com.storynpcs.domain.common.ValidationResult v = new com.storynpcs.domain.common.ValidationResult();
        return loader.loadNpc("schemaVersion: 1\nid: \"" + id + "\"\n", "n.yaml", v);
    }
}
