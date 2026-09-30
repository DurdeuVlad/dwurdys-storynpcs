package com.storynpcs.service;

import com.storynpcs.domain.role.banker.BankVault;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical request that configures a bank vault's sharing policy and member
 * list. This is the only mutation path for {@link BankVault.AccessPolicy} —
 * sharing is progression data (durable per-vault), never definition data, so
 * it crosses the application-service boundary like every other bank mutation.
 *
 * <p>Authorization: the vault owner is the subject; {@code command} actors need
 * permission level 2 for cross-subject (admin) scope, {@code system} is always
 * permitted, and the owner may configure their own vault through
 * {@code player}-scoped adapters.</p>
 */
public record BankAccessMutationRequest(
        String actorType,
        UUID actorId,
        UUID vaultOwnerUuid,
        BankVault.AccessPolicy accessPolicy,
        Set<UUID> memberUuids,
        long expectedVaultRevision,
        UUID requestId,
        int permissionLevel) {

    public static final int MAX_MEMBERS = 64;
    private static final Set<String> ACTOR_TYPES = Set.of(
            "command", "dialogue", "player", "script", "system");

    public BankAccessMutationRequest {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(vaultOwnerUuid, "vaultOwnerUuid");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        Objects.requireNonNull(requestId, "requestId");
        actorType = actorType.trim().toLowerCase(Locale.ROOT);
        if (actorType.isEmpty() || !ACTOR_TYPES.contains(actorType)) {
            throw new IllegalArgumentException("Unsupported actor type: " + actorType);
        }
        if (expectedVaultRevision < 0) {
            throw new IllegalArgumentException("expectedVaultRevision must be non-negative");
        }
        memberUuids = memberUuids == null
                ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(memberUuids));
        if (memberUuids.contains(null)) {
            throw new IllegalArgumentException("memberUuids cannot contain null");
        }
        if (memberUuids.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("memberUuids exceeds " + MAX_MEMBERS + " entries");
        }
        // Members only have meaning on a SHARED vault — reject ambiguous
        // configuration instead of silently dropping listed members.
        if (accessPolicy == BankVault.AccessPolicy.PRIVATE && !memberUuids.isEmpty()) {
            throw new IllegalArgumentException("PRIVATE vaults cannot list shared members");
        }
    }

    public String operation() {
        return "bank.share";
    }

    public String capability() {
        return "bank.share";
    }
}
