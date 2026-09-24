package com.storynpcs.runtime.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storynpcs.persistence.DurableJsonStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Atomic persistence for logical actor identity. */
public final class ActorStateRepository {
    private final Path target;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final DurableJsonStore store;

    public ActorStateRepository(Path target) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        this.target = target;
        this.store = new DurableJsonStore(target, mapper);
    }

    public Path target() {
        return target;
    }

    public void save(ActorProjectionRegistry registry) throws IOException {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        store.write(registry.snapshot());
    }

    public boolean restore(ActorProjectionRegistry registry) throws IOException {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        DurableJsonStore.ReadResult<ActorRegistrySnapshot> result = store.read(ActorRegistrySnapshot.class);
        reportDiagnostics(result);
        if (!result.hasValue()) return false;
        registry.restore(result.value());
        return true;
    }

    public ActorRegistrySnapshot load() throws IOException {
        DurableJsonStore.ReadResult<ActorRegistrySnapshot> result = store.read(ActorRegistrySnapshot.class);
        reportDiagnostics(result);
        if (!result.hasValue()) {
            throw new IOException(result.sourcePresent()
                    ? "Actor state file is missing or unrecoverable: " + target
                    : "Actor state file does not exist: " + target);
        }
        return result.value();
    }

    private void reportDiagnostics(DurableJsonStore.ReadResult<?> result) {
        for (String diagnostic : result.diagnostics()) {
            System.err.println("[StoryNPCs] actor state recovery for " + target + ": " + diagnostic);
        }
    }
}
