package com.storynpcs.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Durable runtime-tunables record (P9-4). One JSON record per world holds the
 * committed tunable map plus the revision that produced it, written through
 * the same atomic {@link DurableJsonStore} boundary as every other store —
 * a committed config change persists before the mutation returns.
 *
 * <p>Load is deliberately not a blind write: the caller restores through
 * {@link com.storynpcs.admin.RuntimeTunables#restore}, which re-validates the
 * persisted map and refuses corrupt values so a hand-edited or damaged record
 * can never poison live state.
 */
public final class RuntimeTunablesStore {

    /** One durable record: the committed tunable map and its revision. */
    public record PersistedTunables(long revision, Map<String, String> values) {
        public PersistedTunables {
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    private final DurableJsonStore store;

    public RuntimeTunablesStore(Path directory, ObjectMapper mapper) {
        this.store = new DurableJsonStore(directory.resolve("runtime_tunables.json"), mapper);
    }

    /** Load the persisted record, or empty when the file does not exist yet. */
    public java.util.Optional<PersistedTunables> load() throws IOException {
        DurableJsonStore.ReadResult<PersistedTunables> result = store.read(PersistedTunables.class);
        return java.util.Optional.ofNullable(result.value());
    }

    /** Persist a full snapshot — atomic tmp→rename via the durable boundary. */
    public void save(long revision, Map<String, String> snapshot) throws IOException {
        store.write(new PersistedTunables(revision, new LinkedHashMap<>(snapshot)));
    }
}
