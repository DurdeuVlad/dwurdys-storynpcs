package com.storynpcs.runtime.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storynpcs.persistence.DurableJsonStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Atomic persistence for logical actor identity.
 *
 * <p>The repository is bound to the world's durable scope token (see
 * {@code WorldScopeIdentity}), never to the absolute world path — relocating a
 * world directory must not orphan or overwrite saved identities. When the
 * stored record cannot be read, or carries a different scope than the world
 * expects, the repository enters a <em>blocked</em> state: reads keep
 * reporting the failure and every write is refused so an empty registry can
 * never silently clobber real data.
 */
public final class ActorStateRepository {
    private final Path target;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final DurableJsonStore store;
    private final String expectedScopeId;
    private volatile String blockedReason;

    /** Legacy constructor: no expected scope; whatever the record carries is adopted. */
    public ActorStateRepository(Path target) {
        this(target, null);
    }

    public ActorStateRepository(Path target, String expectedScopeId) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        this.target = target;
        this.expectedScopeId = expectedScopeId;
        this.store = new DurableJsonStore(target, mapper);
    }

    public Path target() {
        return target;
    }

    /** True once a load observed unrecoverable or foreign-scope data; writes stay refused. */
    public boolean isBlocked() {
        return blockedReason != null;
    }

    public String blockedReason() {
        return blockedReason;
    }

    /** Marks the repository fail-closed; idempotent, first reason wins. */
    public synchronized void markBlocked(String reason) {
        if (blockedReason == null) {
            blockedReason = reason == null ? "actor state blocked" : reason;
        }
    }

    public void save(ActorProjectionRegistry registry) throws IOException {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        String blocked = blockedReason;
        if (blocked != null) {
            throw new IOException("actor state write refused while blocked: " + blocked);
        }
        if (expectedScopeId != null && !expectedScopeId.equals(registry.scopeId())) {
            throw new IOException("refusing to write actor registry for scope " + registry.scopeId()
                    + " into world scope " + expectedScopeId);
        }
        store.write(registry.snapshot());
    }

    public boolean restore(ActorProjectionRegistry registry) throws IOException {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        ActorRegistrySnapshot snapshot = readSnapshot();
        if (snapshot == null) return false;
        registry.restore(snapshot);
        return true;
    }

    public ActorRegistrySnapshot load() throws IOException {
        ActorRegistrySnapshot snapshot = readSnapshot();
        if (snapshot == null) {
            throw new IOException("Actor state file does not exist: " + target);
        }
        return snapshot;
    }

    private ActorRegistrySnapshot readSnapshot() throws IOException {
        DurableJsonStore.ReadResult<ActorRegistrySnapshot> result = store.read(ActorRegistrySnapshot.class);
        reportDiagnostics(result);
        if (!result.hasValue()) {
            if (result.sourcePresent() || store.hasProtectedArtifacts()) {
                markBlocked("actor state record is unrecoverable; refusing to initialize empty state");
                throw new IOException("Actor state file is unrecoverable: " + target);
            }
            return null;
        }
        ActorRegistrySnapshot snapshot = result.value();
        if (expectedScopeId != null && !expectedScopeId.equals(snapshot.scopeId())) {
            markBlocked("actor state scope mismatch: expected " + expectedScopeId
                    + " but found " + snapshot.scopeId());
            throw new IOException("Actor state belongs to a different world scope: " + target);
        }
        return snapshot;
    }

    private void reportDiagnostics(DurableJsonStore.ReadResult<?> result) {
        for (String diagnostic : result.diagnostics()) {
            System.err.println("[StoryNPCs] actor state recovery for " + target + ": " + diagnostic);
        }
    }
}
