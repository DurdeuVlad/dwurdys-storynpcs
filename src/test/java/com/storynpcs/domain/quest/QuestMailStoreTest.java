package com.storynpcs.domain.quest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcItemStack;
import com.storynpcs.domain.progression.QuestProgressState;

class QuestMailStoreTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static QuestMail mail(String seed, UUID player, long tick) {
        return new QuestMail(
                UUID.nameUUIDFromBytes(seed.getBytes()),
                player,
                NamespacedId.of("storynpcs", "quest-a"),
                List.of(NpcItemStack.of(NamespacedId.of("minecraft", "diamond"), 3, null)),
                40, "INVENTORY_FULL", tick, 0, 0);
    }

    @Test
    void mailSurvivesReopenAndClaimsExactlyOnce(@TempDir Path dir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        QuestMailStore store = new QuestMailStore(dir, mapper);
        store.open();
        store.enqueue(mail("m1", PLAYER, 10));
        store.enqueue(mail("m2", PLAYER, 20));

        // Reopen — pending mail is durable across restarts.
        QuestMailStore reopened = new QuestMailStore(dir, mapper);
        reopened.open();
        List<QuestMail> pending = reopened.pendingFor(PLAYER);
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).createdTick()).isLessThanOrEqualTo(pending.get(1).createdTick());
        assertThat(pending.get(0).items().get(0).count()).isEqualTo(3);

        UUID firstId = pending.get(0).mailId();
        assertThat(reopened.claim(firstId, 100)).isPresent();
        // Second claim delivers nothing — no duplication.
        assertThat(reopened.claim(firstId, 101)).isEmpty();
        assertThat(reopened.pendingFor(PLAYER)).hasSize(1);
        // Claim survives reopen too (claimed mark is durable).
        QuestMailStore again = new QuestMailStore(dir, mapper);
        again.open();
        assertThat(again.claim(firstId, 102)).isEmpty();
    }

    @Test
    void concurrentClaimsOnTheSameMailSucceedExactlyOnce(@TempDir Path dir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QuestMailStore store = new QuestMailStore(dir, mapper);
        store.open();
        QuestMail m = mail("race", PLAYER, 10);
        store.enqueue(m);

        int threads = 16;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var pool = Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    gate.await();
                    if (store.claim(m.mailId(), 100).isPresent()) {
                        successes.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }
        gate.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(failure.get()).isNull();
        // Exactly one claimant wins — the atomic read-check-write under the
        // store monitor cannot admit two successful claims on the same mail.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(store.pendingFor(PLAYER)).isEmpty();

        QuestMailStore reopened = new QuestMailStore(dir, mapper);
        reopened.open();
        assertThat(reopened.claim(m.mailId(), 200)).isEmpty();
    }

    @Test
    void pendingIsPerPlayerAndSortedByTick(@TempDir Path dir) throws IOException {
        QuestMailStore store = new QuestMailStore(dir, new ObjectMapper());
        store.open();
        UUID other = UUID.randomUUID();
        store.enqueue(mail("late", PLAYER, 50));
        store.enqueue(mail("early", PLAYER, 5));
        store.enqueue(mail("other", other, 1));
        List<QuestMail> pending = store.pendingFor(PLAYER);
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).createdTick()).isEqualTo(5);
        assertThat(store.pendingFor(other)).hasSize(1);
    }

    @Test
    void teamProgressionSurvivesMemberChangesAndRevisions() {
        UUID owner = UUID.randomUUID();
        TeamProgression team = new TeamProgression(UUID.randomUUID(), owner);
        assertThat(team.isMember(owner)).isTrue();
        long r0 = team.getRevision();

        UUID member = UUID.randomUUID();
        team.addMember(member);
        assertThat(team.getRevision()).isGreaterThan(r0);
        team.addMember(member); // no-op, no revision bump
        long r1 = team.getRevision();
        team.putQuestState(NamespacedId.of("storynpcs", "q1"), new QuestProgressState());
        assertThat(team.getRevision()).isGreaterThan(r1);
        assertThat(team.getQuests()).containsKey("storynpcs:q1");
        team.removeMember(member);
        assertThat(team.isMember(member)).isFalse();
        // Owner identity survives — reconnect-safe.
        assertThat(team.getOwnerUuid()).isEqualTo(owner);
    }

    @Test
    void overflowPolicyDefaultsToMail() {
        assertThat(RewardOverflowPolicy.valueOf("MAIL")).isEqualTo(RewardOverflowPolicy.MAIL);
        assertThat(RewardOverflowPolicy.values()).containsExactly(
                RewardOverflowPolicy.MAIL, RewardOverflowPolicy.FAIL, RewardOverflowPolicy.DROP_IN_WORLD);
    }
}
