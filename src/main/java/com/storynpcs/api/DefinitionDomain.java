package com.storynpcs.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.service.CanonicalMutationResult;
import com.storynpcs.service.MutationRequest;

/**
 * Typed per-domain definition interface (P9-1). Every mutation routes through
 * the canonical service envelope — the API never touches the registry, files,
 * or runtime stores directly. Reads expose only detached views: no mutable
 * domain object escapes the facade.
 */
public final class DefinitionDomain<T> {

    /** Immutable detached summary of a registered definition. */
    public record DefinitionView(NamespacedId id, String kind, int schemaVersion) {}

    private final String kind;
    private final Supplier<? extends java.util.Collection<T>> listAll;
    private final Function<T, NamespacedId> idOf;
    private final Function<T, Integer> schemaOf;
    private final BiFunction<MutationRequest, T, CanonicalMutationResult> saver;
    private final Function<MutationRequest, CanonicalMutationResult> deleter;
    private final StoryNpcsApi api;

    DefinitionDomain(StoryNpcsApi api, String kind,
                     Supplier<? extends java.util.Collection<T>> listAll,
                     Function<T, NamespacedId> idOf,
                     Function<T, Integer> schemaOf,
                     BiFunction<MutationRequest, T, CanonicalMutationResult> saver,
                     Function<MutationRequest, CanonicalMutationResult> deleter) {
        this.api = api;
        this.kind = kind;
        this.listAll = listAll;
        this.idOf = idOf;
        this.schemaOf = schemaOf;
        this.saver = saver;
        this.deleter = deleter;
    }

    public String kind() {
        return kind;
    }

    /** All registered ids for this domain — detached, sorted upstream. */
    public List<NamespacedId> ids() {
        return listAll.get().stream().map(idOf).toList();
    }

    /** Immutable summaries — the only shape a definition leaves the facade as. */
    public List<DefinitionView> summaries() {
        return listAll.get().stream()
                .map(d -> new DefinitionView(idOf.apply(d), kind, schemaOf.apply(d)))
                .toList();
    }

    public boolean contains(NamespacedId id) {
        return listAll.get().stream().anyMatch(d -> id.equals(idOf.apply(d)));
    }

    /**
     * Save (create or replace) a definition through the canonical mutation
     * path — session grant, revision check, fingerprint, and durable write all
     * enforced by the service.
     */
    public ValidationResult save(ApiSessionRegistry.Session session, T payload) {
        var request = api.mutationRequest(session, kind + ".replace", kind + ".mutate",
                idOf.apply(payload));
        if (request == null) {
            var denied = ValidationResult.valid();
            denied.addError("API_SESSION_INVALID",
                    "session is expired, revoked, or does not grant '" + kind + ".mutate'");
            return denied;
        }
        return saver.apply(request, payload).diagnostics();
    }

    /** Delete a definition through the canonical mutation path. */
    public ValidationResult delete(ApiSessionRegistry.Session session, NamespacedId id) {
        var request = api.mutationRequest(session, kind + ".delete", kind + ".delete", id);
        if (request == null) {
            var denied = ValidationResult.valid();
            denied.addError("API_SESSION_INVALID",
                    "session is expired, revoked, or does not grant '" + kind + ".delete'");
            return denied;
        }
        return deleter.apply(request).diagnostics();
    }

    static MutationRequest request(ApiSessionRegistry.Session session, String operation,
                                   String capability, NamespacedId target, long revision) {
        return new MutationRequest(operation, "api:" + session.sessionId(), capability,
                target, revision, UUID.randomUUID());
    }
}
