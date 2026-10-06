package com.storynpcs.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-owned capability sessions for the extension API (P9-1).
 *
 * A session is an opaque grant: a named principal holds a bounded set of
 * DEFINITION-policy capabilities until expiry or revocation. The registry is a
 * per-instance service object — never a static — so a restarted server cannot
 * validate another server's grants. Only DEFINITION-policy capabilities may be
 * granted: an API session can never impersonate a player subject for
 * PLAYER_SCOPED operations (vaults, trades, progression) — that boundary is
 * structural, not a flag.
 */
public final class ApiSessionRegistry {

    public static final int MAX_SESSIONS = 128;
    public static final long DEFAULT_TTL_TICKS = 72_000L; // one Minecraft day

    /** One issued grant — immutable record, opaque id. */
    public record Session(UUID sessionId, String principal,
                          Set<String> capabilities,
                          long issuedTick, long expiryTick, boolean revoked) {
        public Session {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public boolean live(long nowTick) {
            return !revoked && nowTick < expiryTick;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Issue a session for {@code principal} holding {@code capabilities}.
     * Returns empty when the registry is full or the capability set is empty —
     * a session that grants nothing is never issued.
     */
    public Optional<Session> issue(String principal, Set<String> capabilities, long nowTick) {
        return issue(principal, capabilities, DEFAULT_TTL_TICKS, nowTick);
    }

    public Optional<Session> issue(String principal, Set<String> capabilities,
                                   long ttlTicks, long nowTick) {
        if (principal == null || principal.isBlank()
                || capabilities == null || capabilities.isEmpty()
                || ttlTicks <= 0 || sessions.size() >= MAX_SESSIONS) {
            return Optional.empty();
        }
        var session = new Session(UUID.randomUUID(), principal.trim(),
                capabilities, nowTick, nowTick + ttlTicks, false);
        sessions.put(session.sessionId(), session);
        return Optional.of(session);
    }

    /** True when the session is live and grants {@code capability}. */
    public boolean grants(UUID sessionId, String capability, long nowTick) {
        var session = sessions.get(sessionId);
        return session != null && session.live(nowTick)
                && session.capabilities().contains(capability);
    }

    public Optional<Session> session(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Revoke a grant — subsequent checks deny immediately. */
    public boolean revoke(UUID sessionId) {
        var session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        sessions.put(sessionId, new Session(session.sessionId(), session.principal(),
                session.capabilities(), session.issuedTick(), session.expiryTick(), true));
        return true;
    }

    /** Drop expired sessions — bounded sweep, called on the tick path. */
    public int sweepExpired(long nowTick) {
        int removed = 0;
        for (var e : sessions.entrySet()) {
            if (!e.getValue().live(nowTick)) {
                sessions.remove(e.getKey(), e.getValue());
                removed++;
            }
        }
        return removed;
    }

    public void clear() {
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }
}
