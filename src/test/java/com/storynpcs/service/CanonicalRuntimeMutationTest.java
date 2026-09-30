package com.storynpcs.service;

import com.storynpcs.api.event.CanonicalMutationEvent;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.FactionReputationChangeEvent;
import com.storynpcs.api.event.FollowerFormationChangeEvent;
import com.storynpcs.api.event.FollowerStateChangeEvent;
import com.storynpcs.api.event.QuestCompleteEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalRuntimeMutationTest {
    private static final NamespacedId FACTION_ID = NamespacedId.of("storynpcs:test_faction");
    private static final NamespacedId QUEST_ID = NamespacedId.of("storynpcs:test_quest");
    private static final NamespacedId NPC_ID = NamespacedId.of("storynpcs:test_npc");

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository repository;
    private EventPublisher events;
    private List<StoryNpcsEvent> publishedEvents;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
        repository = new ProgressionRepository(tempDir);
        events = new EventPublisher();
        publishedEvents = new ArrayList<>();
        events.register(publishedEvents::add);
        service = new StoryNpcsApplicationService(registry, repository, events);

        registry.registerFaction(new Faction(FACTION_ID, "Test Faction", 0, -20, 20));
        Quest quest = new Quest(QUEST_ID, "Test Quest");
        quest.setObjectives(List.of(new QuestObjective(
                "obj", QuestObjective.Type.COLLECT_ITEM, "minecraft:dirt", 1)));
        registry.registerQuest(quest);
    }

    private static FactionProgressionMutationRequest factionRequest(
            UUID player, FactionProgressionMutationRequest.Action action, int amount,
            long expectedRevision, UUID requestId) {
        return new FactionProgressionMutationRequest(
                "player", player, player, FACTION_ID, action, amount, expectedRevision, requestId, -1);
    }

    private List<CanonicalMutationEvent> canonicalEvents() {
        return publishedEvents.stream()
                .filter(CanonicalMutationEvent.class::isInstance)
                .map(CanonicalMutationEvent.class::cast)
                .toList();
    }

    private <T extends StoryNpcsEvent> List<T> eventsOf(Class<T> type) {
        return publishedEvents.stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ---------- Faction progression ----------

    @Test
    void factionSetAppliesAdvancesRevisionAndPublishesOrderedEvents() {
        UUID player = UUID.randomUUID();

        var result = service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.SET, 55, 0, UUID.randomUUID()));

        assertThat(result.applied()).isTrue();
        assertThat(result.revision()).isEqualTo(1L);
        assertThat(service.currentFactionProgressionRevision(player)).isEqualTo(1L);
        assertThat(repository.getOrCreate(player).getFactionScore(FACTION_ID, 0)).isEqualTo(55);

        List<StoryNpcsEvent> ordered = publishedEvents;
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0)).isInstanceOf(CanonicalMutationEvent.class);
        assertThat(ordered.get(1)).isInstanceOf(FactionReputationChangeEvent.class);
        CanonicalMutationEvent canonical = (CanonicalMutationEvent) ordered.get(0);
        assertThat(canonical.operation()).isEqualTo("faction.progress.set");
        assertThat(canonical.subjectId()).isEqualTo(player);
        FactionReputationChangeEvent reputation = (FactionReputationChangeEvent) ordered.get(1);
        assertThat(reputation.oldPoints()).isEqualTo(0);
        assertThat(reputation.newPoints()).isEqualTo(55);
    }

    @Test
    void factionAdjustClampsToDomainBounds() {
        UUID player = UUID.randomUUID();
        service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.ADJUST, 60, 0, UUID.randomUUID()));

        var result = service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.ADJUST, -1_000_000, 1, UUID.randomUUID()));

        assertThat(result.applied()).isTrue();
        assertThat(repository.getOrCreate(player).getFactionScore(FACTION_ID, 0)).isEqualTo(-100_000);
        assertThat(service.currentFactionProgressionRevision(player)).isEqualTo(2L);
    }

    @Test
    void factionReplayReturnsDuplicateWithoutReapplying() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request = factionRequest(player, FactionProgressionMutationRequest.Action.ADJUST, 40, 0, requestId);

        var first = service.mutateFactionProgression(request);
        var replay = service.mutateFactionProgression(request);

        assertThat(first.applied()).isTrue();
        assertThat(replay.applied()).isTrue();
        assertThat(replay.duplicate()).isTrue();
        assertThat(repository.getOrCreate(player).getFactionScore(FACTION_ID, 0)).isEqualTo(40);
        assertThat(eventsOf(FactionReputationChangeEvent.class)).hasSize(1);
    }

    @Test
    void factionPayloadMismatchOnReusedRequestIdIsRejected() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.SET, 10, 0, requestId));

        var reused = service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.SET, 20, 1, requestId));

        assertThat(reused.applied()).isFalse();
        assertThat(reused.diagnostics().getErrors())
                .anyMatch(error -> "REQUEST_PAYLOAD_MISMATCH".equals(error.code()));
        assertThat(repository.getOrCreate(player).getFactionScore(FACTION_ID, 0)).isEqualTo(10);
    }

    @Test
    void factionStaleRevisionIsRejectedWithoutSideEffects() {
        UUID player = UUID.randomUUID();
        service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.SET, 10, 0, UUID.randomUUID()));

        var stale = service.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.SET, 99, 0, UUID.randomUUID()));

        assertThat(stale.applied()).isFalse();
        assertThat(stale.diagnostics().getErrors())
                .anyMatch(error -> "STALE_REVISION".equals(error.code()));
        assertThat(repository.getOrCreate(player).getFactionScore(FACTION_ID, 0)).isEqualTo(10);
    }

    @Test
    void factionCommitFailureRestoresStateAndRejects() {
        UUID player = UUID.randomUUID();
        ProgressionRepository faulted = new ProgressionRepository(tempDir.resolve("faulted")) {
            @Override
            protected void writeProgression(UUID uuid, PlayerProgression progression) throws IOException {
                throw new IOException("injected persistence failure");
            }
        };
        StoryNpcsApplicationService faultedService =
                new StoryNpcsApplicationService(registry, faulted, events);

        var result = faultedService.mutateFactionProgression(factionRequest(
                player, FactionProgressionMutationRequest.Action.SET, 42, 0, UUID.randomUUID()));

        assertThat(result.applied()).isFalse();
        assertThat(result.diagnostics().getErrors())
                .anyMatch(error -> "PROGRESSION_COMMIT_FAILED".equals(error.code()));
        PlayerProgression progression = faulted.getOrCreate(player);
        assertThat(progression.getFactionScore(FACTION_ID, 0)).isEqualTo(0);
        assertThat(progression.getFactionRevision()).isEqualTo(0L);
    }

    @Test
    void factionAuthorizationIsEnforced() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        var crossSubject = service.mutateFactionProgression(new FactionProgressionMutationRequest(
                "player", other, player, FACTION_ID,
                FactionProgressionMutationRequest.Action.SET, 10, 0, UUID.randomUUID(), -1));
        assertThat(crossSubject.applied()).isFalse();
        assertThat(crossSubject.diagnostics().getErrors())
                .anyMatch(error -> "PLAYER_SUBJECT_MISMATCH".equals(error.code()));

        var scripted = service.mutateFactionProgression(new FactionProgressionMutationRequest(
                "script", player, player, FACTION_ID,
                FactionProgressionMutationRequest.Action.SET, 10, 0, UUID.randomUUID(), -1));
        assertThat(scripted.applied()).isFalse();
        assertThat(scripted.diagnostics().getErrors())
                .anyMatch(error -> "SCRIPT_CAPABILITY_REQUIRED".equals(error.code()));

        var weakCommand = service.mutateFactionProgression(new FactionProgressionMutationRequest(
                "command", other, player, FACTION_ID,
                FactionProgressionMutationRequest.Action.SET, 10, 0, UUID.randomUUID(), 0));
        assertThat(weakCommand.applied()).isFalse();
        assertThat(weakCommand.diagnostics().getErrors())
                .anyMatch(error -> "PERMISSION_DENIED".equals(error.code()));

        var adminCommand = service.mutateFactionProgression(new FactionProgressionMutationRequest(
                "command", other, player, FACTION_ID,
                FactionProgressionMutationRequest.Action.SET, 10, 0, UUID.randomUUID(), 2));
        assertThat(adminCommand.applied()).isTrue();
    }

    // ---------- Quest completion ----------

    @Test
    void typedCompletionAppliesOnceAndReplays() {
        UUID player = UUID.randomUUID();
        Quest rewarded = new Quest(NamespacedId.of("storynpcs:rewarded"), "Rewarded");
        rewarded.setObjectives(List.of(new QuestObjective(
                "obj", QuestObjective.Type.COLLECT_ITEM, "minecraft:dirt", 1)));
        rewarded.setRewards(List.of(new QuestReward(QuestReward.Type.EXPERIENCE, "xp", 25)));
        registry.registerQuest(rewarded);

        AtomicInteger deliveries = new AtomicInteger();
        service.setRewardSideEffectOverride((uuid, reward) -> deliveries.incrementAndGet());

        var request = new QuestCompletionMutationRequest(
                "player", player, player, rewarded.getId(), 0, UUID.randomUUID(), -1);
        var first = service.completeQuest(request);
        var replay = service.completeQuest(request);

        assertThat(first.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(first.rewardsApplied()).isEqualTo(1);
        assertThat(replay.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(eventsOf(QuestCompleteEvent.class)).hasSize(1);
        assertThat(service.currentQuestProgressionRevision(player)).isEqualTo(1L);
    }

    @Test
    void typedCompletionRejectsStaleRevisionButNotIdempotentCompletion() {
        UUID player = UUID.randomUUID();
        service.mutateQuestProgression(QuestProgressionMutationRequest.start(
                "system", null, player, QUEST_ID, 0, UUID.randomUUID()));

        var stale = new QuestCompletionMutationRequest(
                "player", player, player, QUEST_ID, 0, UUID.randomUUID(), -1);
        var staleResult = service.completeQuest(stale);
        assertThat(staleResult.outcome()).isEqualTo(QuestCompletionResult.Outcome.REJECTED);
        assertThat(staleResult.code()).isEqualTo("STALE_REVISION");

        var completed = service.completeQuest(new QuestCompletionMutationRequest(
                "player", player, player, QUEST_ID, 1, UUID.randomUUID(), -1));
        assertThat(completed.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);

        var afterCompletion = new QuestCompletionMutationRequest(
                "player", player, player, QUEST_ID, 0, UUID.randomUUID(), -1);
        assertThat(service.completeQuest(afterCompletion).outcome())
                .isEqualTo(QuestCompletionResult.Outcome.ALREADY_COMPLETED);
    }

    @Test
    void typedCompletionRejectsUnknownQuestAndMissingSubjectAuthority() {
        UUID player = UUID.randomUUID();
        var missing = new QuestCompletionMutationRequest(
                "player", player, player, NamespacedId.of("storynpcs:missing"), 0, UUID.randomUUID(), -1);
        assertThat(service.completeQuest(missing).code()).isEqualTo("QUEST_NOT_FOUND");

        var crossSubject = new QuestCompletionMutationRequest(
                "player", UUID.randomUUID(), player, QUEST_ID, 0, UUID.randomUUID(), -1);
        var denied = service.completeQuest(crossSubject);
        assertThat(denied.outcome()).isEqualTo(QuestCompletionResult.Outcome.REJECTED);
        assertThat(denied.code()).isEqualTo("PLAYER_SUBJECT_MISMATCH");
    }

    @Test
    void factionRewardCompletionAdvancesFactionRevision() {
        UUID player = UUID.randomUUID();
        Quest quest = new Quest(NamespacedId.of("storynpcs:faction_quest"), "Faction quest");
        quest.setObjectives(List.of(new QuestObjective(
                "obj", QuestObjective.Type.COLLECT_ITEM, "minecraft:dirt", 1)));
        quest.setRewards(List.of(new QuestReward(QuestReward.Type.FACTION_POINTS, FACTION_ID.toString(), 15)));
        registry.registerQuest(quest);

        var result = service.completeQuest(new QuestCompletionMutationRequest(
                "player", player, player, quest.getId(), 0, UUID.randomUUID(), -1));

        assertThat(result.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        PlayerProgression progression = repository.getOrCreate(player);
        assertThat(progression.getFactionScore(FACTION_ID, 0)).isEqualTo(15);
        assertThat(progression.getFactionRevision()).isEqualTo(1L);
        assertThat(eventsOf(FactionReputationChangeEvent.class)).hasSize(1);
    }

    // ---------- Follower state ----------

    @Test
    void followerStateMutationAppliesEventsAndReplays() {
        UUID player = UUID.randomUUID();
        FollowerRole role = new FollowerRole(player);
        var request = FollowerStateMutationRequest.setState(
                "player", player, player, NPC_ID, FollowerRole.State.GUARDING, UUID.randomUUID());

        var first = service.mutateFollowerState(request, role);
        var replay = service.mutateFollowerState(request, role);

        assertThat(first.applied()).isTrue();
        assertThat(replay.applied()).isTrue();
        assertThat(replay.duplicate()).isTrue();
        assertThat(role.getState()).isEqualTo(FollowerRole.State.GUARDING);
        assertThat(eventsOf(FollowerStateChangeEvent.class)).hasSize(1);
        assertThat(eventsOf(CanonicalMutationEvent.class)).hasSize(1);
    }

    @Test
    void followerFormationMutationAppliesAtomically() {
        UUID player = UUID.randomUUID();
        FollowerRole role = new FollowerRole(player);
        var result = service.mutateFollowerState(FollowerStateMutationRequest.setFormation(
                "player", player, player, NPC_ID, FormationType.CIRCLE, 3, 4.0, UUID.randomUUID()), role);

        assertThat(result.applied()).isTrue();
        assertThat(role.getFormation()).isEqualTo(FormationType.CIRCLE);
        assertThat(role.getFormationSlot()).isEqualTo(3);
        assertThat(role.getFormationSpacing()).isEqualTo(4.0);
        assertThat(eventsOf(FollowerFormationChangeEvent.class)).hasSize(1);
    }

    @Test
    void followerMutationRejectsUnownedMissingRoleAndCrossSubject() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        var notOwned = service.mutateFollowerState(FollowerStateMutationRequest.setState(
                "player", player, player, NPC_ID, FollowerRole.State.STAYING, UUID.randomUUID()),
                new FollowerRole(other));
        assertThat(notOwned.applied()).isFalse();
        assertThat(notOwned.diagnostics().getErrors())
                .anyMatch(error -> "FOLLOWER_NOT_OWNED".equals(error.code()));

        var missingRole = service.mutateFollowerState(FollowerStateMutationRequest.setState(
                "player", player, player, NPC_ID, FollowerRole.State.STAYING, UUID.randomUUID()), null);
        assertThat(missingRole.applied()).isFalse();
        assertThat(missingRole.diagnostics().getErrors())
                .anyMatch(error -> "FOLLOWER_ROLE_UNAVAILABLE".equals(error.code()));

        var crossSubject = service.mutateFollowerState(FollowerStateMutationRequest.setState(
                "player", other, player, NPC_ID, FollowerRole.State.STAYING, UUID.randomUUID()),
                new FollowerRole(player));
        assertThat(crossSubject.applied()).isFalse();
        assertThat(crossSubject.diagnostics().getErrors())
                .anyMatch(error -> "PLAYER_SUBJECT_MISMATCH".equals(error.code()));
    }

    // ---------- Compatibility delegates ----------

    @Test
    void compatDelegatesRouteThroughCanonicalBoundary() {
        UUID player = UUID.randomUUID();

        service.setFactionPoints(player, FACTION_ID, 30);
        service.adjustFactionPoints(player, FACTION_ID, -5);
        PlayerProgression progression = repository.getOrCreate(player);
        assertThat(progression.getFactionScore(FACTION_ID, 0)).isEqualTo(25);
        assertThat(progression.getFactionRevision()).isEqualTo(2L);

        assertThatThrownBy(() -> service.setFactionPoints(player, NamespacedId.of("storynpcs:none"), 1))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.completeQuest(player, NamespacedId.of("storynpcs:none")))
                .isInstanceOf(NoSuchElementException.class);

        FollowerRole role = new FollowerRole(player);
        assertThat(service.setFollowerState(player, NPC_ID, role, FollowerRole.State.STAYING)).isTrue();
        assertThat(service.setFollowerState(UUID.randomUUID(), NPC_ID, role, FollowerRole.State.FOLLOWING)).isFalse();
    }

    // ---------- Bank & trade operations (P1-4 economy coverage) ----------

    private com.storynpcs.persistence.BankRepository bankRepo() {
        return new com.storynpcs.persistence.BankRepository(
                tempDir.resolve("banks-" + UUID.randomUUID()));
    }

    private BankOperationRequest bankRequest(UUID player, BankOperationRequest.Action action,
                                             int tab, int slot, String itemId, int amount,
                                             UUID requestId) {
        return new BankOperationRequest("player", player, player, NPC_ID, action,
                tab, slot, itemId, amount, requestId, -1);
    }

    @Test
    void bankDepositAuthorizesSubjectBeforeVaultMutation() {
        var repo = bankRepo();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        var denied = service.depositToBank(new BankOperationRequest(
                "player", other, player, NPC_ID, BankOperationRequest.Action.DEPOSIT,
                0, 0, "minecraft:dirt", 4, UUID.randomUUID(), -1), repo, null);
        assertThat(denied.outcome()).isEqualTo(BankDepositOperationResult.Outcome.REJECTED);
        assertThat(denied.code()).isEqualTo("PLAYER_SUBJECT_MISMATCH");
        assertThat(repo.getOrCreate(player).getTabItems(0)).isEmpty();
        assertThat(canonicalEvents()).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("REJECTED_AUTHORIZATION");
            assertThat(event.targetId()).isEqualTo(NPC_ID);
            assertThat(event.subjectId()).isEqualTo(player);
            assertThat(event.actorId()).isEqualTo(other);
        });

        var applied = service.depositToBank(bankRequest(player,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 4,
                UUID.randomUUID()), repo, null);
        assertThat(applied.outcome()).isEqualTo(BankDepositOperationResult.Outcome.APPLIED);
        assertThat(applied.slot()).isZero();
        assertThat(repo.getOrCreate(player).getTabItems(0)).singleElement().satisfies(item -> {
            assertThat(item.getItemId()).isEqualTo("minecraft:dirt");
            assertThat(item.getCount()).isEqualTo(4);
        });
        assertThat(eventsOf(com.storynpcs.api.event.BankTransactionEvent.class)).hasSize(1);
        assertThat(canonicalEvents().stream().filter(e -> e.outcome().equals("COMMITTED")).count())
                .isEqualTo(1);
    }

    @Test
    void bankOperationsDenyScriptsAndUnprovenCrossSubjectCommands() {
        var repo = bankRepo();
        UUID player = UUID.randomUUID();

        var scripted = service.withdrawFromBank(new BankOperationRequest(
                "script", null, player, NPC_ID, BankOperationRequest.Action.WITHDRAW_STACK,
                0, 0, null, 0, UUID.randomUUID(), -1), repo);
        assertThat(scripted.code()).isEqualTo("SCRIPT_CAPABILITY_REQUIRED");

        var unproven = service.depositToBank(new BankOperationRequest(
                "command", UUID.randomUUID(), player, NPC_ID,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1,
                UUID.randomUUID(), -1), repo, null);
        assertThat(unproven.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(repo.getOrCreate(player).getTabItems(0)).isEmpty();

        var proven = service.depositToBank(new BankOperationRequest(
                "command", UUID.randomUUID(), player, NPC_ID,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1,
                UUID.randomUUID(), 2), repo, null);
        assertThat(proven.outcome()).isEqualTo(BankDepositOperationResult.Outcome.APPLIED);
        assertThat(repo.getOrCreate(player).getTabItems(0)).hasSize(1);
    }

    @Test
    void bankWithdrawAndRemoveStackApplyForSelfSubject() {
        var repo = bankRepo();
        UUID player = UUID.randomUUID();
        service.depositToBank(bankRequest(player, BankOperationRequest.Action.DEPOSIT,
                0, 0, "minecraft:dirt", 4, UUID.randomUUID()), repo, null);

        var withdrawn = service.withdrawFromBank(bankRequest(player,
                BankOperationRequest.Action.WITHDRAW, 0, 0, null, 3, UUID.randomUUID()), repo);
        assertThat(withdrawn.accepted()).isTrue();
        assertThat(withdrawn.item().getCount()).isEqualTo(3);

        var removed = service.withdrawFromBank(bankRequest(player,
                BankOperationRequest.Action.REMOVE_STACK, 0, 0, null, 0, UUID.randomUUID()), repo);
        assertThat(removed.accepted()).isTrue();
        assertThat(removed.item().getCount()).isEqualTo(1);
        assertThat(repo.getOrCreate(player).getTabItems(0)).isEmpty();
    }

    @Test
    void bankUnlockAppliesOncePerRequestIdAndDeniesCrossSubject() {
        var repo = bankRepo();
        UUID player = UUID.randomUUID();
        var banker = new com.storynpcs.domain.role.banker.BankerRole("Test Bank");
        banker.setTabUpgradeCost(0);
        banker.setMaxTabs(4);
        UUID requestId = UUID.randomUUID();

        var first = service.unlockBankTab(bankRequest(player,
                BankOperationRequest.Action.UNLOCK_TAB, 0, -1, null, 0, requestId), repo, banker);
        assertThat(first.applied()).isTrue();
        assertThat(repo.getOrCreate(player).getUnlockedTabs()).isEqualTo(2);

        var replay = service.unlockBankTab(bankRequest(player,
                BankOperationRequest.Action.UNLOCK_TAB, 0, -1, null, 0, requestId), repo, banker);
        assertThat(replay.applied()).isTrue();
        assertThat(repo.getOrCreate(player).getUnlockedTabs()).isEqualTo(2);

        var denied = service.unlockBankTab(new BankOperationRequest(
                "player", UUID.randomUUID(), player, NPC_ID,
                BankOperationRequest.Action.UNLOCK_TAB, 0, -1, null, 0,
                UUID.randomUUID(), -1), repo, banker);
        assertThat(denied.applied()).isFalse();
        assertThat(denied.hasErrors()).isTrue();
        assertThat(denied.recoveryOutcome()).isEqualTo("REJECTED_AUTHORIZATION");
        assertThat(repo.getOrCreate(player).getUnlockedTabs()).isEqualTo(2);
    }

    @Test
    void bankActionRouteMismatchFailsClosed() {
        var repo = bankRepo();
        var banker = new com.storynpcs.domain.role.banker.BankerRole("B");
        var mismatched = service.unlockBankTab(bankRequest(UUID.randomUUID(),
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1,
                UUID.randomUUID()), repo, banker);
        assertThat(mismatched.applied()).isFalse();
        assertThat(mismatched.diagnostics().formatReport()).contains("ACTION_ROUTE_MISMATCH");

        var withdrawMismatched = service.depositToBank(bankRequest(UUID.randomUUID(),
                BankOperationRequest.Action.WITHDRAW, 0, 0, null, 1,
                UUID.randomUUID()), repo, null);
        assertThat(withdrawMismatched.code()).isEqualTo("ACTION_ROUTE_MISMATCH");
    }

    @Test
    void bankHeldDepositRequiresLiveServerButStillAuthorizesFirst() {
        var repo = bankRepo();
        UUID player = UUID.randomUUID();

        var denied = service.depositToBank(new BankOperationRequest(
                "player", UUID.randomUUID(), player, NPC_ID,
                BankOperationRequest.Action.DEPOSIT_HELD, 0, -1, null, 0,
                UUID.randomUUID(), -1), repo, null);
        assertThat(denied.code()).isEqualTo("PLAYER_SUBJECT_MISMATCH");

        var attempt = service.depositToBank(bankRequest(player,
                BankOperationRequest.Action.DEPOSIT_HELD, 0, -1, null, 0,
                UUID.randomUUID()), repo, null);
        assertThat(attempt.outcome()).isEqualTo(BankDepositOperationResult.Outcome.REJECTED);
        assertThat(attempt.code()).isEqualTo("SERVER_UNAVAILABLE");
    }

    @Test
    void tradeExecutionAuthorizesSubjectBeforeJournaledMutation() {
        UUID player = UUID.randomUUID();
        var listing = new com.storynpcs.domain.role.trader.TradeListing(
                "minecraft:diamond", 1, "minecraft:emerald", 5);

        var denied = service.executeTrade(new TradeExecutionRequest(
                "player", UUID.randomUUID(), player, NPC_ID, 0, UUID.randomUUID(), -1), listing);
        assertThat(denied.applied()).isFalse();
        assertThat(denied.recoveryOutcome()).isEqualTo("REJECTED_AUTHORIZATION");
        assertThat(denied.diagnostics().formatReport()).contains("PLAYER_SUBJECT_MISMATCH");
        assertThat(canonicalEvents()).singleElement().satisfies(event -> {
            assertThat(event.targetId()).isEqualTo(NPC_ID);
            assertThat(event.subjectId()).isEqualTo(player);
        });

        var scripted = service.executeTrade(new TradeExecutionRequest(
                "script", null, player, NPC_ID, 0, UUID.randomUUID(), -1), listing);
        assertThat(scripted.diagnostics().formatReport()).contains("SCRIPT_CAPABILITY_REQUIRED");

        // Auth passes for the self subject; the headless mutation path records the
        // listing use and publishes the trade event — proving the envelope reaches
        // the journaled core rather than failing at the gate.
        var attempted = service.executeTrade(new TradeExecutionRequest(
                "player", player, player, NPC_ID, 0, UUID.randomUUID(), -1), listing);
        assertThat(attempted.applied()).isTrue();
        assertThat(attempted.recoveryOutcome()).isEqualTo("COMMITTED");
        assertThat(listing.getUses()).isEqualTo(1);
        assertThat(eventsOf(com.storynpcs.api.event.TradeExecutedEvent.class)).hasSize(1);
    }

    @Test
    void unindexedTradeRequestIdCannotReplayTheExchange() {
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var listing = new com.storynpcs.domain.role.trader.TradeListing(
                "minecraft:diamond", 1, "minecraft:emerald", 5);
        var request = new TradeExecutionRequest(
                "player", player, player, NPC_ID, -1, requestId, -1);

        assertThat(service.executeTrade(request, listing).applied()).isTrue();
        assertThat(service.executeTrade(request, listing).applied()).isTrue();

        assertThat(listing.getUses()).isEqualTo(1);
        assertThat(eventsOf(com.storynpcs.api.event.TradeExecutedEvent.class)).hasSize(1);
    }

    @Test
    void bankCompatDelegatesRouteThroughCanonicalBoundary() {
        var repo = bankRepo();
        UUID player = UUID.randomUUID();

        assertThat(service.depositToBank(player, repo, 0, 0, "minecraft:dirt", 2)).isTrue();
        assertThat(service.depositToBankAuto(player, repo, 0, "minecraft:dirt", 3, null)).isZero();
        assertThat(service.withdrawFromBank(player, repo, 0, 0, 1)).isPresent();
        var removed = service.withdrawEntireStackFromBank(player, repo, 0, 0);
        assertThat(removed.accepted()).isTrue();
        assertThat(removed.item().getCount()).isEqualTo(4);
        assertThat(repo.getOrCreate(player).getTabItems(0)).isEmpty();
        // Compat envelopes carry no banker id — no canonical audit event is emitted.
        assertThat(canonicalEvents()).isEmpty();

        assertThat(service.depositHeldToBank(player, repo, 0, UUID.randomUUID()).code())
                .isEqualTo("SERVER_UNAVAILABLE");
        assertThat(service.unlockBankTab(player, repo,
                new com.storynpcs.domain.role.banker.BankerRole("B"), null)).isFalse();
    }

    @Test
    void bankRequestValidationBoundsInputs() {
        UUID player = UUID.randomUUID();
        assertThatThrownBy(() -> bankRequest(player,
                BankOperationRequest.Action.DEPOSIT, -1, 0, "minecraft:dirt", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bankRequest(player,
                BankOperationRequest.Action.DEPOSIT, 0, 54, "minecraft:dirt", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bankRequest(player,
                BankOperationRequest.Action.DEPOSIT, 0, 0, " ", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bankRequest(player,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BankOperationRequest(
                "intruder", player, player, NPC_ID, BankOperationRequest.Action.DEPOSIT,
                0, 0, "minecraft:dirt", 1, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeExecutionRequest(
                "player", player, player, NPC_ID, -2, UUID.randomUUID(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capabilityRegistryClassifiesEveryRegisteredOperation() {
        assertThat(CapabilityRegistry.policyOf("bank.deposit"))
                .isEqualTo(CapabilityRegistry.Policy.PLAYER_SCOPED);
        assertThat(CapabilityRegistry.policyOf("trade.execute"))
                .isEqualTo(CapabilityRegistry.Policy.PLAYER_SCOPED);
        assertThat(CapabilityRegistry.policyOf("npc.mutate"))
                .isEqualTo(CapabilityRegistry.Policy.DEFINITION);
        assertThat(CapabilityRegistry.policyOf("bogus.capability")).isNull();

        // A player-scoped capability routed through the definition boundary fails closed.
        var decision = AuthorizationPolicy.evaluate(new MutationRequest(
                "bank.deposit", "system", "bank.deposit", NPC_ID, 0, UUID.randomUUID(), -1));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("UNKNOWN_CAPABILITY");
    }

    @Test
    void bankIndexBoundHelpersMatchTheRequestContract() {
        assertThat(BankOperationRequest.isValidTab(0)).isTrue();
        assertThat(BankOperationRequest.isValidTab(BankOperationRequest.MAX_TAB)).isTrue();
        assertThat(BankOperationRequest.isValidTab(-1)).isFalse();
        assertThat(BankOperationRequest.isValidTab(BankOperationRequest.MAX_TAB + 1)).isFalse();
        assertThat(BankOperationRequest.isValidTab(Integer.MIN_VALUE)).isFalse();
        assertThat(BankOperationRequest.isValidSlot(0)).isTrue();
        assertThat(BankOperationRequest.isValidSlot(BankOperationRequest.MAX_SLOT)).isTrue();
        assertThat(BankOperationRequest.isValidSlot(-1)).isFalse();
        assertThat(BankOperationRequest.isValidSlot(BankOperationRequest.MAX_SLOT + 1)).isFalse();
        assertThat(BankOperationRequest.isValidSlot(Integer.MAX_VALUE)).isFalse();

        // The pre-validation helpers and the throwing contract must agree —
        // packet adapters check these before constructing a request.
        UUID player = UUID.randomUUID();
        assertThatThrownBy(() -> bankRequest(player, BankOperationRequest.Action.WITHDRAW_STACK,
                0, BankOperationRequest.MAX_SLOT + 1, null, 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bankRequest(player, BankOperationRequest.Action.UNLOCK_TAB,
                BankOperationRequest.MAX_TAB + 1, -1, null, 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Shared vault access (P1-4 review remediation) ----------

    private BankOperationRequest vaultRequest(UUID player, UUID vaultOwner,
            BankOperationRequest.Action action, int tab, int slot, String itemId, int amount) {
        return new BankOperationRequest("player", player, player, NPC_ID, action,
                tab, slot, itemId, amount, UUID.randomUUID(), -1, vaultOwner);
    }

    @Test
    void sharedVaultMembersTransactOnTheOwnerVault() {
        var repo = bankRepo();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();

        // Configure sharing through the canonical bank-access mutation.
        var share = service.configureBankAccess(new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                java.util.Set.of(member), 0L, UUID.randomUUID(), -1), repo);
        assertThat(share.applied()).isTrue();
        assertThat(repo.getOrCreate(owner).getSharedMemberUuids()).contains(member);

        // The member deposits into the OWNER's vault — item lands owner-side.
        var deposit = service.depositToBank(vaultRequest(member, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 4), repo, null);
        assertThat(deposit.outcome()).isEqualTo(BankDepositOperationResult.Outcome.APPLIED);
        assertThat(repo.getOrCreate(owner).getTabItems(0)).hasSize(1);
        assertThat(repo.getOrCreate(member).getTabItems(0)).isEmpty();

        // The member withdraws from the owner's vault, not their own.
        var withdrawn = service.withdrawFromBank(vaultRequest(member, owner,
                BankOperationRequest.Action.WITHDRAW, 0, 0, null, 2), repo);
        assertThat(withdrawn.accepted()).isTrue();
        assertThat(withdrawn.item().getCount()).isEqualTo(2);
        assertThat(repo.getOrCreate(owner).getTabItems(0)).hasSize(1);

        // A non-member holding the same owner scope is denied before mutation.
        var denied = service.depositToBank(vaultRequest(outsider, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 1, "minecraft:dirt", 1), repo, null);
        assertThat(denied.code()).isEqualTo("VAULT_ACCESS_DENIED");
        assertThat(repo.getOrCreate(owner).getTabItems(0)).hasSize(1);

        // The owner always retains access to their own vault.
        var ownerOp = service.depositToBank(vaultRequest(owner, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 5, "minecraft:stone", 2), repo, null);
        assertThat(ownerOp.outcome()).isEqualTo(BankDepositOperationResult.Outcome.APPLIED);
    }

    @Test
    void privateVaultAndRevokedMembersDenyAccessDeterministically() {
        var repo = bankRepo();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();

        // Default PRIVATE vault: cross-scope requests fail closed.
        var denied = service.depositToBank(vaultRequest(member, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1), repo, null);
        assertThat(denied.code()).isEqualTo("VAULT_ACCESS_DENIED");

        // Share, verify, then revoke — access closes on the next request.
        assertThat(service.configureBankAccess(new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                java.util.Set.of(member), 0L, UUID.randomUUID(), -1), repo).applied()).isTrue();
        assertThat(service.depositToBank(vaultRequest(member, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1),
                repo, null).outcome()).isEqualTo(BankDepositOperationResult.Outcome.APPLIED);

        long vaultRevision = repo.getOrCreate(owner).getRevision();
        assertThat(service.configureBankAccess(new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                java.util.Set.of(), vaultRevision, UUID.randomUUID(), -1), repo).applied()).isTrue();
        var revoked = service.depositToBank(vaultRequest(member, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 1, "minecraft:dirt", 1), repo, null);
        assertThat(revoked.code()).isEqualTo("VAULT_ACCESS_DENIED");

        // A stale expected-vault-revision configure rejects without mutating.
        var stale = service.configureBankAccess(new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                java.util.Set.of(member), 0L, UUID.randomUUID(), -1), repo);
        assertThat(stale.applied()).isFalse();
        assertThat(stale.diagnostics().formatReport()).contains("STALE_VAULT");
        assertThat(repo.getOrCreate(owner).getSharedMemberUuids()).isEmpty();
    }

    @Test
    void bankAccessRequestContractAndOwnerResolutionValidate() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();

        // resolvedVaultOwner: null owner resolves to the acting subject.
        var selfScoped = vaultRequest(member, null,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1);
        assertThat(selfScoped.resolvedVaultOwner()).isEqualTo(member);
        assertThat(vaultRequest(member, owner,
                BankOperationRequest.Action.DEPOSIT, 0, 0, "minecraft:dirt", 1)
                .resolvedVaultOwner()).isEqualTo(owner);

        // PRIVATE + members is an ambiguous configuration — rejected at the request.
        assertThatThrownBy(() -> new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.PRIVATE,
                java.util.Set.of(member), 0L, UUID.randomUUID(), -1))
                .isInstanceOf(IllegalArgumentException.class);

        // Member list is hard-bounded and null-free.
        var oversized = new java.util.LinkedHashSet<UUID>();
        for (int i = 0; i <= BankAccessMutationRequest.MAX_MEMBERS; i++) oversized.add(UUID.randomUUID());
        assertThatThrownBy(() -> new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                oversized, 0L, UUID.randomUUID(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        var withNull = new java.util.HashSet<UUID>();
        withNull.add(null);
        assertThatThrownBy(() -> new BankAccessMutationRequest(
                "system", null, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                withNull, 0L, UUID.randomUUID(), -1))
                .isInstanceOf(IllegalArgumentException.class);

        // Authorization: a member cannot configure the owner's sharing themselves.
        var repo = bankRepo();
        var denied = service.configureBankAccess(new BankAccessMutationRequest(
                "player", member, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                java.util.Set.of(member), 0L, UUID.randomUUID(), -1), repo);
        assertThat(denied.applied()).isFalse();
        assertThat(denied.diagnostics().formatReport()).contains("PLAYER_SUBJECT_MISMATCH");

        // The owner can configure their own vault through a player-scoped request.
        var ownerSelf = service.configureBankAccess(new BankAccessMutationRequest(
                "player", owner, owner,
                com.storynpcs.domain.role.banker.BankVault.AccessPolicy.SHARED,
                java.util.Set.of(member), 0L, UUID.randomUUID(), -1), repo);
        assertThat(ownerSelf.applied()).isTrue();
    }

    // ---------- Two-input trades (review remediation) ----------

    @Test
    void malformedTwoInputListingFailsClosedAtTheServiceBoundary() {
        UUID player = UUID.randomUUID();
        var incoherent = new com.storynpcs.domain.role.trader.TradeListing(
                "minecraft:bread", 1, "minecraft:emerald", 1);
        // A secondary item id with no count is the malformed shape the review
        // flagged: validate() must reject it before any exchange records.
        incoherent.setSecondaryPriceItemId("minecraft:dirt");
        assertThat(incoherent.hasTwoInputs()).isFalse();
        assertThatThrownBy(incoherent::validate).isInstanceOf(IllegalStateException.class);

        var result = service.executeTrade(new TradeExecutionRequest(
                "player", player, player, NPC_ID, 0, UUID.randomUUID(), -1), incoherent);
        assertThat(result.applied()).isFalse();
        assertThat(incoherent.getUses()).isZero();
        assertThat(eventsOf(com.storynpcs.api.event.TradeExecutedEvent.class)).isEmpty();
    }

    @Test
    void coherentTwoInputListingCommitsThroughTheCanonicalPath() {
        UUID player = UUID.randomUUID();
        var twoInput = new com.storynpcs.domain.role.trader.TradeListing(
                "minecraft:bread", 2, "minecraft:emerald", 1);
        twoInput.setSecondaryPriceItemId("minecraft:dirt");
        twoInput.setSecondaryPriceCount(3);
        twoInput.validate(); // coherent — throws nothing
        assertThat(twoInput.hasTwoInputs()).isTrue();

        var result = service.executeTrade(new TradeExecutionRequest(
                "player", player, player, NPC_ID, 0, UUID.randomUUID(), -1), twoInput);
        assertThat(result.applied()).isTrue();
        assertThat(twoInput.getUses()).isEqualTo(1);
    }

    @Test
    void identicalTwoInputListingIsRejectedBeforeAnyExchange() {
        UUID player = UUID.randomUUID();
        // The under-charge shape the review flagged: both inputs demand the
        // same item, so independent held-checks double-count the inventory.
        var duplicated = new com.storynpcs.domain.role.trader.TradeListing(
                "minecraft:bread", 1, "minecraft:emerald", 40);
        duplicated.setSecondaryPriceItemId("minecraft:emerald");
        duplicated.setSecondaryPriceCount(40);
        assertThat(duplicated.hasTwoInputs()).isTrue();
        assertThatThrownBy(duplicated::validate)
                .isInstanceOf(IllegalStateException.class);

        var result = service.executeTrade(new TradeExecutionRequest(
                "player", player, player, NPC_ID, 0, UUID.randomUUID(), -1), duplicated);
        assertThat(result.applied()).isFalse();
        assertThat(duplicated.getUses()).isZero();
        assertThat(eventsOf(com.storynpcs.api.event.TradeExecutedEvent.class)).isEmpty();
    }

    // ---------- Runtime tunables through the canonical boundary ----------

    @Test
    void runtimeTunablesMutateThroughCanonicalAuthorizationAndRevision() {
        var tunables = new com.storynpcs.admin.RuntimeTunables();
        service.setRuntimeTunables(tunables);

        var applied = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "system", "config.mutate", NPC_ID, 0L,
                UUID.randomUUID(), -1), java.util.Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS, "1200"));
        assertThat(applied.applied()).isTrue();
        assertThat(applied.revision()).isEqualTo(1L);
        assertThat(tunables.longValue(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS)).isEqualTo(1200L);

        // Stale expected revision rejects without a partial apply.
        var stale = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "system", "config.mutate", NPC_ID, 0L,
                UUID.randomUUID(), -1), java.util.Map.of(
                com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS, "900000"));
        assertThat(stale.applied()).isFalse();
        assertThat(stale.diagnostics().formatReport()).contains("STALE_REVISION");
        assertThat(tunables.longValue(
                com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS))
                .isEqualTo(4_000_000L);

        // Failed validation rolls back — the live map is untouched.
        var invalid = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "system", "config.mutate", NPC_ID, 1L,
                UUID.randomUUID(), -1), java.util.Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS, "not-a-number"));
        assertThat(invalid.applied()).isFalse();
        assertThat(invalid.diagnostics().formatReport()).contains("VALIDATION_FAILED");

        // Unproven low-permission player actors are denied at the gate.
        var denied = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "player:" + UUID.randomUUID(), "config.mutate", NPC_ID, 1L,
                UUID.randomUUID(), -1), java.util.Map.of());
        assertThat(denied.applied()).isFalse();
        assertThat(denied.diagnostics().formatReport()).contains("PERMISSION_DENIED");
    }

    @Test
    void remoteTunableMutationRequiresAValidBoundProof() {
        var tunables = new com.storynpcs.admin.RuntimeTunables();
        service.setRuntimeTunables(tunables);
        MutationRequest request = new MutationRequest(
                "config.mutate", "api", "config.mutate", NPC_ID, 0L,
                UUID.randomUUID(), -1);
        var changes = java.util.Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_MAX_PENDING, "512");

        // No proof, an expired proof, and a proof without the capability all
        // fail closed before the transaction stage.
        assertThat(service.mutateRuntimeTunables(request, changes, null, 50L)
                .diagnostics().formatReport()).contains("REMOTE_PROOF_INVALID");
        var expired = new com.storynpcs.admin.RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("config.mutate"), 0, 10);
        assertThat(service.mutateRuntimeTunables(request, changes, expired, 11L)
                .diagnostics().formatReport()).contains("REMOTE_PROOF_INVALID");
        var underScoped = new com.storynpcs.admin.RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("npc.edit"), 0, 100);
        assertThat(service.mutateRuntimeTunables(request, changes, underScoped, 50L)
                .diagnostics().formatReport()).contains("REMOTE_PROOF_INVALID");
        assertThat(tunables.revision()).isZero();

        // A live proof carrying the capability commits normally.
        var valid = new com.storynpcs.admin.RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("config.mutate"), 0, 100);
        var applied = service.mutateRuntimeTunables(request, changes, valid, 50L);
        assertThat(applied.applied()).isTrue();
        assertThat(tunables.longValue(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_MAX_PENDING)).isEqualTo(512L);

        // The same proof presented before its issue tick is invalid.
        var notYetIssued = new com.storynpcs.admin.RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("config.mutate"), 60, 100);
        var denied = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "api", "config.mutate", NPC_ID, 1L,
                UUID.randomUUID(), -1), java.util.Map.of(), notYetIssued, 50L);
        assertThat(denied.applied()).isFalse();
        assertThat(denied.diagnostics().formatReport()).contains("REMOTE_PROOF_INVALID");
    }
}
