package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.creator.template.SpawnerRule;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Typed canonical spawner operations (P8-1): save/delete route through
 * {@code executeCanonicalMutation} exactly like the template/npc families,
 * and rule registration feeds the template-dependency graph.
 */
class SpawnerMutationTest {

    private static final NamespacedId SPAWNER_ID = NamespacedId.of("storynpcs:spawner/test_a");
    private static final NamespacedId TEMPLATE_ID = NamespacedId.of("storynpcs:guard_template");

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private List<StoryNpcsEvent> publishedEvents;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        var events = new EventPublisher();
        publishedEvents = new ArrayList<>();
        events.register(publishedEvents::add);
        service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir), events);
    }

    private static SpawnerRule rule(NamespacedId id) {
        var rule = new SpawnerRule();
        rule.setId(id);
        rule.setTemplateId(TEMPLATE_ID);
        return rule;
    }

    private static MutationRequest saveRequest(NamespacedId target, long expectedRevision, UUID requestId) {
        return new MutationRequest("spawner.replace", "command", "spawner.edit",
                target, expectedRevision, requestId, 2);
    }

    private static MutationRequest deleteRequest(NamespacedId target, long expectedRevision, UUID requestId) {
        return new MutationRequest("spawner.delete", "command", "spawner.delete",
                target, expectedRevision, requestId, 2);
    }

    @Test
    void saveRegistersRuleAndTemplateDependency() {
        var result = service.saveSpawner(
                saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule(SPAWNER_ID));

        assertThat(result.newlyApplied()).isTrue();
        assertThat(registry.getSpawnerRule(SPAWNER_ID)).isPresent();
        assertThat(registry.templateSpawnerDependents(TEMPLATE_ID))
                .containsExactly(SPAWNER_ID);
        assertThat(result.events()).containsExactly("spawner.replace:applied");
    }

    @Test
    void saveRegistersRuleWhenTemplateAbsentWithWarning() {
        var result = service.saveSpawner(
                saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule(SPAWNER_ID));

        assertThat(result.newlyApplied()).isTrue();
        assertThat(result.diagnostics().getDiagnostics())
                .anyMatch(d -> d.code().equals("SPAWNER_TEMPLATE_UNKNOWN"));
    }

    @Test
    void saveRejectsPartialAnchor() {
        var rule = rule(SPAWNER_ID);
        rule.setDimension("minecraft:overworld");
        rule.setAnchorX(10); // anchorY/Z missing — all-or-none contract
        var result = service.saveSpawner(
                saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("SPAWNER_PARTIAL_ANCHOR"));
        assertThat(registry.getSpawnerRule(SPAWNER_ID)).isEmpty();
    }

    @Test
    void saveRejectsMissingTemplateId() {
        var rule = rule(SPAWNER_ID);
        rule.setTemplateId(null);
        var result = service.saveSpawner(
                saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("SPAWNER_MISSING_TEMPLATE"));
    }

    @Test
    void saveReplaysIdenticalRequestWithoutReapplying() {
        var rule = rule(SPAWNER_ID);
        UUID requestId = UUID.randomUUID();

        var first = service.saveSpawner(saveRequest(SPAWNER_ID, 0L, requestId), rule);
        var second = service.saveSpawner(saveRequest(SPAWNER_ID, 0L, requestId), rule);

        assertThat(first.newlyApplied()).isTrue();
        assertThat(second.applied()).isTrue();
        assertThat(second.duplicate()).isTrue();
    }

    @Test
    void saveRejectsStaleRevision() {
        var result = service.saveSpawner(
                saveRequest(SPAWNER_ID, 7L, UUID.randomUUID()), rule(SPAWNER_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("STALE_REVISION"));
    }

    @Test
    void retargetedRuleDropsStaleTemplateDependency() {
        service.saveSpawner(saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule(SPAWNER_ID));
        var retargeted = rule(SPAWNER_ID);
        retargeted.setTemplateId(NamespacedId.of("storynpcs:other_template"));

        service.saveSpawner(saveRequest(SPAWNER_ID, 1L, UUID.randomUUID()), retargeted);

        assertThat(registry.templateSpawnerDependents(TEMPLATE_ID)).isEmpty();
        assertThat(registry.templateSpawnerDependents(NamespacedId.of("storynpcs:other_template")))
                .containsExactly(SPAWNER_ID);
    }

    @Test
    void deleteRemovesRuleAndDependency() {
        service.saveSpawner(saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule(SPAWNER_ID));

        var result = service.deleteSpawner(deleteRequest(SPAWNER_ID, 1L, UUID.randomUUID()));

        assertThat(result.newlyApplied()).isTrue();
        assertThat(registry.getSpawnerRule(SPAWNER_ID)).isEmpty();
        assertThat(registry.templateSpawnerDependents(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void deleteReportsMissingSpawner() {
        var result = service.deleteSpawner(deleteRequest(SPAWNER_ID, 0L, UUID.randomUUID()));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("SPAWNER_NOT_FOUND"));
    }

    @Test
    void deleteReplaysIdenticalRequest() {
        service.saveSpawner(saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule(SPAWNER_ID));
        UUID requestId = UUID.randomUUID();

        var first = service.deleteSpawner(deleteRequest(SPAWNER_ID, 1L, requestId));
        var second = service.deleteSpawner(deleteRequest(SPAWNER_ID, 1L, requestId));

        assertThat(first.newlyApplied()).isTrue();
        assertThat(second.duplicate()).isTrue();
    }

    @Test
    void spawnerDeleteSurfacesTemplateDependents() {
        // The inverse direction: deleting a template must report its spawners.
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(TEMPLATE_ID);
        var definition = new com.storynpcs.domain.npc.NpcDefinition();
        definition.setId(NamespacedId.of("storynpcs:embedded_npc"));
        template.setDefinition(definition);
        service.saveTemplate(new MutationRequest("template.replace", "command", "template.edit",
                TEMPLATE_ID, 0L, UUID.randomUUID(), 2), template);
        service.saveSpawner(saveRequest(SPAWNER_ID, 0L, UUID.randomUUID()), rule(SPAWNER_ID));

        var result = service.deleteTemplate(new MutationRequest("template.delete", "command",
                "template.delete", TEMPLATE_ID, 1L, UUID.randomUUID(), 2));

        assertThat(result.newlyApplied()).isTrue();
        assertThat(result.diagnostics().getDiagnostics())
                .anyMatch(d -> d.code().equals("TEMPLATE_SPAWNERS_ORPHANED"));
    }
}
