package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.domain.quest.TeamProgression;
import com.storynpcs.domain.quest.TeamProgressionStore;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** P5-5: shared-party teams — membership, ownership, claim, propagation, durability. */
class TeamProgressionMutationTest {
    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository repository;
    private EventPublisher events;
    private StoryNpcsApplicationService service;
    private TeamProgressionStore teamStore;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DefinitionRegistry();
        repository = new ProgressionRepository(tempDir);
        events = new EventPublisher();
        service = new StoryNpcsApplicationService(registry, repository, events);
        teamStore = new TeamProgressionStore(tempDir.resolve("teams"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        teamStore.open();
        service.setTeamProgressionStore(teamStore);

        Quest quest = new Quest(NamespacedId.of("storynpcs:collect_wood"), "Collect wood");
        quest.setObjectives(List.of(new QuestObjective(
                "logs", QuestObjective.Type.COLLECT_ITEM, "minecraft:oak_log", 2)));
        registry.registerQuest(quest);
    }

    private static PlayerProgressionActionRequest req(
            String op, String actorType, UUID actorId, UUID subject, int perm) {
        return new PlayerProgressionActionRequest(op, actorType, actorId, subject,
                UUID.randomUUID(), perm);
    }

    @Test
    void createInviteJoinLifecyclePersistsAcrossStoreReopen() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();

        assertThat(service.teamCreate(req("team.create", "player", owner, owner, -1))
                .applied()).isTrue();
        UUID teamId = repository.getOrCreate(owner).getTeamId();
        assertThat(teamId).isNotNull();

        // Owner invites; invitee joins by owner reference.
        assertThat(service.teamInvite(req("team.invite", "player", owner, owner, -1), member)
                .applied()).isTrue();
        assertThat(service.teamJoin(req("team.join", "player", member, member, -1), owner)
                .applied()).isTrue();

        TeamProgression team = teamStore.get(teamId).orElseThrow();
        assertThat(team.getMemberUuids()).containsExactlyInAnyOrder(owner, member);
        assertThat(team.getOwnerUuid()).isEqualTo(owner);
        assertThat(team.isInvited(member)).isFalse(); // invite consumed
        assertThat(repository.getOrCreate(member).getTeamId()).isEqualTo(teamId);

        // Reconnect survival: a fresh store instance reads the same record.
        TeamProgressionStore reopened = new TeamProgressionStore(tempDir.resolve("teams"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        reopened.open();
        assertThat(reopened.findTeamFor(member)).isPresent();
        assertThat(reopened.get(teamId).orElseThrow().getMemberUuids())
                .containsExactlyInAnyOrder(owner, member);
    }

    @Test
    void joinWithoutInvitationIsRejected() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));

        var denied = service.teamJoin(req("team.join", "player", stranger, stranger, -1), owner);
        assertThat(denied.applied()).isFalse();
        assertThat(denied.decision().code()).isEqualTo("NOT_INVITED");
        assertThat(repository.getOrCreate(stranger).getTeamId()).isNull();
    }

    @Test
    void adminCommandActorBypassesInviteGate() {
        UUID owner = UUID.randomUUID();
        UUID recruit = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));

        var joined = service.teamJoin(req("team.join", "command", null, recruit, 2), owner);
        assertThat(joined.applied()).isTrue();
        assertThat(repository.getOrCreate(recruit).getTeamId()).isNotNull();
    }

    @Test
    void oneTeamPerPlayerAndOwnerTransferRules() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));
        service.teamInvite(req("team.invite", "player", owner, owner, -1), member);
        service.teamJoin(req("team.join", "player", member, member, -1), owner);

        // Member cannot create or join elsewhere while teamed.
        assertThat(service.teamCreate(req("team.create", "player", member, member, -1))
                .applied()).isFalse();

        // Non-owner cannot transfer ownership.
        var notOwner = service.teamTransferOwner(
                req("team.owner", "player", member, member, -1), member);
        assertThat(notOwner.applied()).isFalse();
        assertThat(notOwner.decision().code()).isEqualTo("NOT_OWNER");

        // Owner cannot transfer to a non-member.
        var notMember = service.teamTransferOwner(
                req("team.owner", "player", owner, owner, -1), outsider);
        assertThat(notMember.decision().code()).isEqualTo("NOT_A_MEMBER");

        // Owner transfers to member.
        assertThat(service.teamTransferOwner(
                req("team.owner", "player", owner, owner, -1), member).applied()).isTrue();
        UUID teamId = repository.getOrCreate(owner).getTeamId();
        assertThat(teamId).isNotNull();
        try {
            assertThat(teamStore.get(teamId).orElseThrow().getOwnerUuid()).isEqualTo(member);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void danglingPlayerRefSelfHealsOnNextOp() throws IOException {
        // Simulate a mid-op crash: the player's ref was persisted but the
        // team record write never landed (dangling), or landed without them.
        UUID player = UUID.randomUUID();
        var prog = repository.getOrCreate(player);
        prog.setTeamId(UUID.randomUUID());

        // create must heal the dangling ref, not refuse as already-teamed.
        assertThat(service.teamCreate(req("team.create", "player", player, player, -1))
                .applied()).isTrue();
        UUID teamId = repository.getOrCreate(player).getTeamId();
        assertThat(teamStore.get(teamId)).isPresent();

        // A ref pointing at a real team that lacks the player also heals.
        UUID otherOwner = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", otherOwner, otherOwner, -1));
        service.teamInvite(req("team.invite", "player", otherOwner, otherOwner, -1), player);
        prog.setTeamId(UUID.randomUUID()); // stale ref again
        assertThat(service.teamJoin(req("team.join", "player", player, player, -1), otherOwner)
                .applied()).isTrue();
    }

    @Test
    void staleRefDoesNotPropagateIntoForeignTeam() throws IOException {
        UUID outsider = UUID.randomUUID();
        UUID realOwner = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", realOwner, realOwner, -1));
        UUID foreignTeam = repository.getOrCreate(realOwner).getTeamId();

        // Outsider's record points at a team they're not a member of.
        repository.getOrCreate(outsider).setTeamId(foreignTeam);
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");
        service.mutateQuestProgression(QuestProgressionMutationRequest.start(
                "system", null, outsider, questId, 0, UUID.randomUUID()));

        // The foreign record must not absorb a non-member's state.
        assertThat(teamStore.get(foreignTeam).orElseThrow().getQuests()).isEmpty();
    }

    @Test
    void lastMemberLeavingDisbandsTeam() {
        UUID owner = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));
        UUID teamId = repository.getOrCreate(owner).getTeamId();

        assertThat(service.teamLeave(req("team.leave", "player", owner, owner, -1))
                .applied()).isTrue();
        assertThat(repository.getOrCreate(owner).getTeamId()).isNull();
        try {
            assertThat(teamStore.get(teamId)).isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void ownerLeaveTransfersToRemainingMember() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));
        service.teamInvite(req("team.invite", "player", owner, owner, -1), member);
        service.teamJoin(req("team.join", "player", member, member, -1), owner);
        UUID teamId = repository.getOrCreate(owner).getTeamId();

        assertThat(service.teamLeave(req("team.leave", "player", owner, owner, -1))
                .applied()).isTrue();
        var team = teamStore.get(teamId).orElseThrow();
        assertThat(team.getMemberUuids()).containsExactly(member);
        assertThat(team.getOwnerUuid()).isEqualTo(member);
    }

    @Test
    void questProgressMirrorsIntoSharedTeamRecord() throws IOException {
        UUID owner = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));
        UUID teamId = repository.getOrCreate(owner).getTeamId();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");

        service.mutateQuestProgression(QuestProgressionMutationRequest.start(
                "system", null, owner, questId, 0, UUID.randomUUID()));
        service.mutateQuestProgression(QuestProgressionMutationRequest.progress(
                "system", null, owner, questId, "logs", 1,
                repository.getOrCreate(owner).getQuestRevision(), UUID.randomUUID()));

        var team = teamStore.get(teamId).orElseThrow();
        var shared = team.getQuests().get(questId.toString());
        assertThat(shared).isNotNull();
        assertThat(shared.getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(shared.getCount("logs")).isEqualTo(1);
    }

    @Test
    void completionClaimsSharedSlotForFirstMemberOnly() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        service.teamCreate(req("team.create", "player", owner, owner, -1));
        service.teamInvite(req("team.invite", "player", owner, owner, -1), member);
        service.teamJoin(req("team.join", "player", member, member, -1), owner);
        UUID teamId = repository.getOrCreate(owner).getTeamId();
        NamespacedId questId = NamespacedId.of("storynpcs:collect_wood");

        // Member completes — claims the shared slot.
        var completion = service.completeQuest(new QuestCompletionMutationRequest(
                "player", member, member, questId,
                repository.getOrCreate(member).getQuestRevision(), UUID.randomUUID(), -1));
        assertThat(completion.outcome())
                .isEqualTo(QuestCompletionResult.Outcome.COMPLETED);

        var team = teamStore.get(teamId).orElseThrow();
        assertThat(team.claimer(questId)).contains(member);
        assertThat(team.getQuests().get(questId.toString()).getStatus())
                .isEqualTo(QuestProgressState.Status.COMPLETED);

        // Owner completing the same quest cannot steal the recorded claim.
        service.completeQuest(new QuestCompletionMutationRequest(
                "player", owner, owner, questId,
                repository.getOrCreate(owner).getQuestRevision(), UUID.randomUUID(), -1));
        assertThat(teamStore.get(teamId).orElseThrow().claimer(questId)).contains(member);
    }

    @Test
    void commandRewardRunsOnceUnderDurableMark() {
        NamespacedId questId = NamespacedId.of("storynpcs:cmd_quest");
        Quest cmd = new Quest(questId, "Command quest");
        cmd.setRewards(List.of(new QuestReward(
                QuestReward.Type.COMMAND, "say hello %player%", 0)));
        registry.registerQuest(cmd);

        UUID player = UUID.randomUUID();
        AtomicInteger delivered = new AtomicInteger();
        service.setRewardSideEffectOverride((p, reward) -> {
            if (reward.getType() == QuestReward.Type.COMMAND) delivered.incrementAndGet();
        });

        var first = service.completeQuest(new QuestCompletionMutationRequest(
                "player", player, player, questId,
                repository.getOrCreate(player).getQuestRevision(), UUID.randomUUID(), -1));
        assertThat(first.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(delivered.get()).isEqualTo(1);

        // Re-completion is idempotent — the command never re-executes.
        var second = service.completeQuest(new QuestCompletionMutationRequest(
                "player", player, player, questId,
                repository.getOrCreate(player).getQuestRevision(), UUID.randomUUID(), -1));
        assertThat(second.outcome()).isEqualTo(QuestCompletionResult.Outcome.ALREADY_COMPLETED);
        assertThat(delivered.get()).isEqualTo(1);
    }

    @Test
    void failedCommandRewardIsMarkedAttemptedNotRetried() {
        NamespacedId questId = NamespacedId.of("storynpcs:cmd_fail");
        Quest cmd = new Quest(questId, "Failing command quest");
        cmd.setRewards(List.of(new QuestReward(
                QuestReward.Type.COMMAND, "say boom", 0)));
        registry.registerQuest(cmd);

        UUID player = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        service.setRewardSideEffectOverride((p, reward) -> {
            if (reward.getType() == QuestReward.Type.COMMAND) {
                attempts.incrementAndGet();
                throw new IllegalStateException("command exploded");
            }
        });

        var failed = service.completeQuest(new QuestCompletionMutationRequest(
                "player", player, player, questId,
                repository.getOrCreate(player).getQuestRevision(), UUID.randomUUID(), -1));
        assertThat(failed.outcome()).isEqualTo(QuestCompletionResult.Outcome.FAILED);
        assertThat(attempts.get()).isEqualTo(1);

        // Retry completes the remaining legs but the durable mark means the
        // failed command is reported once, never re-executed.
        var retry = service.completeQuest(new QuestCompletionMutationRequest(
                "player", player, player, questId,
                repository.getOrCreate(player).getQuestRevision(), UUID.randomUUID(), -1));
        assertThat(retry.outcome()).isEqualTo(QuestCompletionResult.Outcome.COMPLETED);
        assertThat(attempts.get()).isEqualTo(1);
    }
}
