package com.storynpcs.service;

import java.util.Set;

/**
 * Small, deterministic policy boundary used by canonical mutations.
 * Platform adapters prove OP/permission-node authority before creating a player request;
 * this layer still rejects unknown principals, capabilities, and unproven player requests.
 */
public final class AuthorizationPolicy {
    private static final Set<String> ADAPTER_ACTORS = Set.of(
            "adapter", "command", "packet", "gui", "console", "system");

    private AuthorizationPolicy() {}

    /** Seam: validates an {@code api:<sessionId>} actor's grant for a capability. */
    @FunctionalInterface
    public interface ApiGrantValidator {
        boolean grants(java.util.UUID sessionId, String capability);
    }

    /** API actor prefix — the opaque session id follows it. */
    private static final String API_ACTOR_PREFIX = "api:";

    public static AuthorizationDecision evaluate(MutationRequest request) {
        // No session registry wired — every API actor form fails closed.
        return evaluate(request, null);
    }

    public static AuthorizationDecision evaluate(MutationRequest request,
                                                 ApiGrantValidator apiGrants) {
        String actor = request.actorType();
        String capability = request.capability();
        if (actor.equals("api") || actor.startsWith(API_ACTOR_PREFIX)) {
            return evaluateApiActor(actor, capability, apiGrants);
        }
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
     * API actors present a server-issued session: {@code api:<sessionUuid>}
     * must carry a live grant for the requested capability. A bare {@code api}
     * actor (no session) is always denied — sessions are never optional.
     */
    private static AuthorizationDecision evaluateApiActor(String actor, String capability,
                                                          ApiGrantValidator apiGrants) {
        if (apiGrants == null) {
            return AuthorizationDecision.deny("REMOTE_AUTH_UNAVAILABLE",
                    "API mutations are disabled until server-owned capability sessions are integrated.");
        }
        if (!actor.startsWith(API_ACTOR_PREFIX)) {
            return AuthorizationDecision.deny("API_SESSION_REQUIRED",
                    "API mutations require a capability session: actor 'api:<sessionId>'.");
        }
        java.util.UUID sessionId;
        try {
            sessionId = java.util.UUID.fromString(actor.substring(API_ACTOR_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return AuthorizationDecision.deny("API_SESSION_MALFORMED",
                    "API session id is not a UUID: " + actor);
        }
        if (!apiGrants.grants(sessionId, capability)) {
            return AuthorizationDecision.deny("API_CAPABILITY_NOT_GRANTED",
                    "API session does not grant capability '" + capability + "'.");
        }
        return AuthorizationDecision.allow();
    }

    /**
     * Scripted-path authorization (P9-2): a {@code script} actor is allowed
     * exactly when the server-validated grant set (the authored
     * {@code ScriptDefinition.capabilities}) contains the request operation.
     * Scripts never gain subject or permission privileges implicitly — the
     * grant IS the authority, and ungranted operations fail closed.
     */
    public static AuthorizationDecision evaluateScripted(
            PlayerProgressionActionRequest request, java.util.Set<String> grants) {
        return evaluateScriptGrant(request.actorType(), request.operation(), grants);
    }

    /** Scripted-path authorization for faction-standing mutations (P9-2). */
    public static AuthorizationDecision evaluateScripted(
            FactionProgressionMutationRequest request, java.util.Set<String> grants) {
        return evaluateScriptGrant(request.actorType(), request.operation(), grants);
    }

    /** Scripted-path authorization for quest progression mutations (P9-2). */
    public static AuthorizationDecision evaluateScripted(
            QuestProgressionMutationRequest request, java.util.Set<String> grants) {
        return evaluateScriptGrant(request.actorType(), request.operation(), grants);
    }

    /** Scripted-path authorization for quest completion mutations (P9-2). */
    public static AuthorizationDecision evaluateScripted(
            QuestCompletionMutationRequest request, java.util.Set<String> grants) {
        return evaluateScriptGrant(request.actorType(), request.operation(), grants);
    }

    private static AuthorizationDecision evaluateScriptGrant(
            String actorType, String operation, java.util.Set<String> grants) {
        if (!"script".equals(actorType)) {
            return AuthorizationDecision.deny("SCRIPT_ACTOR_REQUIRED",
                    "The scripted boundary requires actor 'script', got '" + actorType + "'.");
        }
        if (grants == null || !grants.contains(operation)) {
            return AuthorizationDecision.deny("SCRIPT_CAPABILITY_NOT_GRANTED",
                    "Script does not declare capability grant '" + operation + "'.");
        }
        return AuthorizationDecision.allow();
    }

    /** Remote mutations fail closed until a server-owned capability-session registry is integrated. */
    public static AuthorizationDecision evaluateRemote(MutationRequest request,
            com.storynpcs.admin.RemoteAccessProof proof, long nowTick) {
        return AuthorizationDecision.deny("REMOTE_AUTH_UNAVAILABLE",
                "Remote mutations are disabled until a server-owned capability session is validated.");
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

    /**
     * Authorization for player-scoped progression actions with no numeric payload
     * (mail read/delete, transport-location unlock; issue #54). Mirrors the
     * player/dialogue/command/system rules of the Quest and Faction progression
     * overloads above exactly.
     */
    public static AuthorizationDecision evaluate(PlayerProgressionActionRequest request) {
        if (CapabilityRegistry.policyOf(request.operation()) != CapabilityRegistry.Policy.PLAYER_SCOPED) {
            return AuthorizationDecision.deny("UNKNOWN_CAPABILITY",
                    "Operation is not a registered player progression action: " + request.operation());
        }
        String actor = request.actorType();
        if (actor.equals("script")) {
            return AuthorizationDecision.deny("SCRIPT_CAPABILITY_REQUIRED",
                    "Scripts cannot perform player progression actions without an explicit granted capability.");
        }
        if (actor.equals("player") || actor.equals("dialogue")) {
            if (!request.playerUuid().equals(request.actorId())) {
                return AuthorizationDecision.deny("PLAYER_SUBJECT_MISMATCH",
                        "Player and dialogue actors may only act on their own progression.");
            }
            return AuthorizationDecision.allow();
        }
        if (actor.equals("command")) {
            boolean self = request.playerUuid().equals(request.actorId());
            if (!self && request.permissionLevel() < 2) {
                return AuthorizationDecision.deny("PERMISSION_DENIED",
                        "Acting on another player's progression requires permission level 2.");
            }
            return AuthorizationDecision.allow();
        }
        if (actor.equals("system")) return AuthorizationDecision.allow();
        return AuthorizationDecision.deny("UNKNOWN_ACTOR", "Unknown progression actor: " + actor);
    }
}
