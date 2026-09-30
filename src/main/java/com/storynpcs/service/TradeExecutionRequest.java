package com.storynpcs.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical request for player-subject trade execution against a trader NPC.
 * Carries the durable request id bound to the trade operation journal so
 * duplicate submissions classify as replays after the authorization gate.
 */
public record TradeExecutionRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        com.storynpcs.domain.common.NamespacedId npcId,
        int listingIndex,
        UUID requestId,
        int permissionLevel) {

    private static final Set<String> ACTOR_TYPES = Set.of(
            "command", "dialogue", "player", "script", "system");

    public TradeExecutionRequest {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(requestId, "requestId");
        actorType = actorType.trim().toLowerCase(Locale.ROOT);
        if (actorType.isEmpty() || !ACTOR_TYPES.contains(actorType)) {
            throw new IllegalArgumentException("Unsupported actor type: " + actorType);
        }
        if (listingIndex < -1) throw new IllegalArgumentException("Invalid listing index: " + listingIndex);
    }

    public String operation() {
        return "trade.execute";
    }

    public String capability() {
        return "trade.execute";
    }
}
