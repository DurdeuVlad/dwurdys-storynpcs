package com.storynpcs.service;

import com.storynpcs.api.event.CanonicalMutationEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.QuestCompleteEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class QuestProgressionMutationTest {
    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository repository;
    private EventPublisher events;
    private List<CanonicalMutationEvent> canonicalEvents;
    private List<StoryNpcsEvent> publishedEvents;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        repository = new ProgressionRepository(tempDir);
        events = new EventPublisher();
        canonicalEvents = new ArrayList<>();
        publishedEvents = new ArrayList<>();
        events.register(event -> {
            publishedEvents.add(event);
            if (event instanceof CanonicalMutationEvent canonical) canonicalEvents.add(canonical);
        });
        service = new StoryNpcsApplicationService(registry, repository, events);

        Quest quest = new Quest(NamespacedId.of("storynpcs:collect_wood"), "Collect wood");
        quest.setObjectives(List.of(new QuestObjective(
                "logs", QuestObjective.Type.COLLECT_ITEM, "minecraft:oak_log", 10)));
        registry.registerQuest(quest);
    }

    @Test
    void typedProgressIsPlayerScopedRevisionedAndReplaySafeAcrossRestart() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        UUID startId = UUID.randomUUID();
        CanonicalMutationResult started = service.mutateQuestProgression(
                QuestProgressionMutationRequest.start("system", null, player, questId, 0, startId));

        assertThat(started.applied()).isTrue();
        assertThat(started.revision()).isEqualTo(1);

        UUID progressId = UUID.randomUUID();
        QuestProgressionMutationRequest progressRequest = QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 3, 1, progressId);
        CanonicalMutationResult progressed = service.mutateQuestProgression(progressRequest);

        assertThat(progressed.applied()).isTrue();
        assertThat(progressed.revision()).isEqualTo(2);
        assertThat(repository.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(3);
        assertThat(canonicalEvents).hasSize(2);
        assertThat(canonicalEvents.get(0).subjectId()).isEqualTo(player);

        CanonicalMutationResult sameProcessReplay = service.mutateQuestProgression(progressRequest);
        assertThat(sameProcessReplay.applied()).isTrue();
        assertThat(sameProcessReplay.duplicate()).isTrue();
        assertThat(canonicalEvents).hasSize(2);

        repository.clearCache();
        StoryNpcsApplicationService restartedService = new StoryNpcsApplicationService(registry, repository, events);
        CanonicalMutationResult replayed = restartedService.mutateQuestProgression(progressRequest);

        assertThat(replayed.applied()).isFalse();
        assertThat(replayed.duplicate()).isFalse();
        assertThat(replayed.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("STALE_REVISION"));
        assertThat(replayed.revision()).isEqualTo(2);
        assertThat(repository.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(3);
        assertThat(canonicalEvents).hasSize(3);
    }

    @Test
    void playerRevisionsAreIndependentAndStaleRequestsDoNotMutate() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");

        CanonicalMutationResult firstStart = service.mutateQuestProgression(
                QuestProgressionMutationRequest.start("system", null, firstPlayer, questId, 0, UUID.randomUUID()));
        CanonicalMutationResult secondStart = service.mutateQuestProgression(
                QuestProgressionMutationRequest.start("system", null, secondPlayer, questId, 0, UUID.randomUUID()));

        assertThat(firstStart.applied()).isTrue();
        assertThat(secondStart.applied()).isTrue();
        assertThat(firstStart.revision()).isEqualTo(1);
        assertThat(secondStart.revision()).isEqualTo(1);

        CanonicalMutationResult stale = service.mutateQuestProgression(
                QuestProgressionMutationRequest.progress(
                        "system", null, firstPlayer, questId, "logs", 2, 0, UUID.randomUUID()));

        assertThat(stale.applied()).isFalse();
        assertThat(stale.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("STALE_REVISION"));
        assertThat(repository.getOrCreate(firstPlayer).getQuestState(questId).getCount("logs")).isZero();
        assertThat(repository.getOrCreate(firstPlayer).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.IN_PROGRESS);
    }

    @Test
    void reusingAProgressionRequestIdForDifferentPayloadFailsClosed() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        service.mutateQuestProgression(QuestProgressionMutationRequest.start(
                "system", null, player, questId, 0, UUID.randomUUID()));

        UUID requestId = UUID.randomUUID();
        service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 2, 1, requestId));
        CanonicalMutationResult mismatch = service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 5, 1, requestId));

        assertThat(mismatch.applied()).isFalse();
        assertThat(mismatch.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("REQUEST_PAYLOAD_MISMATCH"));
        assertThat(repository.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(2);
    }

    @Test
    void objectiveCompletionKeepsTheExistingAutomaticTurnInBehavior() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        service.startQuest(player, questId);

        QuestProgressionMutationRequest request = QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 10, service.currentQuestProgressionRevision(player),
                UUID.randomUUID());
        CanonicalMutationResult result = service.mutateQuestProgression(request);

        assertThat(result.applied()).isTrue();
        assertThat(result.revision()).isEqualTo(service.currentQuestProgressionRevision(player));
        assertThat(result.revision()).isEqualTo(3);
        assertThat(canonicalEvents).filteredOn(event -> event.requestId().equals(request.requestId()))
                .singleElement().satisfies(event -> assertThat(event.revision()).isEqualTo(result.revision()));
        assertThat(repository.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.COMPLETED);
        CanonicalMutationResult replay = service.mutateQuestProgression(request);
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.revision()).isEqualTo(result.revision());
    }

    @Test
    void completionAdvancesRevisionAndRejectsAStartPreparedBeforeCompletion() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        registry.getQuest(questId).orElseThrow().setRepeatType(Quest.RepeatType.REPEATABLE);
        service.startQuest(player, questId);
        long preCompletionRevision = service.currentQuestProgressionRevision(player);
        QuestProgressionMutationRequest delayedStart = QuestProgressionMutationRequest.start(
                "system", null, player, questId, preCompletionRevision, UUID.randomUUID());

        QuestCompletionResult completion = service.completeQuest(player, questId);
        CanonicalMutationResult staleStart = service.mutateQuestProgression(delayedStart);

        assertThat(completion.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(service.currentQuestProgressionRevision(player)).isEqualTo(preCompletionRevision + 1);
        assertThat(staleStart.applied()).isFalse();
        assertThat(staleStart.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("STALE_REVISION"));
        assertThat(repository.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.COMPLETED);
    }

    @Test
    void reentrantCanonicalListenerCannotOvertakeOuterObjectiveEvent() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        service.startQuest(player, questId);
        publishedEvents.clear();
        UUID outerRequestId = UUID.randomUUID();
        UUID nestedRequestId = UUID.randomUUID();
        boolean[] nested = {false};
        events.register(event -> {
            if (event instanceof CanonicalMutationEvent canonical && canonical.requestId().equals(outerRequestId)
                    && !nested[0]) {
                nested[0] = true;
                service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                        "system", null, player, questId, "logs", 1,
                        service.currentQuestProgressionRevision(player), nestedRequestId));
            }
        });

        service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 1,
                service.currentQuestProgressionRevision(player), outerRequestId));

        List<String> eventOrder = publishedEvents.stream()
                .filter(event -> event instanceof CanonicalMutationEvent
                        || event instanceof com.storynpcs.api.event.QuestObjectiveProgressEvent)
                .map(event -> event instanceof CanonicalMutationEvent ? "canonical" : "objective")
                .toList();
        assertThat(eventOrder).containsExactly("canonical", "objective", "canonical", "objective");
    }

    @Test
    void concurrentProgressCommitsKeepTheirNotificationGroupsTogether() throws Exception {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        registry.getQuest(questId).orElseThrow().getObjectives().getFirst()
                .setRequiredCount(QuestProgressState.MAX_OBJECTIVE_COUNT);
        service.startQuest(player, questId);
        publishedEvents.clear();

        int callers = 32;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(callers);
        try {
            List<java.util.concurrent.Future<Boolean>> mutations = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                mutations.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                    for (int attempt = 0; attempt < callers * 2; attempt++) {
                        long revision = service.currentQuestProgressionRevision(player);
                        CanonicalMutationResult result = service.mutateQuestProgression(
                                QuestProgressionMutationRequest.progress("system", null, player, questId, "logs", 1,
                                        revision, UUID.randomUUID()));
                        if (result.applied()) return true;
                        if (result.diagnostics().getErrors().stream()
                                .noneMatch(diagnostic -> diagnostic.code().equals("STALE_REVISION"))) return false;
                    }
                    return false;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var mutation : mutations) assertThat(mutation.get(10, TimeUnit.SECONDS)).isTrue();

            List<String> successfulEventOrder = publishedEvents.stream()
                    .filter(event -> event instanceof com.storynpcs.api.event.QuestObjectiveProgressEvent
                            || (event instanceof CanonicalMutationEvent canonical && canonical.applied()))
                    .map(event -> event instanceof CanonicalMutationEvent ? "canonical" : "objective")
                    .toList();
            assertThat(successfulEventOrder).hasSize(callers * 2);
            for (int index = 0; index < successfulEventOrder.size(); index += 2) {
                assertThat(successfulEventOrder.subList(index, index + 2))
                        .containsExactly("canonical", "objective");
            }
            assertThat(repository.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(callers);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void registeringAListenerDuringDeliveryDoesNotStrandTheQuestEventQueue() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        service.startQuest(player, questId);
        publishedEvents.clear();
        List<StoryNpcsEvent> registeredDuringDispatch = new ArrayList<>();
        boolean[] registered = {false};
        events.register(event -> {
            if (!registered[0]) {
                registered[0] = true;
                events.register(registeredDuringDispatch::add);
            }
        });

        CanonicalMutationResult result = service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 1,
                service.currentQuestProgressionRevision(player), UUID.randomUUID()));

        assertThat(result.applied()).isTrue();
        assertThat(registeredDuringDispatch)
                .anyMatch(com.storynpcs.api.event.QuestObjectiveProgressEvent.class::isInstance);
        assertThat(publishedEvents)
                .anyMatch(com.storynpcs.api.event.QuestObjectiveProgressEvent.class::isInstance);
    }

    @Test
    void nonfatalListenerErrorDoesNotStrandQuestNotificationDelivery() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        service.startQuest(player, questId);
        publishedEvents.clear();
        events.register(event -> {
            if (event instanceof CanonicalMutationEvent canonical && canonical.applied()) {
                throw new AssertionError("injected listener assertion");
            }
        });

        CanonicalMutationResult result = service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 1,
                service.currentQuestProgressionRevision(player), UUID.randomUUID()));

        assertThat(result.applied()).isTrue();
        assertThat(publishedEvents)
                .anyMatch(com.storynpcs.api.event.QuestObjectiveProgressEvent.class::isInstance);
        assertThat(service.currentQuestProgressionRevision(player)).isEqualTo(result.revision());
    }

    @Test
    void publisherFailureDoesNotStrandLaterQuestNotificationGroups() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        List<StoryNpcsEvent> delivered = new ArrayList<>();
        EventPublisher failFirstPublish = new EventPublisher() {
            private boolean failed;

            @Override
            public void publish(StoryNpcsEvent event) {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("injected publisher failure");
                }
                super.publish(event);
            }
        };
        failFirstPublish.register(delivered::add);
        StoryNpcsApplicationService isolatedService = new StoryNpcsApplicationService(
                registry, repository, failFirstPublish);

        isolatedService.startQuest(player, questId);
        CanonicalMutationResult progress = isolatedService.mutateQuestProgression(
                QuestProgressionMutationRequest.progress("system", null, player, questId, "logs", 1,
                        isolatedService.currentQuestProgressionRevision(player), UUID.randomUUID()));

        assertThat(progress.applied()).isTrue();
        assertThat(delivered).anyMatch(com.storynpcs.api.event.QuestStartEvent.class::isInstance);
        assertThat(delivered).anyMatch(com.storynpcs.api.event.QuestObjectiveProgressEvent.class::isInstance);
        assertThat(isolatedService.currentQuestProgressionRevision(player)).isEqualTo(progress.revision());
    }

    @Test
    void failedAutomaticCompletionCanBeRetriedWithoutRepeatingProgress() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        NamespacedId factionId = NamespacedId.of("storynpcs:retry_faction");
        registry.registerFaction(new Faction(factionId, "Retry faction", 0, -100, 100));
        registry.getQuest(questId).orElseThrow().setRewards(
                List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, factionId.toString(), 7)));

        ProgressionRepository failCompletionOnce = new ProgressionRepository(tempDir.resolve("completion-retry")) {
            private int saves;

            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression) throws IOException {
                if (++saves == 3) throw new IOException("injected completion save failure");
                super.writeProgression(playerUuid, progression);
            }
        };
        EventPublisher retryEvents = new EventPublisher();
        List<StoryNpcsEvent> retryNotifications = new ArrayList<>();
        retryEvents.register(retryNotifications::add);
        StoryNpcsApplicationService retryService = new StoryNpcsApplicationService(registry,
                failCompletionOnce, retryEvents);
        retryService.startQuest(player, questId);
        QuestProgressionMutationRequest request = QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 10,
                retryService.currentQuestProgressionRevision(player), UUID.randomUUID());

        CanonicalMutationResult pending = retryService.mutateQuestProgression(request);

        assertThat(pending.applied()).isTrue();
        assertThat(pending.recoveryOutcome()).isEqualTo("PROGRESSION_COMMITTED_COMPLETION_PENDING");
        assertThat(failCompletionOnce.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(10);
        assertThat(failCompletionOnce.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(failCompletionOnce.getOrCreate(player).getFactionScore(factionId, 0)).isZero();

        CanonicalMutationResult replay = retryService.mutateQuestProgression(request);

        assertThat(replay.applied()).isTrue();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.recoveryOutcome()).isEqualTo("COMPLETION_REPLAYED");
        assertThat(retryService.currentQuestProgressionRevision(player)).isEqualTo(3);
        assertThat(failCompletionOnce.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(10);
        assertThat(failCompletionOnce.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.COMPLETED);
        assertThat(failCompletionOnce.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(7);
        assertThat(retryNotifications.stream().filter(QuestCompleteEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void pendingCompletionReplayAfterRestartUsesDurableIntentWithoutRepeatingProgress() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        NamespacedId factionId = NamespacedId.of("storynpcs:restart_retry_faction");
        registry.registerFaction(new Faction(factionId, "Restart retry faction", 0, -100, 100));
        registry.getQuest(questId).orElseThrow().setRewards(
                List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, factionId.toString(), 7)));
        ProgressionRepository failCompletionOnce = new ProgressionRepository(tempDir.resolve("restart-retry")) {
            private int saves;

            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression) throws IOException {
                if (++saves == 3) throw new IOException("injected completion save failure");
                super.writeProgression(playerUuid, progression);
            }
        };
        StoryNpcsApplicationService firstService = new StoryNpcsApplicationService(registry,
                failCompletionOnce, events);
        firstService.startQuest(player, questId);
        QuestProgressionMutationRequest request = QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 10,
                firstService.currentQuestProgressionRevision(player), UUID.randomUUID());

        CanonicalMutationResult pending = firstService.mutateQuestProgression(request);
        assertThat(pending.recoveryOutcome()).isEqualTo("PROGRESSION_COMMITTED_COMPLETION_PENDING");
        failCompletionOnce.clearCache();
        StoryNpcsApplicationService restartedService = new StoryNpcsApplicationService(registry,
                failCompletionOnce, events);

        CanonicalMutationResult replay = restartedService.mutateQuestProgression(request);

        assertThat(replay.applied()).isTrue();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.recoveryOutcome()).isEqualTo("COMPLETION_REPLAYED");
        assertThat(restartedService.currentQuestProgressionRevision(player)).isEqualTo(3);
        assertThat(failCompletionOnce.getOrCreate(player).getQuestState(questId).getCount("logs")).isEqualTo(10);
        assertThat(failCompletionOnce.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.COMPLETED);
        assertThat(failCompletionOnce.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(7);
        assertThat(publishedEvents.stream().filter(QuestCompleteEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void pendingCompletionReplayCannotCompleteAfterObjectiveProgressIsWithdrawn() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        NamespacedId factionId = NamespacedId.of("storynpcs:late_reward_faction");
        registry.getQuest(questId).orElseThrow().setRewards(
                List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, factionId.toString(), 7)));
        service.startQuest(player, questId);
        QuestProgressionMutationRequest completionRequest = QuestProgressionMutationRequest.progress(
                "system", null, player, questId, "logs", 10,
                service.currentQuestProgressionRevision(player), UUID.randomUUID());

        CanonicalMutationResult pending = service.mutateQuestProgression(completionRequest);
        CanonicalMutationResult withdrawProgress = service.mutateQuestProgression(
                QuestProgressionMutationRequest.progress("system", null, player, questId, "logs", -10,
                        pending.revision(), UUID.randomUUID()));
        registry.registerFaction(new Faction(factionId, "Late reward faction", 0, -100, 100));

        CanonicalMutationResult staleCompletionReplay = service.mutateQuestProgression(completionRequest);

        assertThat(pending.recoveryOutcome()).isEqualTo("PROGRESSION_COMMITTED_COMPLETION_PENDING");
        assertThat(withdrawProgress.applied()).isTrue();
        assertThat(staleCompletionReplay.applied()).isTrue();
        assertThat(staleCompletionReplay.duplicate()).isTrue();
        assertThat(staleCompletionReplay.recoveryOutcome()).isEqualTo("COMPLETION_NO_LONGER_ELIGIBLE");
        assertThat(repository.getOrCreate(player).getQuestState(questId).getCount("logs")).isZero();
        assertThat(repository.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isZero();

        CanonicalMutationResult freshCompletion = service.mutateQuestProgression(
                QuestProgressionMutationRequest.progress("system", null, player, questId, "logs", 10,
                        service.currentQuestProgressionRevision(player), UUID.randomUUID()));
        assertThat(freshCompletion.recoveryOutcome()).isEqualTo("COMMITTED");
        assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(7);
    }

    @Test
    void anotherPlayerCannotMutateTheSubjectsQuestProgression() {
        UUID actor = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        QuestProgressionMutationRequest request = new QuestProgressionMutationRequest(
                "player", actor, subject, questId, QuestProgressionMutationRequest.Action.START,
                "", 0, 0, UUID.randomUUID(), 0);

        CanonicalMutationResult result = service.mutateQuestProgression(request);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("PLAYER_SUBJECT_MISMATCH"));
        assertThat(repository.getOrCreate(subject).getQuests()).isEmpty();
        assertThat(repository.getOrCreate(subject).getQuestRevision()).isZero();
    }

    @Test
    void commandCannotChangeAnotherPlayersQuestWithoutOperatorPermission() {
        UUID operator = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        QuestProgressionMutationRequest request = new QuestProgressionMutationRequest(
                "command", operator, subject, questId, QuestProgressionMutationRequest.Action.START,
                "", 0, 0, UUID.randomUUID(), 0);

        CanonicalMutationResult result = service.mutateQuestProgression(request);

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("PERMISSION_DENIED"));
        assertThat(repository.getOrCreate(subject).getQuests()).isEmpty();
    }

    @Test
    void legacyProgressionFileLoadsWithRevisionZero() throws IOException {
        UUID player = UUID.randomUUID();
        String oldJson = "{\"schemaVersion\":1,\"data\":{\"playerUuid\":\"" + player
                + "\",\"quests\":{},\"factionPoints\":{},\"visitedDialogueNodes\":[]}}";
        Files.writeString(tempDir.resolve(player + ".json"), oldJson);

        ProgressionRepository oldRepository = new ProgressionRepository(tempDir);

        assertThat(oldRepository.getOrCreate(player).getQuestRevision()).isZero();
    }

    @Test
    void concurrentQuestTurnInsApplyFactionRewardsOnlyOnce() throws Exception {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:concurrent_reward");
        NamespacedId factionId = NamespacedId.of("storynpcs:reward_faction");
        registry.registerFaction(new Faction(factionId, "Reward faction", 0, -100, 100));
        Quest quest = new Quest(questId, "Concurrent reward");
        quest.setRewards(List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, factionId.toString(), 7)));
        registry.registerQuest(quest);
        service.startQuest(player, questId);

        int callers = 24;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(callers);
        try {
            List<java.util.concurrent.Future<QuestCompletionResult>> completions = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                completions.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                    return service.completeQuest(player, questId);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<QuestCompletionResult> results = new ArrayList<>();
            for (var completion : completions) results.add(completion.get(5, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.outcome() == QuestCompletionResult.Outcome.COMPLETED))
                    .hasSize(1);
            assertThat(results.stream().filter(result -> result.outcome() == QuestCompletionResult.Outcome.ALREADY_COMPLETED))
                    .hasSize(callers - 1);
            assertThat(repository.getOrCreate(player).getFactionScore(factionId, 0)).isEqualTo(7);
            assertThat(publishedEvents.stream().filter(QuestCompleteEvent.class::isInstance)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedDurableWriteRestoresTheCachedProgressionWithoutPublishingSuccess() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        ProgressionRepository failingRepository = new ProgressionRepository(tempDir.resolve("failing")) {
            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression) throws IOException {
                throw new IOException("injected persistence failure");
            }
        };
        StoryNpcsApplicationService failingService = new StoryNpcsApplicationService(registry,
                failingRepository, events);

        CanonicalMutationResult result = failingService.mutateQuestProgression(
                QuestProgressionMutationRequest.start("system", null, player, questId, 0, UUID.randomUUID()));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("PROGRESSION_COMMIT_FAILED"));
        assertThat(failingRepository.getOrCreate(player).getQuests()).isEmpty();
        assertThat(failingRepository.getOrCreate(player).getQuestRevision()).isZero();
        assertThat(canonicalEvents).noneMatch(CanonicalMutationEvent::applied);
    }

    @Test
    void experienceRewardIsDeliveredExactlyOnceAcrossFailedCommitAndRestart() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        registry.getQuest(questId).orElseThrow().setRewards(
                List.of(new QuestReward(QuestReward.Type.EXPERIENCE, "levels", 5)));

        // Save order inside completeQuest: intent(2) -> reward mark(3) -> terminal commit(4).
        // startQuest performs save 1. Failing save 4 crashes between reward delivery
        // and the terminal commit — the exact window from the review finding.
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        ProgressionRepository failCommitOnce = new ProgressionRepository(tempDir.resolve("xp-commit-fail")) {
            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression) throws IOException {
                if (saves.incrementAndGet() == 4) {
                    throw new IOException("injected terminal completion commit failure");
                }
                super.writeProgression(playerUuid, progression);
            }
        };
        StoryNpcsApplicationService crashService = new StoryNpcsApplicationService(registry,
                failCommitOnce, events);
        java.util.concurrent.atomic.AtomicInteger xpDeliveries = new java.util.concurrent.atomic.AtomicInteger();
        crashService.setRewardSideEffectOverride((p, reward) -> xpDeliveries.incrementAndGet());

        crashService.startQuest(player, questId);
        QuestCompletionResult crashed = crashService.completeQuest(player, questId);

        assertThat(crashed.outcome()).isEqualTo(QuestCompletionResult.Outcome.FAILED);
        assertThat(crashed.code()).isEqualTo("REWARD_EXECUTION_FAILED");
        assertThat(xpDeliveries.get()).isEqualTo(1);
        assertThat(failCommitOnce.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.IN_PROGRESS);

        // In-process retry: the durable mark suppresses a second delivery.
        QuestCompletionResult retried = crashService.completeQuest(player, questId);
        assertThat(retried.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(xpDeliveries.get()).isEqualTo(1);
        assertThat(failCommitOnce.getOrCreate(player).getDeliveredQuestRewards()).isEmpty();

        // Restart replay: the completed record loads clean and still cannot re-deliver.
        failCommitOnce.clearCache();
        StoryNpcsApplicationService restarted = new StoryNpcsApplicationService(registry,
                failCommitOnce, events);
        restarted.setRewardSideEffectOverride((p, reward) -> xpDeliveries.incrementAndGet());
        QuestCompletionResult replayed = restarted.completeQuest(player, questId);
        assertThat(replayed.outcome()).isEqualTo(QuestCompletionResult.Outcome.ALREADY_COMPLETED);
        assertThat(xpDeliveries.get()).isEqualTo(1);
    }

    @Test
    void rewardDeliveryCrashAfterDurableMarkIsNeverRedelivered() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        registry.getQuest(questId).orElseThrow().setRewards(
                List.of(new QuestReward(QuestReward.Type.ITEM, "minecraft:diamond", 1)));

        java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
        service.setRewardSideEffectOverride((p, reward) -> {
            deliveries.incrementAndGet();
            throw new IllegalStateException("injected delivery crash after durable mark");
        });
        service.startQuest(player, questId);

        QuestCompletionResult crashed = service.completeQuest(player, questId);

        assertThat(crashed.outcome()).isEqualTo(QuestCompletionResult.Outcome.FAILED);
        assertThat(deliveries.get()).isEqualTo(1);
        // The mark is durable even though the side effect crashed.
        PlayerProgression marked = repository.getOrCreate(player);
        assertThat(marked.getDeliveredQuestRewards()).containsKey(questId);

        // Retry prefers losing the reward over double-granting it: the marked key is skipped.
        service.setRewardSideEffectOverride((p, reward) -> deliveries.incrementAndGet());
        repository.clearCache(); // force reload of the durable mark
        QuestCompletionResult retried = service.completeQuest(player, questId);

        assertThat(retried.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(repository.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.COMPLETED);
    }

    @Test
    void rewardMarkPersistenceFailureAbortsBeforeAnyDelivery() {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        registry.getQuest(questId).orElseThrow().setRewards(
                List.of(new QuestReward(QuestReward.Type.EXPERIENCE, "levels", 5)));

        // Fail the reward-mark write (save 3): nothing may be delivered, and the
        // in-memory state must be restored so a retry can complete cleanly once.
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        ProgressionRepository failMarkOnce = new ProgressionRepository(tempDir.resolve("xp-mark-fail")) {
            @Override
            protected void writeProgression(UUID playerUuid, PlayerProgression progression) throws IOException {
                if (saves.incrementAndGet() == 3) {
                    throw new IOException("injected reward mark failure");
                }
                super.writeProgression(playerUuid, progression);
            }
        };
        StoryNpcsApplicationService markFailService = new StoryNpcsApplicationService(registry,
                failMarkOnce, events);
        java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
        markFailService.setRewardSideEffectOverride((p, reward) -> deliveries.incrementAndGet());

        markFailService.startQuest(player, questId);
        QuestCompletionResult aborted = markFailService.completeQuest(player, questId);

        assertThat(aborted.outcome()).isEqualTo(QuestCompletionResult.Outcome.FAILED);
        assertThat(deliveries.get()).isZero();
        assertThat(failMarkOnce.getOrCreate(player).getDeliveredQuestRewards()).isEmpty();
        assertThat(failMarkOnce.getOrCreate(player).getQuestState(questId).getStatus())
                .isEqualTo(QuestProgressState.Status.IN_PROGRESS);

        // The retry marks, delivers, and commits exactly once.
        QuestCompletionResult retried = markFailService.completeQuest(player, questId);
        assertThat(retried.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(deliveries.get()).isEqualTo(1);
    }

    @Test
    void unavailableProgressionFailsQuestCompletionAndMutationClosed() throws IOException {
        UUID player = UUID.randomUUID();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        Files.writeString(tempDir.resolve(player + ".json"), "{ corrupt progression");

        QuestCompletionResult completion = service.completeQuest(player, questId);
        assertThat(completion.outcome()).isEqualTo(QuestCompletionResult.Outcome.FAILED);
        assertThat(completion.code()).isEqualTo("PROGRESSION_UNAVAILABLE");
        assertThat(repository.isUnavailable(player)).isTrue();

        CanonicalMutationResult mutation = service.mutateQuestProgression(
                QuestProgressionMutationRequest.start("system", null, player, questId, 0, UUID.randomUUID()));
        assertThat(mutation.applied()).isFalse();
        assertThat(mutation.diagnostics().getErrors()).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("PROGRESSION_UNAVAILABLE"));
        // No success events may escape for a blocked player.
        assertThat(publishedEvents).noneMatch(QuestCompleteEvent.class::isInstance);
    }
}
