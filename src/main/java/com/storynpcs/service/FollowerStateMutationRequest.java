package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable command for a player-scoped follower state or formation mutation. */
public record FollowerStateMutationRequest(
        String actorType,
        UUID actorId,
        UUID playerUuid,
        NamespacedId npcId,
        Action action,
        FollowerRole.State state,
        FormationType formation,
        int slotIndex,
        double spacing,
        UUID requestId,
        int permissionLevel,
        UUID ownerUuid) {

    public enum Action { SET_STATE, SET_FORMATION, SET_OWNER }

    private static final Set<String> ACTORS = Set.of("command", "dialogue", "player", "script", "system");

    public FollowerStateMutationRequest {
        if (actorType == null || !ACTORS.contains(actorType)) {
            throw new IllegalArgumentException("actorType must be a registered follower actor");
        }
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestId, "requestId");
        if (permissionLevel < -1) throw new IllegalArgumentException("permissionLevel must be >= -1");
        if (action == Action.SET_STATE) {
            Objects.requireNonNull(state, "state");
        } else if (action == Action.SET_OWNER) {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
        } else {
            Objects.requireNonNull(formation, "formation");
            if (slotIndex < -1 || slotIndex > 64) {
                throw new IllegalArgumentException("slotIndex must be between -1 (auto) and 64");
            }
            if (!(spacing > 0.0) || spacing > 64.0 || Double.isNaN(spacing) || Double.isInfinite(spacing)) {
                throw new IllegalArgumentException("spacing must be within (0, 64]");
            }
        }
    }

    public static FollowerStateMutationRequest setState(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId npcId,
            FollowerRole.State state, UUID requestId) {
        return new FollowerStateMutationRequest(actorType, actorId, playerUuid, npcId,
                Action.SET_STATE, state, null, -1, 0.0, requestId, -1, null);
    }

    public static FollowerStateMutationRequest setFormation(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId npcId,
            FormationType formation, int slotIndex, double spacing, UUID requestId) {
        return new FollowerStateMutationRequest(actorType, actorId, playerUuid, npcId,
                Action.SET_FORMATION, null, formation, slotIndex, spacing, requestId, -1, null);
    }

    /** Target `/noppes owner set`: reassign a follower's owning player (P9-3). */
    public static FollowerStateMutationRequest setOwner(
            String actorType, UUID actorId, UUID playerUuid, NamespacedId npcId,
            UUID ownerUuid, UUID requestId, int permissionLevel) {
        return new FollowerStateMutationRequest(actorType, actorId, playerUuid, npcId,
                Action.SET_OWNER, null, null, -1, 0.0, requestId, permissionLevel, ownerUuid);
    }

    public String operation() {
        return switch (action) {
            case SET_STATE -> "follower.state.set";
            case SET_FORMATION -> "follower.formation.set";
            case SET_OWNER -> "follower.owner.set";
        };
    }

    public String capability() {
        return operation();
    }
}
