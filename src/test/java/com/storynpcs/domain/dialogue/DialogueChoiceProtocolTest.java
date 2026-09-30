package com.storynpcs.domain.dialogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueChoiceProtocol.AcceptOutcome;
import com.storynpcs.domain.dialogue.DialogueChoiceProtocol.ChoiceToken;
import com.storynpcs.domain.dialogue.DialogueChoiceProtocol.RejectReason;

class DialogueChoiceProtocolTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final NamespacedId DIALOGUE = NamespacedId.of("storynpcs", "intro");

    private final DialogueChoiceProtocol protocol = new DialogueChoiceProtocol();

    private ChoiceToken issue() {
        return protocol.issue(SESSION, PLAYER, DIALOGUE, "node-a", "choice-1", 7L, 0L);
    }

    @Test
    void acceptedTokenCarriesBoundChoiceAndConsumesExactlyOnce() {
        ChoiceToken token = issue();
        var outcome = protocol.accept(token, SESSION, PLAYER, 7L, true, 0L);
        assertThat(outcome).isInstanceOf(AcceptOutcome.Accepted.class);
        var accepted = (AcceptOutcome.Accepted) outcome;
        assertThat(accepted.nodeId()).isEqualTo("node-a");
        assertThat(accepted.choiceKey()).isEqualTo("choice-1");
        assertThat(accepted.graphRevision()).isEqualTo(7L);

        // Replay — the winner is deterministic; duplicates never re-accept.
        assertThat(protocol.accept(token, SESSION, PLAYER, 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.ALREADY_CONSUMED));
    }

    @Test
    void indexOnlyAndUnknownTokensRejected() {
        assertThat(protocol.accept(null, SESSION, PLAYER, 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN));
        assertThat(protocol.accept(new ChoiceToken(UUID.randomUUID()), SESSION, PLAYER, 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN));
    }

    @Test
    void bindingChecksRejectMismatches() {
        ChoiceToken token = issue();
        assertThat(protocol.accept(token, UUID.randomUUID(), PLAYER, 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.WRONG_SESSION));
        assertThat(protocol.accept(token, SESSION, UUID.randomUUID(), 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.WRONG_PLAYER));
        assertThat(protocol.accept(token, SESSION, PLAYER, 7L, false, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.SESSION_CLOSED));
        assertThat(protocol.accept(token, SESSION, PLAYER, 99L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.STALE_REVISION));
    }

    @Test
    void expiryRejectsLateAccepts() {
        ChoiceToken token = issue();
        assertThat(protocol.accept(token, SESSION, PLAYER, 7L, true,
                DialogueChoiceProtocol.DEFAULT_EXPIRY_TICKS + 1))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.EXPIRED));
    }

    @Test
    void sessionRevocationDropsPendingTokens() {
        ChoiceToken t1 = issue();
        protocol.issue(SESSION, PLAYER, DIALOGUE, "node-a", "choice-2", 7L, 0L);
        ChoiceToken other = protocol.issue(UUID.randomUUID(), PLAYER, DIALOGUE, "n", "c", 7L, 0L);
        assertThat(protocol.revokeSession(SESSION)).isEqualTo(2);
        assertThat(protocol.accept(t1, SESSION, PLAYER, 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN));
        assertThat(protocol.pendingChoice(other)).isPresent();
    }

    @Test
    void pendingMapIsHardBoundedAndEvictsEldest() {
        var bounded = new DialogueChoiceProtocol(600L, 4L);
        ChoiceToken first = bounded.issue(SESSION, PLAYER, DIALOGUE, "n", "c1", 7L, 0L);
        for (int i = 0; i < 5; i++) {
            bounded.issue(SESSION, PLAYER, DIALOGUE, "n", "c" + i, 7L, 0L);
        }
        // Six issues against capacity four: the map stays bounded and the
        // eldest outstanding tokens are evicted deterministically.
        assertThat(bounded.pendingCount()).isEqualTo(4);
        assertThat(bounded.accept(first, SESSION, PLAYER, 7L, true, 0L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN));
        ChoiceToken newest = bounded.issue(SESSION, PLAYER, DIALOGUE, "n", "last", 7L, 0L);
        assertThat(bounded.pendingCount()).isEqualTo(4);
        assertThat(bounded.accept(newest, SESSION, PLAYER, 7L, true, 0L))
                .isInstanceOf(AcceptOutcome.Accepted.class);
    }

    @Test
    void expiredEntriesAreSweptOnIssue() {
        var bounded = new DialogueChoiceProtocol(600L, 8L);
        ChoiceToken stale = bounded.issue(SESSION, PLAYER, DIALOGUE, "n", "old", 7L, 0L);
        // Issue far past the stale token's expiry — issue-time sweep removes it.
        bounded.issue(SESSION, PLAYER, DIALOGUE, "n", "new", 7L, 601L);
        assertThat(bounded.pendingCount()).isEqualTo(1);
        assertThat(bounded.accept(stale, SESSION, PLAYER, 7L, true, 601L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN));
    }

    @Test
    void concurrentAcceptsResolveExactlyOnce() throws Exception {
        ChoiceToken token = issue();
        int racers = 16;
        var ready = new java.util.concurrent.CountDownLatch(racers);
        var start = new java.util.concurrent.CountDownLatch(1);
        var outcomes = new java.util.concurrent.ConcurrentLinkedQueue<AcceptOutcome>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    outcomes.add(protocol.accept(token, SESSION, PLAYER, 7L, true, 0L));
                });
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(outcomes).hasSize(racers);
        assertThat(outcomes.stream().filter(AcceptOutcome.Accepted.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream()
                .filter(o -> o.equals(new AcceptOutcome.Rejected(RejectReason.ALREADY_CONSUMED))))
                .hasSize(racers - 1);
    }

    @Test
    void runtimeTunablesDriveExpiryAndCapacity() {
        var tunables = new com.storynpcs.admin.RuntimeTunables();
        var live = new DialogueChoiceProtocol(tunables);

        // Tighten expiry to the tunable floor and cap pending at the minimum.
        var outcome = tunables.applyChanges(java.util.Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS, "20",
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_MAX_PENDING, "16"), 0L);
        assertThat(outcome.committed()).isTrue();

        ChoiceToken token = live.issue(SESSION, PLAYER, DIALOGUE, "n", "c", 7L, 0L);
        // The tunable expiry replaced the 600-tick default — 21 ticks out is expired.
        assertThat(live.accept(token, SESSION, PLAYER, 7L, true, 21L))
                .isEqualTo(new AcceptOutcome.Rejected(RejectReason.EXPIRED));

        for (int i = 0; i < 40; i++) {
            live.issue(SESSION, PLAYER, DIALOGUE, "n", "c" + i, 7L, 0L);
        }
        assertThat(live.pendingCount()).isEqualTo(16);

        // A live commit to a larger cap is picked up without reconstruction.
        var expanded = tunables.applyChanges(java.util.Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_MAX_PENDING, "64"), 1L);
        assertThat(expanded.committed()).isTrue();
        for (int i = 0; i < 60; i++) {
            live.issue(SESSION, PLAYER, DIALOGUE, "n", "d" + i, 7L, 0L);
        }
        assertThat(live.pendingCount()).isEqualTo(64);
    }

    @Test
    void constructorRejectsNonPositiveBounds() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DialogueChoiceProtocol(0L))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DialogueChoiceProtocol(-5L, 8L))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DialogueChoiceProtocol(600L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new DialogueChoiceProtocol(
                                (java.util.function.Supplier<com.storynpcs.admin.RuntimeTunables>) null))
                .isInstanceOf(NullPointerException.class);
    }
}
