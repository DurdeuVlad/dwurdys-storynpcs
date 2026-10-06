package com.storynpcs.authoring.ai;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P10-2 — canonical patch-plan apply: dry-run gate, per-op canonical
 * dispatch, and failed-apply rollback that leaves the prior revision intact.
 */
class P102PatchPlanApplyTest {

    private static final NamespacedId NPC_A = NamespacedId.of("storynpcs:aaa_guard");
    private static final NamespacedId NPC_Z = NamespacedId.of("storynpcs:zzz_guard");

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private StoryNpcsApplicationService service;
    private PatchPlanApplier applier;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir), new EventPublisher());
        applier = new PatchPlanApplier(service, registry);
    }

    private static PatchPlan.PatchOp op(String op, String family, String target,
                                        String field, String payload, String loc) {
        return new PatchPlan.PatchOp(op, family, target, field, payload, loc);
    }

    private static PatchPlan plan(long base, PatchPlan.PatchOp... ops) {
        return new PatchPlan("plan-test", base, List.of(ops));
    }

    private Set<String> existingIds() {
        Set<String> ids = new HashSet<>();
        registry.getAllNpcs().forEach(n -> ids.add(n.getId().toString()));
        registry.getAllDialogues().forEach(d -> ids.add(d.getId().toString()));
        registry.getAllQuests().forEach(q -> ids.add(q.getId().toString()));
        registry.getAllFactions().forEach(f -> ids.add(f.getId().toString()));
        return ids;
    }

    private static String npcJson(NamespacedId id, String name) {
        return NpcDefinitionSerde.toJson(new NpcDefinition(id, name));
    }

    @Test
    @DisplayName("create applies through the canonical boundary and persists the definition")
    void createAppliesCanonically() {
        var report = applier.apply(
                plan(0, op("create", "npc", NPC_A.toString(), "",
                        npcJson(NPC_A, "Guard A"), "gen:1")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertTrue(report.committed(), "apply must commit: " + report.diagnostics().formatReport());
        assertEquals(1, report.appliedOps().size());
        assertTrue(registry.getNpc(NPC_A).isPresent());
        assertEquals("Guard A", registry.getNpc(NPC_A).get().getDisplay().getName());
    }

    @Test
    @DisplayName("set overlays one field on the stored definition")
    void setOverlaysField() {
        service.createNpc(new NpcDefinition(NPC_A, "Guard A"));
        var report = applier.apply(
                plan(registry.revision(),
                        op("set", "npc", NPC_A.toString(), "name", "\"Captain\"", "gen:2")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertTrue(report.committed(), "set must commit: " + report.diagnostics().formatReport());
        var npc = registry.getNpc(NPC_A).orElseThrow();
        assertEquals("Captain", npc.getDisplay() != null ? npc.getDisplay().getName() : null);
    }

    @Test
    @DisplayName("a stale base revision rejects the plan with zero writes")
    void staleBaseRejectsWithoutWrites() {
        var report = applier.apply(
                plan(999, op("create", "npc", NPC_A.toString(), "",
                        npcJson(NPC_A, "Guard A"), "gen:3")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertFalse(report.committed());
        assertTrue(report.diagnostics().getErrors().stream()
                .anyMatch(e -> "PATCH_STALE_BASE".equals(e.code())));
        assertTrue(registry.getAllNpcs().isEmpty(), "no writes on stale base");
    }

    @Test
    @DisplayName("a failing op rolls back everything the plan applied — prior revision intact")
    void failingOpRollsBack() {
        // create storynpcs:aaa_guard (sorts first), then update of an absent
        // npc — the second op fails and the first must be undone.
        var report = applier.apply(
                plan(0,
                        op("create", "npc", NPC_A.toString(), "",
                                npcJson(NPC_A, "Guard A"), "gen:4a"),
                        op("update", "npc", NPC_Z.toString(), "",
                                npcJson(NPC_Z, "Ghost"), "gen:4b")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertFalse(report.committed(), "failing op must not commit");
        assertEquals(1, report.appliedOps().size(), "one op applied before failure");
        assertEquals(1, report.rolledBackOps().size(), "the applied op was rolled back");
        assertTrue(registry.getAllNpcs().isEmpty(),
                "registry must be exactly as before the plan");
        assertTrue(report.diagnostics().getErrors().stream()
                .anyMatch(e -> "PATCH_TARGET_MISSING".equals(e.code())));
    }

    @Test
    @DisplayName("families outside the apply scope reject explicitly, never silently")
    void unsupportedFamilyRejects() {
        var report = applier.apply(
                plan(0, op("create", "recipe", "storynpcs:pie", "",
                        "{\"id\":\"storynpcs:pie\"}", "gen:5")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertFalse(report.committed());
        assertTrue(report.diagnostics().getErrors().stream()
                .anyMatch(e -> "PATCH_APPLY_SCOPE".equals(e.code())));
    }

    @Test
    @DisplayName("delete of an absent target is an idempotent no-op, not a failure")
    void deleteAbsentIsNoop() {
        var report = applier.apply(
                plan(0, op("delete", "npc", NPC_A.toString(), "", null, "gen:6")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertTrue(report.committed(), "absent delete must not fail the plan");
        assertTrue(report.appliedOps().isEmpty(), "no-op delete is not counted as applied");
    }

    @Test
    @DisplayName("duplicate ops collapse — replaying a plan cannot double-apply")
    void dedupedOpsApplyOnce() {
        PatchPlan.PatchOp create = op("create", "npc", NPC_A.toString(), "",
                npcJson(NPC_A, "Guard A"), "gen:7");
        var report = applier.apply(
                plan(0, create, create),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertTrue(report.committed());
        assertEquals(1, report.appliedOps().size(), "identical ops collapse to one apply");
        assertEquals(1, registry.getAllNpcs().size());
    }

    @Test
    @DisplayName("a plan that creates then sets the same target applies in dependency order")
    void createThenSetAppliesInOrder() {
        // 'set' sorts after 'create' on the same target even though the
        // idempotency key alone would order them differently.
        var report = applier.apply(
                plan(0,
                        op("set", "npc", NPC_A.toString(), "name", "\"Captain\"", "gen:9a"),
                        op("create", "npc", NPC_A.toString(), "",
                                npcJson(NPC_A, "Guard A"), "gen:9b")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertTrue(report.committed(), "create+set must commit: " + report.diagnostics().formatReport());
        assertEquals("Captain", registry.getNpc(NPC_A).orElseThrow().getDisplay().getName());
        // applicationOrder put the create first.
        assertEquals("create", report.appliedOps().get(0).op());
        assertEquals("set", report.appliedOps().get(1).op());
    }

    @Test
    @DisplayName("create of an existing target fails canonically and rolls back")
    void createExistingFails() {
        service.createNpc(new NpcDefinition(NPC_A, "Guard A"));
        long before = registry.getNpc(NPC_A).orElseThrow() == null ? 0 : 1;
        var report = applier.apply(
                plan(registry.revision(),
                        op("create", "npc", NPC_A.toString(), "",
                                npcJson(NPC_A, "Impostor"), "gen:8")),
                SchemaBundle.current(), existingIds(), registry.revision(), 2);
        assertFalse(report.committed());
        // The pre-existing definition is untouched.
        assertEquals("Guard A", registry.getNpc(NPC_A).orElseThrow().getDisplay().getName());
        assertTrue(before > 0);
    }
}
