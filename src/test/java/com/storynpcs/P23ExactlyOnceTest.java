package com.storynpcs;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.transport.TransportLocation;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.persistence.DurableOperationJournal;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.service.FactionProgressionMutationRequest;
import com.storynpcs.service.PlayerProgressionActionRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #57 / P2-3 fixtures: durable request-id dedup for progression-backed
 * mutations (faction adjust, transport unlock) and the durable operation
 * journal for live-side-effect transport requests.
 */
class P23ExactlyOnceTest {
    private static final NamespacedId FACTION_ID = NamespacedId.of("storynpcs:test_faction");
    private static final NamespacedId LOCATION_ID = NamespacedId.of("storynpcs:ferry_dock");

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository repository;
    private EventPublisher events;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        repository = new ProgressionRepository(tempDir);
        events = new EventPublisher();
        service = new StoryNpcsApplicationService(registry, repository, events);
        registry.registerFaction(new Faction(FACTION_ID, "Test Faction", 0, -1000, 1000));
        registry.registerTransportLocation(new TransportLocation(
                LOCATION_ID, "Ferry Dock", "minecraft:overworld", 0, 64, 0));
    }

    /** A fresh service over a freshly-reloaded repository — a simulated restart. */
    private StoryNpcsApplicationService restartedService() {
        return new StoryNpcsApplicationService(registry, new ProgressionRepository(tempDir), events);
    }

    private static FactionProgressionMutationRequest adjustRequest(
            UUID player, int delta, long expectedRevision, UUID requestId) {
        return FactionProgressionMutationRequest.adjust(
                "player", player, player, FACTION_ID, delta, expectedRevision, requestId);
    }

    @Test
    void factionAdjustReplayAfterRestartDoesNotDoubleApply() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        var first = service.mutateFactionProgression(adjustRequest(player, 10, 0, requestId));
        assertThat(first.applied()).isTrue();
        assertThat(repository.getOrCreate(player).getFactionScore(FACTION_ID, 0)).isEqualTo(10);

        // Restart: the in-memory replay cache is gone; the durable ledger in
        // the persisted progression must classify the replay.
        var replayed = restartedService().mutateFactionProgression(
                adjustRequest(player, 10, 0, requestId));

        assertThat(replayed.applied()).isTrue();
        assertThat(replayed.duplicate()).isTrue();
        assertThat(replayed.revision()).isEqualTo(1L);
        PlayerProgression reloaded = new ProgressionRepository(tempDir).getOrCreate(player);
        assertThat(reloaded.getFactionScore(FACTION_ID, 0))
                .as("durable dedup must not re-apply a non-idempotent ADJUST")
                .isEqualTo(10);
        assertThat(reloaded.getFactionRevision()).isEqualTo(1L);
    }

    @Test
    void factionReplayWithDifferentPayloadIsRejected() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        assertThat(service.mutateFactionProgression(adjustRequest(player, 10, 0, requestId))
                .applied()).isTrue();

        // Same request id, different amount — fingerprint mismatch, durable.
        var mismatched = restartedService().mutateFactionProgression(
                adjustRequest(player, 25, 0, requestId));

        assertThat(mismatched.applied()).isFalse();
        assertThat(mismatched.hasErrors()).isTrue();
        assertThat(mismatched.formatReport()).contains("REQUEST_PAYLOAD_MISMATCH");
    }

    @Test
    void transportUnlockReplayAfterRestartReturnsRecordedOutcome() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request = new PlayerProgressionActionRequest(
                "transport.unlock", "player", player, player, requestId, -1);

        var first = service.unlockTransportLocation(request, LOCATION_ID);
        assertThat(first.applied()).isTrue();
        assertThat(first.duplicate()).isFalse();

        var replayed = restartedService().unlockTransportLocation(request, LOCATION_ID);
        assertThat(replayed.applied()).isTrue();
        assertThat(replayed.duplicate()).isTrue();

        // A replay with the same request id bound to another location fails closed.
        var other = new PlayerProgressionActionRequest(
                "transport.unlock", "player", player, player, requestId, -1);
        registry.registerTransportLocation(new TransportLocation(
                NamespacedId.of("storynpcs:gate"), "Gate", "minecraft:overworld", 1, 64, 1));
        var mismatch = restartedService().unlockTransportLocation(
                other, NamespacedId.of("storynpcs:gate"));
        assertThat(mismatch.applied()).isFalse();
        assertThat(mismatch.decision().code()).isEqualTo("REQUEST_PAYLOAD_MISMATCH");
    }

    @Test
    void transportRequestJournalsTerminalOutcomeForDurableReplay() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request = new PlayerProgressionActionRequest(
                "transport.request", "player", player, player, requestId, -1);

        // No MinecraftServer: the operation terminates rejected-but-durable.
        var first = service.requestTransport(request, LOCATION_ID);
        assertThat(first.approved()).isFalse();
        assertThat(first.detail()).isEqualTo("SERVER_UNAVAILABLE");

        // Replay after restart must return the recorded outcome, not re-execute.
        var replayed = restartedService().requestTransport(request, LOCATION_ID);
        assertThat(replayed.approved()).isFalse();
        assertThat(replayed.detail()).isEqualTo("SERVER_UNAVAILABLE");
    }

    @Test
    void transportRequestWithPreparedRecordFailsClosed() throws IOException {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        // Plant a PREPARED record as a crash between prepare and commit would.
        var journal = new DurableOperationJournal(tempDir.resolve("transport-operations"));
        journal.begin(requestId, "transport.request", player + "|" + LOCATION_ID);

        var request = new PlayerProgressionActionRequest(
                "transport.request", "player", player, player, requestId, -1);
        var result = service.requestTransport(request, LOCATION_ID);

        assertThat(result.approved()).isFalse();
        assertThat(result.detail()).isEqualTo("RECOVERY_REQUIRED");
        // Login recovery must surface the unresolved prepared record.
        assertThat(service.recoverTransportOperations(player)).isEqualTo(1);
        assertThat(service.recoverTransportOperations(UUID.randomUUID())).isZero();
    }

    @Test
    void transportRequestIdReusedAcrossLocationsIsRejected() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        var request = new PlayerProgressionActionRequest(
                "transport.request", "player", player, player, requestId, -1);
        assertThat(service.requestTransport(request, LOCATION_ID).detail())
                .isEqualTo("SERVER_UNAVAILABLE");

        // Same request id, different location: identity mismatch, durable.
        var mismatched = restartedService().requestTransport(request,
                NamespacedId.of("storynpcs:gate"));
        assertThat(mismatched.approved()).isFalse();
        assertThat(mismatched.detail()).startsWith("REQUEST_PAYLOAD_MISMATCH");
    }

    @Test
    void transportUnlockCommitFailureRollsBackMarkerAndRetries() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request = new PlayerProgressionActionRequest(
                "transport.unlock", "player", player, player, requestId, -1);

        ProgressionRepository failingRepository = new ProgressionRepository(tempDir) {
            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression)
                    throws IOException {
                throw new IOException("injected persistence failure");
            }
        };
        var failingService = new StoryNpcsApplicationService(registry, failingRepository, events);

        var failed = failingService.unlockTransportLocation(request, LOCATION_ID);
        assertThat(failed.applied()).isFalse();
        assertThat(failed.decision().code()).isEqualTo("PROGRESSION_COMMIT_FAILED");

        // The rolled-back marker must not suppress retries — in-process or
        // across restart the same request id re-executes rather than
        // replaying a phantom applied outcome.
        assertThat(failingService.unlockTransportLocation(request, LOCATION_ID)
                .decision().code()).isEqualTo("PROGRESSION_COMMIT_FAILED");
        assertThat(failingRepository.getOrCreate(player).appliedActionOutcome(requestId)).isNull();

        var recovered = restartedService();
        var applied = recovered.unlockTransportLocation(request, LOCATION_ID);
        assertThat(applied.applied()).isTrue();
        assertThat(applied.duplicate()).isFalse();
    }

    @Test
    void transportRequestWithCorruptTerminalRecordFailsClosed() throws IOException {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        // Plant a terminal record whose detail payload is malformed — e.g. a
        // multi-line destination name written by an older build corrupts the
        // flat field layout, and a non-numeric fee token must fail closed
        // rather than throw out of the request path.
        var journal = new DurableOperationJournal(tempDir.resolve("transport-operations"));
        journal.begin(requestId, "transport.request", player + "|" + LOCATION_ID);
        journal.commit(requestId, "APPLIED", "true\nFerry\nDock\noops");

        var request = new PlayerProgressionActionRequest(
                "transport.request", "player", player, player, requestId, -1);
        var result = service.requestTransport(request, LOCATION_ID);

        assertThat(result.approved()).isFalse();
        assertThat(result.detail()).isEqualTo("RECOVERY_REQUIRED");
    }
}
