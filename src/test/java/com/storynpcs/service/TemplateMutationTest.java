package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.creator.template.NpcTemplate;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Typed canonical template operations (P1-1): save/delete route through
 * {@code executeCanonicalMutation} with actor, capability, revision, and
 * payload-fingerprint binding exactly like the other definition families.
 */
class TemplateMutationTest {

    private static final NamespacedId TEMPLATE_ID = NamespacedId.of("storynpcs:test_template");

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir), new EventPublisher());
    }

    private static NpcTemplate template(NamespacedId id) {
        NpcTemplate template = new NpcTemplate();
        template.setId(id);
        template.setDescription("test template");
        NpcDefinition definition = new NpcDefinition();
        definition.setId(NamespacedId.of("storynpcs", "embedded_npc"));
        template.setDefinition(definition);
        return template;
    }

    private static MutationRequest saveRequest(NamespacedId target, long expectedRevision, UUID requestId) {
        return new MutationRequest("template.replace", "command", "template.edit",
                target, expectedRevision, requestId, 2);
    }

    private static MutationRequest deleteRequest(NamespacedId target, long expectedRevision, UUID requestId) {
        return new MutationRequest("template.delete", "command", "template.delete",
                target, expectedRevision, requestId, 2);
    }

    @Test
    void typedSaveAppliesRegistersAndBumpsRevision() {
        var result = service.saveTemplate(
                saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), template(TEMPLATE_ID));

        assertThat(result.newlyApplied()).isTrue();
        assertThat(result.revision()).isEqualTo(1L);
        assertThat(result.recoveryOutcome()).isEqualTo("COMMITTED");
        assertThat(result.events()).containsExactly("template.replace:applied");
        assertThat(registry.getTemplate(TEMPLATE_ID)).isPresent();
    }

    @Test
    void typedSaveDetachesPayloadFromCallerObject() {
        var saved = template(TEMPLATE_ID);
        var result = service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), saved);
        assertThat(result.newlyApplied()).isTrue();

        saved.setDescription("mutated after save");
        assertThat(registry.getTemplate(TEMPLATE_ID).orElseThrow().getDescription())
                .isEqualTo("test template");
    }

    @Test
    void typedSaveRejectsTargetMismatch() {
        var result = service.saveTemplate(
                saveRequest(NamespacedId.of("storynpcs:other"), 0L, UUID.randomUUID()),
                template(TEMPLATE_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("TARGET_ID_MISMATCH"));
        assertThat(registry.getTemplate(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void typedSaveRejectsMissingTemplateId() {
        NpcTemplate idless = template(null);
        var result = service.saveTemplate(
                saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), idless);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("TEMPLATE_ID_MISSING"));
    }

    @Test
    void typedSaveRejectsUnsupportedSchemaVersion() {
        NpcTemplate bad = template(TEMPLATE_ID);
        bad.setSchemaVersion(99);
        var result = service.saveTemplate(
                saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), bad);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("SCHEMA_VERSION_UNSUPPORTED"));
        assertThat(registry.getTemplate(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void typedSaveReplaysIdenticalRequestWithoutReapplying() {
        var template = template(TEMPLATE_ID);
        UUID requestId = UUID.randomUUID();

        var first = service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, requestId), template);
        var second = service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, requestId), template);

        assertThat(first.newlyApplied()).isTrue();
        assertThat(second.applied()).isTrue();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.revision()).isEqualTo(1L);
    }

    @Test
    void typedSaveRejectsReusedRequestIdWithDifferentPayload() {
        UUID requestId = UUID.randomUUID();
        service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, requestId), template(TEMPLATE_ID));

        NpcTemplate altered = template(TEMPLATE_ID);
        altered.setDescription("different payload");
        var result = service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, requestId), altered);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("REQUEST_PAYLOAD_MISMATCH"));
    }

    @Test
    void typedSaveRejectsStaleRevisionWithoutWriting() {
        var result = service.saveTemplate(
                saveRequest(TEMPLATE_ID, 7L, UUID.randomUUID()), template(TEMPLATE_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("STALE_REVISION"));
        assertThat(registry.getTemplate(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void typedSaveDeniesScriptActor() {
        var request = new MutationRequest("template.replace", "script", "template.edit",
                TEMPLATE_ID, 0L, UUID.randomUUID());
        var result = service.saveTemplate(request, template(TEMPLATE_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.recoveryOutcome()).isEqualTo("REJECTED_AUTHORIZATION");
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("SCRIPT_CAPABILITY_REQUIRED"));
        assertThat(registry.getTemplate(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void typedSaveDeniesUnprovenPlayerActor() {
        var request = new MutationRequest("template.replace", "player:" + UUID.randomUUID(),
                "template.edit", TEMPLATE_ID, 0L, UUID.randomUUID(), 1);
        var result = service.saveTemplate(request, template(TEMPLATE_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("PERMISSION_DENIED"));
    }

    @Test
    void typedSaveRejectsRouteMismatch() {
        var request = new MutationRequest("template.delete", "command", "template.delete",
                TEMPLATE_ID, 0L, UUID.randomUUID(), 2);
        var result = service.saveTemplate(request, template(TEMPLATE_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("MUTATION_ROUTE_MISMATCH"));
    }

    @Test
    void typedSaveRejectsForeignFamilyCapability() {
        var request = new MutationRequest("template.replace", "command", "npc.edit",
                TEMPLATE_ID, 0L, UUID.randomUUID(), 2);
        var result = service.saveTemplate(request, template(TEMPLATE_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("MUTATION_ROUTE_MISMATCH"));
    }

    @Test
    void convenienceSaveRoutesThroughCanonicalBoundary() {
        var result = service.saveTemplate(template(TEMPLATE_ID));

        assertThat(result.hasErrors()).isFalse();
        assertThat(registry.getTemplate(TEMPLATE_ID)).isPresent();
        assertThat(service.currentRevision("template", TEMPLATE_ID)).isEqualTo(1L);
    }

    @Test
    void convenienceSavePreservesMissingIdDiagnostic() {
        var result = service.saveTemplate(template(null));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .anyMatch(error -> error.code().equals("TEMPLATE_ID_MISSING"));
    }

    @Test
    void typedDeleteRemovesAndBumpsRevision() {
        service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), template(TEMPLATE_ID));

        var result = service.deleteTemplate(deleteRequest(TEMPLATE_ID, 1L, UUID.randomUUID()));

        assertThat(result.newlyApplied()).isTrue();
        assertThat(result.revision()).isEqualTo(2L);
        assertThat(registry.getTemplate(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void typedDeleteReportsMissingTemplate() {
        var result = service.deleteTemplate(deleteRequest(TEMPLATE_ID, 0L, UUID.randomUUID()));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("TEMPLATE_NOT_FOUND"));
    }

    @Test
    void typedDeleteReplaysIdenticalRequest() {
        service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), template(TEMPLATE_ID));
        UUID requestId = UUID.randomUUID();

        var first = service.deleteTemplate(deleteRequest(TEMPLATE_ID, 1L, requestId));
        var second = service.deleteTemplate(deleteRequest(TEMPLATE_ID, 1L, requestId));

        assertThat(first.newlyApplied()).isTrue();
        assertThat(second.duplicate()).isTrue();
        assertThat(registry.getTemplate(TEMPLATE_ID)).isEmpty();
    }

    @Test
    void typedDeleteRejectsStaleRevision() {
        service.saveTemplate(saveRequest(TEMPLATE_ID, 0L, UUID.randomUUID()), template(TEMPLATE_ID));

        var result = service.deleteTemplate(deleteRequest(TEMPLATE_ID, 9L, UUID.randomUUID()));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> error.code().equals("STALE_REVISION"));
        assertThat(registry.getTemplate(TEMPLATE_ID)).isPresent();
    }
}
