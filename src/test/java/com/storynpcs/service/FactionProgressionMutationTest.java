package com.storynpcs.service;

import com.storynpcs.api.event.CanonicalMutationEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.FactionReputationChangeEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
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

class FactionProgressionMutationTest {
    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository repository;
    private EventPublisher events;
    private List<CanonicalMutationEvent> canonicalEvents;
    private List<FactionReputationChangeEvent> reputationEvents;
    private StoryNpcsApplicationService service;
    private final NamespacedId factionId = NamespacedId.of("storynpcs:townsfolk");

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        repository = new ProgressionRepository(tempDir);
        events = new EventPublisher();
        canonicalEvents = new ArrayList<>();
        reputationEvents = new ArrayList<>();
        events.register(event -> {
            if (event instanceof CanonicalMutationEvent canonical) canonicalEvents.add(canonical);
            if (event instanceof FactionReputationChangeEvent reputation) reputationEvents.add(reputation);
        });
        service = new StoryNpcsApplicationService(registry, repository, events);
        registry.registerFaction(new Faction(factionId, "Townsfolk", 0, -50, 50));
    }

    @Test
    void typedSetIsPlayerScopedRevisionedAndReplaySafeAcrossRestart() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        CanonicalMutationResult set = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 250, 0, requestId, -1));

        assertThat(set.applied()).isTrue();
        assertThat(set.duplicate()).isFalse();
        assertThat(set.revision()).isEqualTo(1);
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(250);
        assertThat(canonicalEvents).hasSize(1);
        assertThat(canonicalEvents.get(0).subjectId()).isEqualTo(player);
        assertThat(reputationEvents).hasSize(1);
        assertThat(reputationEvents.get(0).oldPoints()).isEqualTo(0);
        assertThat(reputationEvents.get(0).newPoints()).isEqualTo(250);

        CanonicalMutationResult sameProcessReplay = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 250, 0, requestId, -1));
        assertThat(sameProcessReplay.applied()).isTrue();
        assertThat(sameProcessReplay.duplicate()).isTrue();
        // Replay must not re-apply the mutation or re-publish events.
        assertThat(canonicalEvents).hasSize(1);
        assertThat(reputationEvents).hasSize(1);

        repository.clearCache();
        StoryNpcsApplicationService restartedService = new StoryNpcsApplicationService(registry, repository, events);
        CanonicalMutationResult afterRestart = restartedService.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 999, 1, UUID.randomUUID(), -1));
        assertThat(afterRestart.applied()).isTrue();
        assertThat(afterRestart.revision()).isEqualTo(2);
    }

    @Test
    void adjustAccumulatesAndClampsWithinBounds() {
        UUID player = UUID.randomUUID();
        CanonicalMutationResult first = service.mutateFactionProgression(
                FactionProgressionMutationRequest.adjust("system", null, player, factionId, 40, 0, UUID.randomUUID(), -1));
        assertThat(first.applied()).isTrue();
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(40);

        CanonicalMutationResult second = service.mutateFactionProgression(
                FactionProgressionMutationRequest.adjust("system", null, player, factionId, 99_990, 1, UUID.randomUUID(), -1));
        assertThat(second.applied()).isTrue();
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(100_000);
    }

    @Test
    void staleExpectedRevisionIsRejectedWithoutMutatingState() {
        UUID player = UUID.randomUUID();
        service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 10, 0, UUID.randomUUID(), -1));

        CanonicalMutationResult stale = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 999, 0, UUID.randomUUID(), -1));

        assertThat(stale.applied()).isFalse();
        assertThat(stale.hasErrors()).isTrue();
        assertThat(stale.formatReport()).contains("STALE_REVISION");
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(10);
    }

    @Test
    void reusedRequestIdWithChangedPayloadIsRejected() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 10, 0, requestId, -1));

        CanonicalMutationResult mismatched = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 20, 0, requestId, -1));

        assertThat(mismatched.applied()).isFalse();
        assertThat(mismatched.formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(10);
    }

    @Test
    void unknownFactionIsRejected() {
        UUID player = UUID.randomUUID();
        CanonicalMutationResult result = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player,
                        NamespacedId.of("storynpcs:nonexistent"), 10, 0, UUID.randomUUID(), -1));

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("FACTION_NOT_FOUND");
    }

    @Test
    void playerActorCannotMutateAnotherPlayersFactionReputation() {
        UUID player = UUID.randomUUID();
        UUID otherActor = UUID.randomUUID();
        CanonicalMutationResult result = service.mutateFactionProgression(
                new FactionProgressionMutationRequest("player", otherActor, player, factionId,
                        FactionProgressionMutationRequest.Action.SET, 500, 0, UUID.randomUUID(), -1));

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("PLAYER_SUBJECT_MISMATCH");
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(0);
    }

    @Test
    void commandActorWithoutPermissionCannotMutateAnotherPlayer() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        CanonicalMutationResult result = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("command", admin, player, factionId, 500, 0, UUID.randomUUID(), 0));

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("PERMISSION_DENIED");
    }

    @Test
    void commandActorWithPermissionCanMutateAnotherPlayer() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        CanonicalMutationResult result = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("command", admin, player, factionId, 500, 0, UUID.randomUUID(), 2));

        assertThat(result.applied()).isTrue();
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(500);
    }

    @Test
    void scriptActorIsAlwaysDenied() {
        UUID player = UUID.randomUUID();
        CanonicalMutationResult result = service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("script", player, player, factionId, 500, 0, UUID.randomUUID(), -1));

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("SCRIPT_CAPABILITY_REQUIRED");
    }

    @Test
    void currentFactionProgressionRevisionReflectsCommittedMutations() {
        UUID player = UUID.randomUUID();
        assertThat(service.currentFactionProgressionRevision(player)).isEqualTo(0);

        service.mutateFactionProgression(
                FactionProgressionMutationRequest.set("system", null, player, factionId, 10, 0, UUID.randomUUID(), -1));

        assertThat(service.currentFactionProgressionRevision(player)).isEqualTo(1);
    }
}
