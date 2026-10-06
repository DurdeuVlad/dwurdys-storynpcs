package com.storynpcs.creator.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Player/session-scoped HUD overlays (P8-6). Overlays never outlive their
 * session — logout expires them deterministically; a session may hold at most
 * a bounded number.
 */
public final class OverlaySession {

    public static final int MAX_OVERLAYS_PER_SESSION = 8;

    public record Overlay(UUID overlayId, String elementId, int expiryTick) {}

    private final Map<UUID, UUID> sessionPlayer = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, Overlay>> overlays = new LinkedHashMap<>();

    public void openSession(UUID sessionId, UUID playerUuid) {
        sessionPlayer.put(sessionId, playerUuid);
        overlays.putIfAbsent(sessionId, new LinkedHashMap<>());
    }

    /** Returns the overlay, or null when the session is full/unknown. */
    public Overlay show(UUID sessionId, String elementId, int durationTicks, int nowTick) {
        Map<UUID, Overlay> session = overlays.get(sessionId);
        if (session == null || session.size() >= MAX_OVERLAYS_PER_SESSION || durationTicks <= 0) {
            return null;
        }
        Overlay overlay = new Overlay(UUID.randomUUID(), elementId, nowTick + durationTicks);
        session.put(overlay.overlayId(), overlay);
        return overlay;
    }

    /** Active overlays for a session — expired entries are pruned on read. */
    public java.util.List<Overlay> active(UUID sessionId, int nowTick) {
        Map<UUID, Overlay> session = overlays.get(sessionId);
        if (session == null) {
            return java.util.List.of();
        }
        session.values().removeIf(o -> o.expiryTick() <= nowTick);
        return java.util.List.copyOf(session.values());
    }

    /** Session close (logout) drops every overlay — no leakage across sessions. */
    public int closeSession(UUID sessionId) {
        Map<UUID, Overlay> session = overlays.remove(sessionId);
        sessionPlayer.remove(sessionId);
        return session == null ? 0 : session.size();
    }

    /** Server-stop cleanup — drops every session and every overlay. */
    public void clearAll() {
        overlays.clear();
        sessionPlayer.clear();
    }

    /** Every tracked session id — for lifecycle iteration and diagnostics. */
    public java.util.Set<UUID> sessionIds() {
        return java.util.Set.copyOf(overlays.keySet());
    }

    public Optional<UUID> playerOf(UUID sessionId) {
        return Optional.ofNullable(sessionPlayer.get(sessionId));
    }
}
