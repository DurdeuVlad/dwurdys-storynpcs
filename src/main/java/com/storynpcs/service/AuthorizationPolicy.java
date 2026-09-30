package com.storynpcs.service;

import java.util.Set;

/**
 * Small, deterministic policy boundary used by canonical mutations.
 * Platform adapters prove OP/permission-node authority before creating a player request;
 * this layer still rejects unknown principals, capabilities, and unproven player requests.
 */
public final class AuthorizationPolicy {
    private static final Set<String> ADAPTER_ACTORS = Set.of(
            "adapter", "command", "packet", "gui", "api", "console", "system");

    private AuthorizationPolicy() {}

    public static AuthorizationDecision evaluate(MutationRequest request) {
        String actor = request.actorType();
        String capability = request.capability();
        boolean playerActor = actor.startsWith("player:") && actor.length() > "player:".length();
        if (!playerActor && !ADAPTER_ACTORS.contains(actor) && !actor.equals("script")) {
            return AuthorizationDecision.deny("UNKNOWN_ACTOR", "Unknown mutation actor type: " + actor);
        }
        if (CapabilityRegistry.policyOf(capability) != CapabilityRegistry.Policy.DEFINITION) {
            return AuthorizationDecision.deny("UNKNOWN_CAPABILITY", "Capability is not registered: " + capability);
        }
        if (actor.equals("script")) {
            return AuthorizationDecision.deny("SCRIPT_CAPABILITY_REQUIRED",
                    "Scripts cannot invoke definition mutations without an explicit granted capability.");
        }
        if (playerActor && request.permissionLevel() < 2) {
            return AuthorizationDecision.deny("PERMISSION_DENIED",
                    "Player mutation requests require operator level 2 proof.");
        }
        return AuthorizationDecision.allow();
    }

    /** Authorization for player-scoped quest mutations; the subject is not inferred from a quest ID. */
    public static AuthorizationDecision evaluate(QuestProgressionMutationRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.playerUuid(), request.permissionLevel(), "quest progression",
                request.operation());
    }

    /** Authorization for player-scoped quest completion; the subject is not inferred from a quest ID. */
    public static AuthorizationDecision evaluate(QuestCompletionMutationRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.playerUuid(), request.permissionLevel(), "quest completion",
                request.operation());
    }

    /** Authorization for player-scoped faction-standing mutations. */
    public static AuthorizationDecision evaluate(FactionProgressionMutationRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.playerUuid(), request.permissionLevel(), "faction progression",
                request.operation());
    }

    /** Authorization for owner-scoped follower state mutations. */
    public static AuthorizationDecision evaluate(FollowerStateMutationRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.playerUuid(), request.permissionLevel(), "follower state",
                request.operation());
    }

    /** Authorization for player-scoped bank vault operations (deposit/withdraw/unlock). */
    public static AuthorizationDecision evaluate(BankOperationRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.playerUuid(), request.permissionLevel(), "bank vault",
                request.capability());
    }

    /**
     * Authorization for vault-sharing configuration. The subject is the vault
     * owner — configuring sharing mutates the owner's durable vault record.
     */
    public static AuthorizationDecision evaluate(BankAccessMutationRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.vaultOwnerUuid(), request.permissionLevel(), "bank access",
                request.capability());
    }

    /** Authorization for player-scoped trade execution. */
    public static AuthorizationDecision evaluate(TradeExecutionRequest request) {
        return evaluatePlayerScoped(request.actorType(), request.actorId(),
                request.playerUuid(), request.permissionLevel(), "trade",
                request.capability());
    }

    /**
     * Authorization for remote (API-carried) mutations: the base definition
     * rules plus a {@link com.storynpcs.admin.RemoteAccessProof} that must be
     * temporally valid AND carry the request's capability. A missing, expired,
     * or under-scoped proof fails closed before any mutation.
     */
    public static AuthorizationDecision evaluateRemote(MutationRequest request,
            com.storynpcs.admin.RemoteAccessProof proof, long nowTick) {
        AuthorizationDecision base = evaluate(request);
        if (!base.allowed()) {
            return base;
        }
        if (proof == null || !proof.permits(request.capability(), nowTick)) {
            return AuthorizationDecision.deny("REMOTE_PROOF_INVALID",
                    "Remote mutations require an unexpired proof carrying the capability");
        }
        return AuthorizationDecision.allow();
    }

    private static AuthorizationDecision evaluatePlayerScoped(
            String actor, java.util.UUID actorId, java.util.UUID playerUuid,
            int permissionLevel, String domain, String capability) {
        if (CapabilityRegistry.policyOf(capability) != CapabilityRegistry.Policy.PLAYER_SCOPED) {
            return AuthorizationDecision.deny("UNKNOWN_CAPABILITY",
                    "Capability is not registered for " + domain + " operations: " + capability);
        }
        if (actor.equals("script")) {
            return AuthorizationDecision.deny("SCRIPT_CAPABILITY_REQUIRED",
                    "Scripts cannot mutate " + domain + " without an explicit granted capability.");
        }
        if (actor.equals("player") || actor.equals("dialogue")) {
            if (!playerUuid.equals(actorId)) {
                return AuthorizationDecision.deny("PLAYER_SUBJECT_MISMATCH",
                        "Player and dialogue actors may only mutate their own " + domain + ".");
            }
            return AuthorizationDecision.allow();
        }
        if (actor.equals("command")) {
            // P9-4: operator data scope — SELF for unproven/low-permission
            // actors, ADMIN once permission level 2 is established. The scope
            // makes the cross-player boundary explicit and testable.
            var scope = permissionLevel >= 2
                    ? com.storynpcs.admin.PlayerDataScope.ADMIN
                    : com.storynpcs.admin.PlayerDataScope.SELF;
            if (!scope.permits(actorId, playerUuid)) {
                return AuthorizationDecision.deny("PERMISSION_DENIED",
                        "Changing another player's " + domain + " requires permission level 2.");
            }
            return AuthorizationDecision.allow();
        }
        if (actor.equals("system")) return AuthorizationDecision.allow();
        return AuthorizationDecision.deny("UNKNOWN_ACTOR", "Unknown " + domain + " actor: " + actor);
    }
}
