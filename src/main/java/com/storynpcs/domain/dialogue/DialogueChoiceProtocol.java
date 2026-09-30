package com.storynpcs.domain.dialogue;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.storynpcs.admin.RuntimeTunables;
import com.storynpcs.admin.RuntimeTunablesView;
import com.storynpcs.domain.common.NamespacedId;

/**
 * Server-issued opaque choice tokens (P5-2). A token binds
 * actor/session/dialogue/node/choice/revision/expiry; accepting a token is a
 * single-use, atomic consume — the first accept wins deterministically and any
 * concurrent or replayed accept is rejected without re-running effects.
 *
 * <p>Index-only requests are rejected: there is no accept-by-index path at all.
 *
 * <p>All token state is synchronized — issue/accept/revoke may race on the
 * server thread and scheduler threads — and the pending map is hard-bounded:
 * expired entries are swept first, then the eldest, so a client opening many
 * dialogue sessions cannot grow memory without limit. Expiry and capacity
 * resolve live from {@link RuntimeTunables} when one is supplied.</p>
 */
public final class DialogueChoiceProtocol {

    public static final long DEFAULT_EXPIRY_TICKS = 600; // 30 s at 20 tps
    public static final long DEFAULT_MAX_PENDING = 256;

    /** Opaque server-issued token. The random UUID carries no semantic content. */
    public record ChoiceToken(UUID value) {
        public ChoiceToken {
            if (value == null) {
                throw new IllegalArgumentException("token value required");
            }
        }
    }

    private record Pending(
            UUID sessionId,
            UUID playerUuid,
            NamespacedId dialogueId,
            String nodeId,
            String choiceKey,
            long graphRevision,
            long expiresAtTick,
            boolean consumed) {}

    public enum RejectReason {
        UNKNOWN_TOKEN, WRONG_SESSION, WRONG_PLAYER, STALE_REVISION, EXPIRED, ALREADY_CONSUMED, SESSION_CLOSED
    }

    public sealed interface AcceptOutcome {
        record Accepted(String nodeId, String choiceKey, long graphRevision) implements AcceptOutcome {}
        record Rejected(RejectReason reason) implements AcceptOutcome {}
    }

    /** What happens to an open session when it times out or the player disconnects. */
    public enum TimeoutPolicy { CLOSE, RESUME }

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long expiryTicks;
    private final long maxPending;
    private final java.util.function.Supplier<? extends RuntimeTunablesView> tunables; // nullable — live tunable source

    public DialogueChoiceProtocol() {
        this(DEFAULT_EXPIRY_TICKS);
    }

    public DialogueChoiceProtocol(long expiryTicks) {
        this(expiryTicks, DEFAULT_MAX_PENDING);
    }

    public DialogueChoiceProtocol(long expiryTicks, long maxPending) {
        this(expiryTicks, maxPending, (java.util.function.Supplier<? extends RuntimeTunablesView>) null);
    }

    /** Tunable-backed protocol: expiry/capacity resolve live per issue. */
    public DialogueChoiceProtocol(RuntimeTunablesView tunables) {
        this(DEFAULT_EXPIRY_TICKS, DEFAULT_MAX_PENDING,
                tunables == null
                        ? (java.util.function.Supplier<? extends RuntimeTunablesView>) null
                        : () -> tunables);
    }

    /** Live-supplier variant: the supplier is queried per call (wiring-safe). */
    public DialogueChoiceProtocol(java.util.function.Supplier<? extends RuntimeTunablesView> tunablesSupplier) {
        this(DEFAULT_EXPIRY_TICKS, DEFAULT_MAX_PENDING,
                java.util.Objects.requireNonNull(tunablesSupplier, "tunablesSupplier"));
    }

    private DialogueChoiceProtocol(long expiryTicks, long maxPending,
                                   java.util.function.Supplier<? extends RuntimeTunablesView> tunables) {
        if (expiryTicks <= 0) {
            throw new IllegalArgumentException("expiryTicks must be positive");
        }
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.expiryTicks = expiryTicks;
        this.maxPending = maxPending;
        this.tunables = tunables;
    }

    private RuntimeTunablesView tunables() {
        return tunables != null ? tunables.get() : null;
    }

    private long expiryTicks() {
        RuntimeTunablesView t = tunables();
        return t != null
                ? t.longValue(RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS) : expiryTicks;
    }

    private long maxPending() {
        RuntimeTunablesView t = tunables();
        return t != null
                ? t.longValue(RuntimeTunables.DIALOGUE_TOKEN_MAX_PENDING) : maxPending;
    }

    /** Issue a token for a choice rendered inside a session view. */
    public ChoiceToken issue(UUID sessionId, UUID playerUuid, NamespacedId dialogueId,
                             String nodeId, String choiceKey, long graphRevision, long nowTick) {
        ChoiceToken token = new ChoiceToken(new UUID(random.nextLong(), random.nextLong()));
        synchronized (pending) {
            evictExpired(nowTick);
            long capacity = maxPending();
            while (pending.size() >= capacity) {
                // Deterministic hard bound: eldest outstanding token is evicted
                // first — it rejects as UNKNOWN_TOKEN if presented later.
                Iterator<UUID> it = pending.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
            pending.put(token.value(), new Pending(sessionId, playerUuid, dialogueId,
                    nodeId, choiceKey, graphRevision, nowTick + expiryTicks(), false));
        }
        return token;
    }

    /**
     * Accept a token. Exactly one accept may succeed per token; the outcome
     * carries the bound node/choice/revision so the caller can execute the
     * effect transaction exactly once and render the next authoritative view.
     */
    public AcceptOutcome accept(ChoiceToken token, UUID sessionId, UUID playerUuid,
                                long currentGraphRevision, boolean sessionOpen, long nowTick) {
        if (token == null) {
            return new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN);
        }
        synchronized (pending) {
            Pending p = pending.get(token.value());
            if (p == null) {
                return new AcceptOutcome.Rejected(RejectReason.UNKNOWN_TOKEN);
            }
            if (p.consumed()) {
                return new AcceptOutcome.Rejected(RejectReason.ALREADY_CONSUMED);
            }
            if (!p.sessionId().equals(sessionId)) {
                return new AcceptOutcome.Rejected(RejectReason.WRONG_SESSION);
            }
            if (!p.playerUuid().equals(playerUuid)) {
                return new AcceptOutcome.Rejected(RejectReason.WRONG_PLAYER);
            }
            if (!sessionOpen) {
                return new AcceptOutcome.Rejected(RejectReason.SESSION_CLOSED);
            }
            if (nowTick > p.expiresAtTick()) {
                return new AcceptOutcome.Rejected(RejectReason.EXPIRED);
            }
            if (currentGraphRevision != p.graphRevision()) {
                return new AcceptOutcome.Rejected(RejectReason.STALE_REVISION);
            }
            // Atomic consume — first accept wins, every later accept rejects.
            pending.put(token.value(), new Pending(p.sessionId(), p.playerUuid(), p.dialogueId(),
                    p.nodeId(), p.choiceKey(), p.graphRevision(), p.expiresAtTick(), true));
            return new AcceptOutcome.Accepted(p.nodeId(), p.choiceKey(), p.graphRevision());
        }
    }

    /** Drop all unconsumed tokens bound to a session (on close/reload). Returns count. */
    public int revokeSession(UUID sessionId) {
        synchronized (pending) {
            int removed = 0;
            var it = pending.values().iterator();
            while (it.hasNext()) {
                if (it.next().sessionId().equals(sessionId)) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }

    public Optional<String> pendingChoice(ChoiceToken token) {
        synchronized (pending) {
            Pending p = token == null ? null : pending.get(token.value());
            return p == null || p.consumed() ? Optional.empty() : Optional.of(p.choiceKey());
        }
    }

    public int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** Remove every entry whose expiry tick has passed — swept on issue. */
    private void evictExpired(long nowTick) {
        pending.values().removeIf(p -> nowTick > p.expiresAtTick());
    }
}
