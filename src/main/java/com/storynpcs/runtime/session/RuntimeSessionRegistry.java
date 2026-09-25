package com.storynpcs.runtime.session;

import com.storynpcs.domain.common.NamespacedId;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-server transient player/tool state. It is deliberately instance-owned;
 * selections and packet throttles must not leak across servers or test runs.
 */
public final class RuntimeSessionRegistry {
    private final Map<UUID, NamespacedId> selectedPathNpc = new ConcurrentHashMap<>();
    private final Map<UUID, NamespacedId> cloneTemplates = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedPassengers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDialogueChoiceMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRoleActionMillis = new ConcurrentHashMap<>();
    private final Map<UUID, RoleSession> roleSessions = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, Boolean>> acceptedRequests = new ConcurrentHashMap<>();

    public record RoleSession(String kind, NamespacedId npcId, UUID sessionId) {}

    public enum RequestAdmission {
        NEW,
        DUPLICATE,
        INVALID
    }

    public void selectPathNpc(UUID playerId, NamespacedId npcId) {
        if (playerId != null && npcId != null) {
            selectedPathNpc.put(playerId, npcId);
        }
    }

    public NamespacedId selectedPathNpc(UUID playerId) {
        return playerId == null ? null : selectedPathNpc.get(playerId);
    }

    public void selectCloneTemplate(UUID playerId, NamespacedId npcId) {
        if (playerId != null && npcId != null) {
            cloneTemplates.put(playerId, npcId);
        }
    }

    public NamespacedId cloneTemplate(UUID playerId) {
        return playerId == null ? null : cloneTemplates.get(playerId);
    }

    public void selectPassenger(UUID playerId, int entityId) {
        if (playerId != null) {
            selectedPassengers.put(playerId, entityId);
        }
    }

    public Integer selectedPassenger(UUID playerId) {
        return playerId == null ? null : selectedPassengers.get(playerId);
    }

    public void clearSelectedPassenger(UUID playerId) {
        if (playerId != null) {
            selectedPassengers.remove(playerId);
        }
    }

    public boolean throttleDialogueChoice(UUID playerId, long nowMillis, long minimumIntervalMillis) {
        return throttle(lastDialogueChoiceMillis, playerId, nowMillis, minimumIntervalMillis);
    }

    public boolean throttleRoleAction(UUID playerId, long nowMillis, long minimumIntervalMillis) {
        return throttle(lastRoleActionMillis, playerId, nowMillis, minimumIntervalMillis);
    }

    /** Returns a stable server-issued token for the currently open role screen. */
    public UUID openRoleSession(UUID playerId, String kind, NamespacedId npcId) {
        if (playerId == null || kind == null || npcId == null) return null;
        RoleSession current = roleSessions.get(playerId);
        if (current != null && current.kind().equals(kind) && current.npcId().equals(npcId)) {
            return current.sessionId();
        }
        UUID sessionId = UUID.randomUUID();
        roleSessions.put(playerId, new RoleSession(kind, npcId, sessionId));
        return sessionId;
    }

    public boolean isRoleSession(UUID playerId, String kind, NamespacedId npcId, UUID sessionId) {
        if (playerId == null || kind == null || npcId == null || sessionId == null) return false;
        RoleSession current = roleSessions.get(playerId);
        return current != null && current.sessionId().equals(sessionId)
                && current.kind().equals(kind) && current.npcId().equals(npcId);
    }

    /** Returns false for a duplicate request id within the player's bounded replay window. */
    public boolean acceptRequest(UUID playerId, UUID requestId) {
        return admitRequest(playerId, requestId) == RequestAdmission.NEW;
    }

    /** Records a request while preserving duplicate classification for durable adapters. */
    public RequestAdmission admitRequest(UUID playerId, UUID requestId) {
        if (playerId == null || requestId == null) return RequestAdmission.INVALID;
        LinkedHashMap<UUID, Boolean> requests = acceptedRequests.computeIfAbsent(
                playerId, ignored -> new LinkedHashMap<>(64, 0.75f, true));
        synchronized (requests) {
            if (requests.containsKey(requestId)) return RequestAdmission.DUPLICATE;
            requests.put(requestId, Boolean.TRUE);
            while (requests.size() > 64) {
                requests.remove(requests.keySet().iterator().next());
            }
            return RequestAdmission.NEW;
        }
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        selectedPathNpc.remove(playerId);
        cloneTemplates.remove(playerId);
        selectedPassengers.remove(playerId);
        lastDialogueChoiceMillis.remove(playerId);
        lastRoleActionMillis.remove(playerId);
        roleSessions.remove(playerId);
        acceptedRequests.remove(playerId);
    }

    public void clearAll() {
        selectedPathNpc.clear();
        cloneTemplates.clear();
        selectedPassengers.clear();
        lastDialogueChoiceMillis.clear();
        lastRoleActionMillis.clear();
        roleSessions.clear();
        acceptedRequests.clear();
    }

    private static boolean throttle(Map<UUID, Long> timestamps, UUID playerId, long nowMillis, long minimumIntervalMillis) {
        if (playerId == null || minimumIntervalMillis < 0) {
            return false;
        }
        Long last = timestamps.get(playerId);
        if (last != null && nowMillis - last < minimumIntervalMillis) {
            return true;
        }
        timestamps.put(playerId, nowMillis);
        return false;
    }
}
