package com.storynpcs;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.companion.CompanionProfile;
import com.storynpcs.domain.companion.WageLedger;
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

        // Fail once, then succeed: if the commit failure were memoized, the
        // in-process retry would replay the denial instead of re-executing.
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean(false);
        ProgressionRepository flakyRepository = new ProgressionRepository(tempDir) {
            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression)
                    throws IOException {
                if (failedOnce.compareAndSet(false, true)) {
                    throw new IOException("injected first-save failure");
                }
                super.writeProgression(playerUuid, progression);
            }
        };
        var flakyService = new StoryNpcsApplicationService(registry, flakyRepository, events);

        var failed = flakyService.unlockTransportLocation(request, LOCATION_ID);
        assertThat(failed.applied()).isFalse();
        assertThat(failed.decision().code()).isEqualTo("PROGRESSION_COMMIT_FAILED");
        // The rolled-back save must have removed the marker with the grant.
        assertThat(flakyRepository.getOrCreate(player).appliedActionOutcome(requestId)).isNull();

        var retried = flakyService.unlockTransportLocation(request, LOCATION_ID);
        assertThat(retried.applied()).isTrue();
        assertThat(retried.duplicate()).isFalse()
                .as("commit failures are not memoized — the retry must re-execute");

        // A permanently failing store keeps denying and never poisons the id.
        ProgressionRepository deadRepository = new ProgressionRepository(tempDir.resolve("dead")) {
            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression)
                    throws IOException {
                throw new IOException("injected persistent failure");
            }
        };
        var deadService = new StoryNpcsApplicationService(registry, deadRepository, events);
        assertThat(deadService.unlockTransportLocation(request, LOCATION_ID)
                .decision().code()).isEqualTo("PROGRESSION_COMMIT_FAILED");
        assertThat(deadRepository.getOrCreate(player).appliedActionOutcome(requestId)).isNull();
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

    // ── companion wages (issue #57 — durable period backstop) ──────────────

    private static CompanionProfile wageProfile() {
        var profile = new CompanionProfile();
        profile.setWageAmount(5);
        profile.setWageIntervalTicks(100);
        return profile;
    }

    @Test
    void companionWageDurableBackstopSuppressesRechargeAfterMarkerLoss()
            throws IOException {
        UUID owner = UUID.randomUUID();
        UUID companion = UUID.randomUUID();

        // Simulate restart: the entity NBT marker is gone (fresh ledger), but
        // the owner progression durably recorded period 2 as charged.
        PlayerProgression ownerProgression = repository.getOrCreate(owner);
        ownerProgression.recordCompanionWagePeriod(companion, 2);
        repository.save(owner, ownerProgression);

        var ledger = new WageLedger(); // entity-side marker lost
        var outcome = service.chargeCompanionWage(owner, companion, wageProfile(), ledger, 0, 250);
        assertThat(outcome).isEqualTo(
                StoryNpcsApplicationService.CompanionWageOutcome.ALREADY_CHARGED);
        // The durable backstop heals the entity ledger forward.
        assertThat(ledger.getLastChargedPeriod()).isEqualTo(2);

        // The marker survives a real reload — a restarted service suppresses
        // the same period without touching the payer.
        var reloaded = new ProgressionRepository(tempDir).getOrCreate(owner);
        assertThat(reloaded.chargedWagePeriod(companion)).isEqualTo(2);
        assertThat(restartedService().chargeCompanionWage(
                owner, companion, wageProfile(), new WageLedger(), 0, 250))
                .isEqualTo(StoryNpcsApplicationService.CompanionWageOutcome.ALREADY_CHARGED);

        // A later period is not suppressed by the stale marker — falls
        // through to the offline-owner policy path (no server in tests).
        assertThat(service.chargeCompanionWage(owner, companion, wageProfile(), ledger, 0, 350))
                .isEqualTo(StoryNpcsApplicationService.CompanionWageOutcome.OWNER_OFFLINE_PAUSED);
    }

    @Test
    void companionWageQuarantinedOwnerRecordFailsClosed() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID companion = UUID.randomUUID();

        // Poison the owner's durable record — loadFromDisk quarantines it and
        // blocks the id. The wage must not charge while the period cannot be
        // durably verified.
        java.nio.file.Files.writeString(
                tempDir.resolve(owner + ".json"), "{ not json !!!");

        var outcome = service.chargeCompanionWage(
                owner, companion, wageProfile(), new WageLedger(), 0, 250);
        assertThat(outcome).isEqualTo(
                StoryNpcsApplicationService.CompanionWageOutcome.PROGRESSION_UNAVAILABLE);
    }

    @Test
    void companionWageBlockedRecordShortCircuitsWithoutRescanningDisk() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID companion = UUID.randomUUID();
        java.nio.file.Files.writeString(tempDir.resolve(owner + ".json"), "{ not json !!!");

        int[] loads = {0};
        var countingRepository = new ProgressionRepository(tempDir) {
            @Override
            public PlayerProgression getOrCreate(UUID playerUuid) {
                loads[0]++;
                return super.getOrCreate(playerUuid);
            }
        };
        var countingService = new StoryNpcsApplicationService(registry, countingRepository, events);

        // First call quarantines the corrupt record and blocks the id.
        assertThat(countingService.chargeCompanionWage(
                owner, companion, wageProfile(), new WageLedger(), 0, 250))
                .isEqualTo(StoryNpcsApplicationService.CompanionWageOutcome.PROGRESSION_UNAVAILABLE);
        assertThat(loads[0]).isEqualTo(1);

        // Blocked ticks short-circuit on isUnavailable — no further disk
        // reads or quarantine scans per wage tick.
        countingService.chargeCompanionWage(owner, companion, wageProfile(), new WageLedger(), 0, 250);
        countingService.chargeCompanionWage(owner, companion, wageProfile(), new WageLedger(), 0, 250);
        assertThat(loads[0]).isEqualTo(1);
    }

    @Test
    void companionWageMarkerEvictionPrefersStaleCompanions() {
        PlayerProgression progression = repository.getOrCreate(UUID.randomUUID());
        UUID live = UUID.randomUUID();
        progression.recordCompanionWagePeriod(live, 0);
        // Fill the ledger beyond capacity with distinct dormant companions.
        for (int i = 0; i < 300; i++) {
            progression.recordCompanionWagePeriod(UUID.randomUUID(), i);
        }
        // Re-recording the live companion refreshes its position — it must
        // survive further evictions while dormant entries age out first.
        progression.recordCompanionWagePeriod(live, 42);
        for (int i = 0; i < 50; i++) {
            progression.recordCompanionWagePeriod(UUID.randomUUID(), 300 + i);
        }
        assertThat(progression.chargedWagePeriod(live)).isEqualTo(42);
        assertThat(progression.getCompanionWagePeriods()).hasSize(256);
    }
}
