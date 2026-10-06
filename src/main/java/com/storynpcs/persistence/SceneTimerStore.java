package com.storynpcs.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.creator.scene.SceneTimer.TimerEntry;

/**
 * Durable scene-timer ledger (P8-5) — one record per scheduled timer so
 * periodic events survive restart without duplicating or losing a firing.
 */
public final class SceneTimerStore {

    private final IndexedRecordStore store;

    public SceneTimerStore(Path directory, ObjectMapper mapper) {
        this.store = new IndexedRecordStore(directory, mapper);
    }

    public void open() throws IOException {
        store.open();
    }

    public Optional<TimerEntry> load(UUID timerId) throws IOException {
        return store.read(timerId.toString(), TimerEntry.class);
    }

    public void save(TimerEntry entry) throws IOException {
        store.write(entry.timerId().toString(), entry);
    }

    public boolean delete(UUID timerId) throws IOException {
        return store.delete(timerId.toString());
    }

    public List<String> listIds() {
        return store.listIds();
    }
}
