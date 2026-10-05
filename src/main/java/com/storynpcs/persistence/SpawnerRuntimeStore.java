package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.creator.template.SpawnerRuntimeState;
import com.storynpcs.domain.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Durable spawner-runtime ledger (P8-1) backed by the indexed record store —
 * one record per spawner rule id, committed atomically per change so quota
 * ownership survives crashes and restarts.
 */
public final class SpawnerRuntimeStore {

    private final IndexedRecordStore store;

    public SpawnerRuntimeStore(Path directory, ObjectMapper mapper) {
        this.store = new IndexedRecordStore(directory, mapper);
    }

    public void open() throws IOException {
        store.open();
    }

    public Optional<SpawnerRuntimeState> load(NamespacedId spawnerId) throws IOException {
        return store.read(spawnerId.toString(), SpawnerRuntimeState.class);
    }

    public void save(SpawnerRuntimeState state) throws IOException {
        store.write(state.getSpawnerId(), state);
    }

    public boolean delete(NamespacedId spawnerId) throws IOException {
        return store.delete(spawnerId.toString());
    }

    /** All persisted spawner ids — pruned against the rule registry each pass. */
    public List<String> listIds() {
        return store.listIds();
    }
}
