package com.storynpcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.storynpcs.authoring.ai.PatchPlan;
import com.storynpcs.authoring.ai.PatchPlanValidator;
import com.storynpcs.authoring.ai.SchemaBundle;
import com.storynpcs.editor.hub.AuthoringHub;

class P10DomainTest {

    // --- P10-1 authoring hub ------------------------------------------------

    @Test
    void hubCoversAllParityDomains() {
        assertThat(AuthoringHub.coversAllDomains()).isTrue();
        assertThat(AuthoringHub.Panel.values()).contains(
                AuthoringHub.Panel.DIALOGUE, AuthoringHub.Panel.QUEST, AuthoringHub.Panel.TRADE,
                AuthoringHub.Panel.TRANSPORT, AuthoringHub.Panel.SCRIPT);
    }

    @Test
    void hubSearchPaginationAndRevisionedState() {
        assertThat(AuthoringHub.searchPanels("npc"))
                .containsExactly(AuthoringHub.Panel.NPC_IDENTITY);
        assertThat(AuthoringHub.searchPanels("t")).hasSize(11); // TEMPLATE TOOL TRANSPORT TRADE... deterministic sort
        var items = java.util.stream.IntStream.range(0, 25).boxed().toList();
        assertThat(AuthoringHub.page(items, 0, 10)).hasSize(10);
        assertThat(AuthoringHub.page(items, 2, 10)).hasSize(5);
        assertThat(AuthoringHub.page(items, 3, 10)).isEmpty();
        assertThat(AuthoringHub.pageCount(25, 10)).isEqualTo(3);

        AuthoringHub hub = new AuthoringHub();
        long r0 = hub.state().revision();
        hub.open(AuthoringHub.Panel.DIALOGUE, "storynpcs:intro");
        assertThat(hub.state().revision()).isGreaterThan(r0);
        assertThat(hub.state().selectedId()).isEqualTo("storynpcs:intro");
        var err = AuthoringHub.fieldError("inventory.drops", "index 99 invalid");
        assertThat(err.schemaPath()).isEqualTo("inventory.drops");
        assertThat(err.repairHint()).contains("0-20");
    }

    // --- P10-2 patch plans ---------------------------------------------------

    @Test
    void schemaBundleCoversFamiliesAndExplicitUnsupported() {
        SchemaBundle bundle = SchemaBundle.current();
        assertThat(bundle.bundleVersion()).isEqualTo(SchemaBundle.CURRENT_VERSION);
        assertThat(bundle.families()).containsKeys("npc", "dialogue", "quest", "faction",
                "template", "recipe", "transport", "worldTool");
        assertThat(bundle.supports("npc", "display")).isTrue();
        assertThat(bundle.unsupported("npc", "linkedData")).isNotEmpty(); // explicit
        assertThat(bundle.unsupported("npc", "madeUp")).isNotEmpty();     // not in schema
        assertThat(bundle.unsupported("npc", "display")).isEmpty();
        assertThat(bundle.unsupported("npc", "scripts")).isEmpty();       // P9-2: bound script ids are schema-supported
    }

    @Test
    void patchPlanIsDeterministicAndIdempotent() {
        var op1 = new PatchPlan.PatchOp("set", "npc", "storynpcs:guard", "stats.health", "80", "gen:1");
        var op2 = new PatchPlan.PatchOp("create", "dialogue", "storynpcs:intro", "", "{}", "gen:2");
        var plan = new PatchPlan("p1", 5, List.of(op2, op1, op1)); // dup + unordered
        var dedup = plan.deduplicated();
        assertThat(dedup.ops()).hasSize(2);
        assertThat(plan.deterministicOrder()).isEqualTo(plan.deterministicOrder());
        assertThat(dedup.ops().get(0).idempotencyKey())
                .isLessThan(dedup.ops().get(1).idempotencyKey());
    }

    @Test
    void dryRunReportsErrorsWithoutWriting() {
        var bundle = SchemaBundle.current();
        var validator = new PatchPlanValidator();
        var good = new PatchPlan.PatchOp("create", "npc", "storynpcs:new", "",
                "{\"id\":\"storynpcs:new\",\"dialogueId\":\"storynpcs:intro\"}", "gen:1");
        var badOp = new PatchPlan.PatchOp("explode", "npc", "storynpcs:new2", "", "{}", "gen:5");
        var badField = new PatchPlan.PatchOp("set", "npc", "storynpcs:new", "linkedData", "x", "gen:9");
        var plan = new PatchPlan("p", 5, List.of(good, badOp, badField));

        var report = validator.dryRun(plan, bundle, Set.of("storynpcs:intro"), 5);
        assertThat(report.diagnostics().getErrors()).extracting(d -> d.code())
                .contains("PATCH_UNSUPPORTED_OP", "PATCH_UNSUPPORTED_FIELD");
        assertThat(report.dependencies()).extracting(d -> d.toString())
                .contains("storynpcs:intro");
        assertThat(report.operationSummary()).hasSize(3);

        // Stale base revision reported.
        var stale = validator.dryRun(new PatchPlan("p", 99, List.of()), bundle, Set.of(), 5);
        assertThat(stale.diagnostics().getErrors()).extracting(d -> d.code())
                .containsExactly("PATCH_STALE_BASE");
    }
}
