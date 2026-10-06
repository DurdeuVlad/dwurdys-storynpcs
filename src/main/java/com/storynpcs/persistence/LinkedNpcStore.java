package com.storynpcs.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Durable linked-NPC ledger (P8-5) — one record per actor whose target link
 * must survive unload/restart. The in-memory {@code LinkedNpcGraph} is
 * rehydrated from this store at world init.
 */
public final class LinkedNpcStore {

    /** Single persisted link record. */
    public record LinkRecord(UUID actorUuid, UUID targetUuid) {}

    private final IndexedRecordStore store;

    public LinkedNpcStore(Path directory, ObjectMapper mapper) {
        this.store = new IndexedRecordStore(directory, mapper);
    }

    public void open() throws IOException {
        store.open();
    }

    public Optional<LinkRecord> load(UUID actorUuid) throws IOException {
        return store.read(actorUuid.toString(), LinkRecord.class);
    }

    public void save(LinkRecord record) throws IOException {
        store.write(record.actorUuid().toString(), record);
    }

    public boolean delete(UUID actorUuid) throws IOException {
        return store.delete(actorUuid.toString());
    }

    public List<String> listIds() {
        return store.listIds();
    }
}
