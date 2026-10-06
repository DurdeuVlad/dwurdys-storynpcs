package com.storynpcs.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.creator.scene.SceneDefinition;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.AuthorizationPolicy;
import com.storynpcs.service.MutationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/** P9-1: typed public extension API — sessions, domains, events, boundaries. */
class P91ExtensionApiTest {

    @TempDir
    Path tempDir;

    // ── ApiSessionRegistry ───────────────────────────────────────────────────

    @Test
    void sessionGrantLifecycleIsBounded() {
        var registry = new ApiSessionRegistry();
        var session = registry.issue("ext.mod", Set.of("scene.mutate"), 100L).orElseThrow();

        assertThat(registry.grants(session.sessionId(), "scene.mutate", 100L)).isTrue();
        assertThat(registry.grants(session.sessionId(), "scene.delete", 100L)).isFalse();
        assertThat(registry.grants(UUID.randomUUID(), "scene.mutate", 100L)).isFalse();

        assertThat(registry.revoke(session.sessionId())).isTrue();
        assertThat(registry.grants(session.sessionId(), "scene.mutate", 100L)).isFalse();
    }

    @Test
    void sessionExpiryAndSweepAreEnforced() {
        var registry = new ApiSessionRegistry();
        var session = registry.issue("ext.mod", Set.of("scene.mutate"), 10L, 100L).orElseThrow();

        assertThat(session.live(109L)).isTrue();
        assertThat(session.live(110L)).isFalse();
        assertThat(registry.grants(session.sessionId(), "scene.mutate", 110L)).isFalse();

        assertThat(registry.sweepExpired(110L)).isEqualTo(1);
        assertThat(registry.size()).isZero();
    }

    @Test
    void registryRefusesEmptyOrOverflowGrants() {
        var registry = new ApiSessionRegistry();
        assertThat(registry.issue("ext.mod", Set.of(), 0L)).isEmpty();
        assertThat(registry.issue("  ", Set.of("scene.mutate"), 0L)).isEmpty();
        assertThat(registry.issue("ext.mod", Set.of("scene.mutate"), 0L, 0L)).isEmpty();

        for (int i = 0; i < ApiSessionRegistry.MAX_SESSIONS; i++) {
            assertThat(registry.issue("ext.mod", Set.of("scene.mutate"), 0L)).isPresent();
        }
        assertThat(registry.issue("ext.mod", Set.of("scene.mutate"), 0L)).isEmpty();
    }

    // ── AuthorizationPolicy api actors ───────────────────────────────────────

    private static MutationRequest apiRequest(String actor, String capability) {
        return new MutationRequest("scene.replace", actor, capability,
                NamespacedId.of("storynpcs:api_scene"), 0L, UUID.randomUUID());
    }

    @Test
    void bareApiActorStillDeniedWithoutSession() {
        var decision = AuthorizationPolicy.evaluate(apiRequest("api", "scene.mutate"),
                (id, cap) -> true);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("API_SESSION_REQUIRED");
    }

    @Test
    void malformedApiSessionDenied() {
        var decision = AuthorizationPolicy.evaluate(apiRequest("api:not-a-uuid", "scene.mutate"),
                (id, cap) -> true);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("API_SESSION_MALFORMED");
    }

    @Test
    void ungrantedCapabilityDeniedGrantedAllowed() {
        UUID sessionId = UUID.randomUUID();
        var granted = AuthorizationPolicy.evaluate(
                apiRequest("api:" + sessionId, "scene.mutate"),
                (id, cap) -> id.equals(sessionId) && cap.equals("scene.mutate"));
        assertThat(granted.allowed()).isTrue();

        var wrongCap = AuthorizationPolicy.evaluate(
                apiRequest("api:" + sessionId, "scene.delete"),
                (id, cap) -> cap.equals("scene.mutate"));
        assertThat(wrongCap.allowed()).isFalse();
        assertThat(wrongCap.code()).isEqualTo("API_CAPABILITY_NOT_GRANTED");
    }

    @Test
    void apiActorDeniedWhenNoRegistryWired() {
        var decision = AuthorizationPolicy.evaluate(apiRequest("api:" + UUID.randomUUID(), "scene.mutate"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("REMOTE_AUTH_UNAVAILABLE");
    }

    // ── openSession capability policy ────────────────────────────────────────

    @Test
    void openSessionRejectsNonDefinitionCapabilities() throws Exception {
        var api = api(service(new DefinitionRegistry()));
        assertThat(api.openSession("ext.mod", Set.of("quest.start"))).isEmpty(); // player-scoped
        assertThat(api.openSession("ext.mod", Set.of("not.a.capability"))).isEmpty();
        assertThat(api.openSession("ext.mod", Set.of("scene.mutate", "quest.start"))).isEmpty();
        assertThat(api.openSession("ext.mod", Set.of("scene.mutate", "scene.delete"))).isPresent();
    }

    // ── canonical mutations through the facade ───────────────────────────────

    @Test
    void domainSaveAndDeleteFlowThroughCanonicalEnvelope() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        var api = api(service);

        var session = api.openSession("ext.scenes", Set.of("scene.mutate", "scene.delete")).orElseThrow();
        var scene = scene("storynpcs:api_scene");

        var save = api.scenes().save(session, scene);
        assertThat(save.getErrors()).isEmpty();
        assertThat(registry.getScene(scene.getId())).isPresent();
        assertThat(Files.exists(tempDir.resolve("scenes"))).isTrue();

        var delete = api.scenes().delete(session, scene.getId());
        assertThat(delete.getErrors()).isEmpty();
        assertThat(registry.getScene(scene.getId())).isEmpty();
    }

    @Test
    void ungrantedSessionCannotMutateEvenWhenServiceIsReached() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        var api = api(service);

        var scene = scene("storynpcs:api_scene");
        service.saveScene(scene); // seed via trusted adapter

        // Session grants only scene.mutate — a delete request reaches the
        // canonical envelope and is denied by the authorization policy.
        var session = api.openSession("ext.scenes", Set.of("scene.mutate")).orElseThrow();
        var delete = api.scenes().delete(session, scene.getId());
        assertThat(delete.getErrors()).extracting(com.storynpcs.domain.common.DiagnosticError::code)
                .contains("API_CAPABILITY_NOT_GRANTED");
        assertThat(registry.getScene(scene.getId())).isPresent();
    }

    @Test
    void revokedSessionFailsAtCanonicalEnvelope() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        var api = api(service);

        var session = api.openSession("ext.scenes", Set.of("scene.mutate")).orElseThrow();
        assertThat(api.revokeSession(session)).isTrue();

        var save = api.scenes().save(session, scene("storynpcs:api_scene"));
        assertThat(save.getErrors()).isNotEmpty();
        assertThat(registry.getScene(NamespacedId.of("storynpcs:api_scene"))).isEmpty();
    }

    @Test
    void apiRevisionEnvelopeIsFilledByTheService() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        var api = api(service);

        var scene = scene("storynpcs:api_scene");
        service.saveScene(scene);
        var session = api.openSession("ext.scenes", Set.of("scene.mutate")).orElseThrow();

        // Re-save through the API: expectedRevision comes from the service's
        // canonical revision clock, so a concurrent bump would reject, not
        // silently overwrite.
        var result = api.scenes().save(session, scene);
        assertThat(result.getErrors()).isEmpty();
        assertThat(service.currentRevision("scene", scene.getId())).isGreaterThan(0L);
    }

    // ── typed views and subscriptions ────────────────────────────────────────

    @Test
    void domainSummariesAreDetachedViews() throws Exception {
        var registry = new DefinitionRegistry();
        var service = service(registry);
        service.saveScene(scene("storynpcs:api_scene"));

        var api = api(service);
        var summaries = api.scenes().summaries();
        assertThat(summaries).hasSize(1);
        var view = summaries.get(0);
        assertThat(view.id()).isEqualTo(NamespacedId.of("storynpcs:api_scene"));
        assertThat(view.kind()).isEqualTo("scene");
        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(api.scenes().ids()).containsExactly(view.id());
    }

    @Test
    void subscriptionDeliversEventsAndCloses() throws Exception {
        var events = new EventPublisher();
        var api = new StoryNpcsApi(new DefinitionRegistry(), service(new DefinitionRegistry()), events);

        var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var sub = api.subscribe(e -> seen.add(e.getClass().getSimpleName())).orElseThrow();

        events.publish(new com.storynpcs.api.event.StoryNpcsEvent() {});
        assertThat(seen).hasSize(1);

        sub.close();
        events.publish(new com.storynpcs.api.event.StoryNpcsEvent() {});
        assertThat(seen).hasSize(1); // listener detached — no further deliveries
    }

    @Test
    void apiNeverExposesTheService() {
        for (var method : StoryNpcsApi.class.getDeclaredMethods()) {
            assertThat(method.getReturnType())
                    .as("api method %s must not leak the canonical service", method.getName())
                    .isNotEqualTo(StoryNpcsApplicationService.class);
            for (var param : method.getParameterTypes()) {
                assertThat(param)
                        .as("api method %s must not accept the canonical service", method.getName())
                        .isNotEqualTo(StoryNpcsApplicationService.class);
            }
        }
        for (var method : DefinitionDomain.class.getDeclaredMethods()) {
            assertThat(method.getReturnType())
                    .as("domain method %s must not leak the canonical service", method.getName())
                    .isNotEqualTo(StoryNpcsApplicationService.class);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SceneDefinition scene(String id) {
        var scene = new SceneDefinition();
        scene.setSchemaVersion(1);
        scene.setId(NamespacedId.of(id));
        scene.setMaxEntities(2);
        scene.setMaxDurationTicks(100);
        scene.setStages(java.util.List.of(
                new SceneDefinition.SceneStage("only", 50, "hello")));
        return scene;
    }

    private StoryNpcsApi api(StoryNpcsApplicationService service) {
        return new StoryNpcsApi(registry, service, new EventPublisher());
    }

    private DefinitionRegistry registry;

    private StoryNpcsApplicationService service(DefinitionRegistry registry) throws Exception {
        this.registry = registry;
        Files.createDirectories(tempDir.resolve("progression"));
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir);
        var service = new StoryNpcsApplicationService(
                registry, new ProgressionRepository(tempDir.resolve("progression")),
                new EventPublisher());
        service.setLoader(loader);
        return service;
    }
}
