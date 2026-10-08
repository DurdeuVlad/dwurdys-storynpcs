package com.storynpcs.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.storynpcs.creator.template.NpcTemplate;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.migration.FieldMappingRegistry.Action;
import com.storynpcs.migration.ImportPlan.Step;
import com.storynpcs.migration.ImportReport.StepResult;

/**
 * #194: real CustomNPCs text-export corpus — clone SNBT translates to
 * StoryNPCs npc definitions; unmapped CNPC families quarantine with explicit
 * re-scope reasons.
 */
class CnpcTextExportImportTest {

    private static final class FakeSink implements DefinitionImporter.ImportSink {
        final Map<String, Object> defs = new LinkedHashMap<>();
        private static String key(String family, NamespacedId id) { return family + "|" + id; }
        @Override public boolean contains(String family, NamespacedId id) {
            return defs.containsKey(key(family, id));
        }
        @Override public Object snapshot(String family, NamespacedId id) { return defs.get(key(family, id)); }
        @Override public void save(String family, NamespacedId id, Object definition) {
            defs.put(key(family, id), definition);
        }
        @Override public void delete(String family, NamespacedId id) { defs.remove(key(family, id)); }
        @Override public void restore(String family, NamespacedId id, Object prior) {
            defs.put(key(family, id), prior);
        }
        @Override public void saveTemplate(NpcTemplate template) {}
        @Override public void deleteTemplate(NamespacedId id) {}
        @Override public NpcTemplate snapshotTemplate(NamespacedId id) { return null; }
    }

    private static String fixture(String path) {
        try (InputStream in = CnpcTextExportImportTest.class
                .getResourceAsStream("/fixtures/customnpcs/" + path)) {
            assertThat(in).as("fixture %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + path, e);
        }
    }

    private static ImportSource cnpcSource() {
        return new ImportSource(ImportSource.Kind.CUSTOMNPCS_TEXT_EXPORT,
                "cnpc-guard-world", 1, "CustomNPCs-Unofficial-NeoForge-1.21.1.20251230");
    }

    private DefinitionImporter importer;
    private FakeSink sink;

    @BeforeEach
    void setUp() {
        importer = new DefinitionImporter();
        sink = new FakeSink();
    }

    @Test
    void textExportSourceIsSupported() {
        assertThat(ImportSource.support(ImportSource.Kind.CUSTOMNPCS_TEXT_EXPORT))
                .isEqualTo(ImportSource.Support.SUPPORTED);
        assertThat(ImportSource.support(ImportSource.Kind.TARGET_WORLD_NBT))
                .as("binary world NBT remains unverified")
                .isEqualTo(ImportSource.Support.UNSUPPORTED_NEEDS_EVIDENCE);
    }

    @Test
    void realCloneExportPlansApplyNewNpc() {
        Map<String, Map<String, String>> documents = new LinkedHashMap<>();
        documents.put("clones", Map.of("Dorian both trades.json",
                fixture("clones/Dorian both trades.json")));

        ImportPlan plan = importer.plan(cnpcSource(), ConflictPolicy.FAIL, documents, sink);

        assertThat(plan.applicable()).isTrue();
        assertThat(plan.steps()).hasSize(1);
        Step step = plan.steps().get(0);
        assertThat(step.family()).isEqualTo("npc");
        assertThat(step.resolution()).isEqualTo(Step.Resolution.APPLY_NEW);
        assertThat(step.definitionId().toString()).isEqualTo("cnpc:dorian");
        // Every CNPC field is accounted for — mapped ones carry a target note,
        // the rest are reported UNSUPPORTED, never silently dropped.
        assertThat(step.fieldMappings())
                .anySatisfy(m -> {
                    assertThat(m.fieldPath()).isEqualTo("cnpc:Name");
                    assertThat(m.action()).isEqualTo(Action.MIGRATED);
                })
                .anySatisfy(m -> {
                    assertThat(m.fieldPath()).isEqualTo("cnpc:TraderSold");
                    assertThat(m.action()).isEqualTo(Action.UNSUPPORTED);
                })
                .noneMatch(m -> m.action() == Action.UNKNOWN);
    }

    @Test
    void realCloneExportAppliesTranslatedFields() {
        Map<String, Map<String, String>> documents = new LinkedHashMap<>();
        documents.put("clones", Map.of("Dorian both trades.json",
                fixture("clones/Dorian both trades.json"),
                "Cavaler.json", fixture("clones/Cavaler.json")));

        ImportReport report = importer.apply(
                importer.plan(cnpcSource(), ConflictPolicy.FAIL, documents, sink), sink);

        assertThat(report.succeeded()).isTrue();
        assertThat(report.steps()).allSatisfy(
                s -> assertThat(s.outcome()).isEqualTo(StepResult.Outcome.APPLIED));
        NpcDefinition dorian = (NpcDefinition) sink.defs.get("npc|cnpc:dorian");
        assertThat(dorian).isNotNull();
        assertThat(dorian.getDisplay().getName()).isEqualTo("Dorian");
        assertThat(dorian.getDisplay().getTitle()).isEqualTo("FIERAR");
        assertThat(dorian.getStats().getMaxHealth()).isEqualTo(20.0);
        assertThat(dorian.getStats().getAggroRange()).isEqualTo(16);
        assertThat(dorian.getFactionId().toString()).isEqualTo("cnpc:faction_3");
    }

    @Test
    void unmappedCnpcFamiliesQuarantineWithReason() {
        Map<String, Map<String, String>> documents = new LinkedHashMap<>();
        documents.put("clones", Map.of("Imp.json", fixture("clones/Imp.json")));
        documents.put("dialogs", Map.of("36.json", fixture("dialogs/36.json")));
        documents.put("quests", Map.of("16.json", fixture("quests/16.json")));

        ImportPlan plan = importer.plan(cnpcSource(), ConflictPolicy.FAIL, documents, sink);

        assertThat(plan.applicable()).isTrue();
        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.steps())
                .filteredOn(s -> s.family().equals("dialogs") || s.family().equals("quests"))
                .allSatisfy(s -> {
                    assertThat(s.resolution()).isEqualTo(Step.Resolution.QUARANTINE);
                    assertThat(s.detail()).contains("not mapped");
                });
        assertThat(plan.steps())
                .filteredOn(s -> s.family().equals("npc"))
                .allSatisfy(s -> assertThat(s.resolution()).isEqualTo(Step.Resolution.APPLY_NEW));
    }

    @Test
    void malformedSnbtQuarantines() {
        Map<String, Map<String, String>> documents = new LinkedHashMap<>();
        documents.put("clones", Map.of("broken.json", "{ \"Name\": \"oops\""));

        ImportPlan plan = importer.plan(cnpcSource(), ConflictPolicy.FAIL, documents, sink);

        assertThat(plan.steps()).singleElement().satisfies(s -> {
            assertThat(s.resolution()).isEqualTo(Step.Resolution.QUARANTINE);
            assertThat(s.detail()).contains("unparseable CNPC SNBT");
        });
    }

    @Test
    void namelessCloneGetsDeterministicId() {
        Map<String, Map<String, String>> documents = new LinkedHashMap<>();
        documents.put("clones", Map.of("x.json", "{ \"Name\": \"  \", \"MaxHealth\": 10 }"));

        ImportPlan plan = importer.plan(cnpcSource(), ConflictPolicy.FAIL, documents, sink);

        assertThat(plan.steps()).singleElement().satisfies(s -> {
            assertThat(s.resolution()).isEqualTo(Step.Resolution.APPLY_NEW);
            assertThat(s.definitionId().toString()).isEqualTo("cnpc:unnamed_clone");
        });
    }
}
