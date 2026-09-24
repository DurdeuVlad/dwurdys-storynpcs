package com.storynpcs.runtime.actor;

import com.storynpcs.api.event.ActorLifecycleEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActorLifecycleServiceTest {
    private static final NamespacedId ACTOR = NamespacedId.of("storynpcs:guard_captain");
    private static final NamespacedId DEFINITION = NamespacedId.of("storynpcs:guard_captain");

    @Test
    void replacementPreservesLogicalIdentityAndPublishesReasonedEvents() {
        EventPublisher publisher = new EventPublisher();
        List<ActorLifecycleEvent> events = new ArrayList<>();
        publisher.register(event -> events.add((ActorLifecycleEvent) event));
        ActorLifecycleService service = new ActorLifecycleService(
                new ActorProjectionRegistry("server-a/overworld"),
                publisher
        );

        UUID firstProjection = UUID.randomUUID();
        UUID replacementProjection = UUID.randomUUID();
        assertEquals(ActorLifecycleReason.SPAWNED,
                service.bindProjection(ACTOR, DEFINITION, firstProjection).reason());
        ActorProjectionResult replaced = service.replaceProjection(
                ACTOR, DEFINITION, firstProjection, replacementProjection);

        assertTrue(replaced.applied());
        assertEquals(ActorLifecycleReason.REPLACED, replaced.reason());
        assertEquals(replacementProjection, service.registry().find(ACTOR).orElseThrow().projectionId());
        assertTrue(service.registry().actorForProjection(firstProjection).isEmpty());
        assertEquals(ACTOR, service.registry().actorForProjection(replacementProjection).orElseThrow());
        assertEquals(List.of(ActorLifecycleReason.SPAWNED, ActorLifecycleReason.REPLACED),
                events.stream().map(ActorLifecycleEvent::reason).toList());
        assertEquals(ACTOR, events.get(1).actorId());
        assertEquals("server-a/overworld", events.get(1).scopeId());
    }

    @Test
    void failedProjectionIsRetainedAndRetryable() {
        ActorLifecycleService service = new ActorLifecycleService(
                new ActorProjectionRegistry("server-a/nether"),
                null
        );
        UUID firstProjection = UUID.randomUUID();
        service.bindProjection(ACTOR, DEFINITION, firstProjection);

        ActorProjectionResult failed = service.failProjection(ACTOR, firstProjection, "entity construction failed");
        assertFalse(failed.applied());
        assertTrue(failed.retryable());
        assertEquals(ActorLifecycleState.FAILED, failed.state());
        assertEquals("entity construction failed", failed.diagnostic());

        UUID retryProjection = UUID.randomUUID();
        ActorProjectionResult retried = service.retryProjection(ACTOR, retryProjection);
        assertTrue(retried.applied());
        assertEquals(ActorLifecycleReason.RETRY_SUCCEEDED, retried.reason());
        assertEquals(retryProjection, service.registry().find(ACTOR).orElseThrow().projectionId());
    }

    @Test
    void unloadThenReloadKeepsActorIdButReplacesProjection() {
        ActorLifecycleService service = new ActorLifecycleService(
                new ActorProjectionRegistry("server-a/overworld"),
                null
        );
        UUID oldProjection = UUID.randomUUID();
        service.bindProjection(ACTOR, DEFINITION, oldProjection);
        ActorProjectionResult unloaded = service.unloadProjection(ACTOR, oldProjection);

        assertTrue(unloaded.applied());
        assertEquals(ActorLifecycleState.UNLOADED, unloaded.state());
        assertEquals(ACTOR, service.registry().find(ACTOR).orElseThrow().actorId());
        assertTrue(service.registry().find(ACTOR).orElseThrow().projectionId() == null);

        UUID newProjection = UUID.randomUUID();
        ActorProjectionResult reloaded = service.bindProjection(ACTOR, DEFINITION, newProjection);
        assertTrue(reloaded.applied());
        assertEquals(ActorLifecycleReason.RELOADED, reloaded.reason());
    }

    @Test
    void registriesAreIsolatedByInstanceAndScope() {
        ActorProjectionRegistry overworld = new ActorProjectionRegistry("server-a/overworld");
        ActorProjectionRegistry nether = new ActorProjectionRegistry("server-a/nether");
        UUID projection = UUID.randomUUID();

        overworld.bind(ACTOR, DEFINITION, projection);

        assertTrue(overworld.find(ACTOR).isPresent());
        assertTrue(nether.find(ACTOR).isEmpty());
        assertTrue(nether.actorForProjection(projection).isEmpty());
    }

    @Test
    void persistenceRestoresLogicalActorsWithoutPersistingEntityUuid(@TempDir Path tempDir) throws Exception {
        ActorProjectionRegistry source = new ActorProjectionRegistry("server-a/overworld");
        UUID projection = UUID.randomUUID();
        source.bind(ACTOR, DEFINITION, projection);
        ActorStateRepository repository = new ActorStateRepository(tempDir.resolve("actors.json"));
        repository.save(source);

        String persisted = java.nio.file.Files.readString(repository.target());
        assertFalse(persisted.contains(projection.toString()), "entity UUID must not become durable identity");

        ActorProjectionRegistry restored = new ActorProjectionRegistry("server-a/overworld");
        assertTrue(repository.restore(restored));
        ActorRecord record = restored.find(ACTOR).orElseThrow();
        assertEquals(ACTOR, record.actorId());
        assertEquals(DEFINITION, record.definitionId());
        assertNull(record.projectionId());
        assertEquals(ActorLifecycleState.UNLOADED, record.state());
    }

    @Test
    void projectionConflictDoesNotStealAnotherActor() {
        ActorProjectionRegistry registry = new ActorProjectionRegistry("server-a/overworld");
        NamespacedId otherActor = NamespacedId.of("storynpcs:merchant");
        UUID projection = UUID.randomUUID();
        registry.bind(ACTOR, DEFINITION, projection);

        ActorProjectionResult conflict = registry.bind(otherActor, otherActor, projection);

        assertFalse(conflict.applied());
        assertTrue(conflict.retryable());
        assertEquals(ACTOR, registry.actorForProjection(projection).orElseThrow());
        assertTrue(registry.find(otherActor).isEmpty());
    }

    @Test
    void staleProjectionFailureCannotFailReplacement() {
        ActorLifecycleService service = new ActorLifecycleService(
                new ActorProjectionRegistry("server-a/overworld"),
                null
        );
        UUID firstProjection = UUID.randomUUID();
        UUID replacementProjection = UUID.randomUUID();
        service.bindProjection(ACTOR, DEFINITION, firstProjection);
        service.replaceProjection(ACTOR, DEFINITION, firstProjection, replacementProjection);

        ActorProjectionResult stale = service.failProjection(ACTOR, firstProjection, "late failure");

        assertFalse(stale.applied());
        assertFalse(stale.retryable());
        assertEquals(ActorLifecycleState.PROJECTED, service.registry().find(ACTOR).orElseThrow().state());
        assertEquals(replacementProjection, service.registry().find(ACTOR).orElseThrow().projectionId());
    }

    @Test
    void staleRebindCannotReplaceAnActiveProjection() {
        ActorLifecycleService service = new ActorLifecycleService(
                new ActorProjectionRegistry("server-a/overworld"),
                null
        );
        UUID firstProjection = UUID.randomUUID();
        UUID activeReplacement = UUID.randomUUID();
        UUID delayedOldProjection = UUID.randomUUID();
        service.bindProjection(ACTOR, DEFINITION, firstProjection);
        service.replaceProjection(ACTOR, DEFINITION, firstProjection, activeReplacement);

        ActorProjectionResult stale = service.bindProjection(ACTOR, DEFINITION, delayedOldProjection);

        assertFalse(stale.applied());
        assertTrue(stale.retryable());
        assertEquals(activeReplacement, service.registry().find(ACTOR).orElseThrow().projectionId());
    }

    @Test
    void legacyProjectionReusesTheOnlyUnloadedActorForItsDefinition() {
        ActorLifecycleService service = new ActorLifecycleService(
                new ActorProjectionRegistry("server-a/overworld"),
                null
        );
        UUID oldProjection = UUID.randomUUID();
        UUID legacyProjection = UUID.randomUUID();
        service.bindProjection(ACTOR, DEFINITION, oldProjection);
        service.unloadProjection(ACTOR, oldProjection);

        ActorProjectionResult reconciled = service.bindLegacyProjection(DEFINITION, legacyProjection);

        assertTrue(reconciled.applied());
        assertEquals(ActorLifecycleReason.RELOADED, reconciled.reason());
        assertEquals(ACTOR, reconciled.actorId());
        assertEquals(legacyProjection, service.registry().find(ACTOR).orElseThrow().projectionId());
        assertEquals(1, service.registry().records().size());
    }
}
