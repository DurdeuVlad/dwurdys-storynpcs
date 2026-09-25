package com.storynpcs.persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Durable runtime state for trader listing uses; YAML definitions remain read-only content. */
public final class TradeStateRepository {
    private static final int MAX_KEY_LENGTH = 512;

    public static final class State {
        @JsonProperty
        private Map<String, Integer> uses = new HashMap<>();

        public Map<String, Integer> getUses() { return uses; }
        public void setUses(Map<String, Integer> uses) {
            this.uses = uses == null ? new HashMap<>() : new HashMap<>(uses);
        }
    }

    private final Path storageDirectory;
    private final Path target;
    private final ObjectMapper mapper;
    private State state;
    private boolean unavailable;

    public TradeStateRepository(Path storageDirectory) {
        if (storageDirectory == null) throw new IllegalArgumentException("storageDirectory cannot be null");
        this.storageDirectory = storageDirectory;
        this.target = storageDirectory.resolve("state.json");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not create trade state directory: " + storageDirectory, e);
        }
    }

    public synchronized int getUses(String npcId, String listingId) throws IOException {
        return state().getUses().getOrDefault(key(npcId, listingId), 0);
    }

    /** Atomically reserves one durable listing use if the expected projection still matches. */
    public synchronized boolean reserveUse(String npcId, String listingId,
                                            int expectedUses, int maxUses) throws IOException {
        String key = key(npcId, listingId);
        State current = state();
        int actual = current.getUses().getOrDefault(key, 0);
        if (actual != expectedUses || actual < 0 || (maxUses > 0 && actual >= maxUses)) return false;
        State snapshot = copy(current);
        current.getUses().put(key, actual + 1);
        try {
            store().write(current);
            return true;
        } catch (IOException | RuntimeException failure) {
            state = snapshot;
            throw failure;
        }
    }

    /** Compensates a reserved use only when no other operation has advanced the same listing. */
    public synchronized boolean rollbackUse(String npcId, String listingId,
                                             int reservedUses, int expectedUses) throws IOException {
        String key = key(npcId, listingId);
        State current = state();
        int actual = current.getUses().getOrDefault(key, 0);
        if (actual != reservedUses) return false;
        State snapshot = copy(current);
        current.getUses().put(key, expectedUses);
        try {
            store().write(current);
            return true;
        } catch (IOException | RuntimeException failure) {
            state = snapshot;
            throw failure;
        }
    }

    public synchronized void save() throws IOException {
        if (state != null) store().write(state);
    }

    private State state() throws IOException {
        if (unavailable) throw new IOException("trade state is unavailable after an invalid durable record");
        if (state != null) return state;
        DurableJsonStore.ReadResult<State> result = store().read(State.class);
        for (String diagnostic : result.diagnostics()) {
            System.err.println("[StoryNPCs] trade state recovery: " + diagnostic);
        }
        if (result.hasValue()) {
            state = result.value();
            if (state.getUses() == null) state.setUses(null);
            try {
                validateState(state);
            } catch (IllegalArgumentException invalid) {
                state = null;
                unavailable = true;
                throw new IOException("trade state record contains invalid listing uses: " + target, invalid);
            }
            return state;
        }
        if (result.sourcePresent() || store().hasProtectedArtifacts()) {
            unavailable = true;
            throw new IOException("trade state record is invalid or quarantined: " + target);
        }
        state = new State();
        return state;
    }

    private DurableJsonStore store() {
        return new DurableJsonStore(target, mapper);
    }

    private static State copy(State source) {
        State copy = new State();
        copy.setUses(source.getUses());
        return copy;
    }

    private static void validateState(State state) {
        if (state == null || state.getUses() == null) {
            throw new IllegalArgumentException("trade state uses cannot be null");
        }
        for (Map.Entry<String, Integer> entry : state.getUses().entrySet()) {
            String key = entry.getKey();
            Integer uses = entry.getValue();
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                    || uses == null || uses < 0) {
                throw new IllegalArgumentException("invalid trade state entry");
            }
        }
    }

    private static String key(String npcId, String listingId) {
        if (npcId == null || npcId.isBlank()) throw new IllegalArgumentException("npcId cannot be blank");
        if (listingId == null || listingId.isBlank()) throw new IllegalArgumentException("listingId cannot be blank");
        if (!listingId.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException("listingId is malformed");
        String key = npcId + "|" + listingId;
        if (key.length() > MAX_KEY_LENGTH) throw new IllegalArgumentException("trade state key is too long");
        return key;
    }
}
