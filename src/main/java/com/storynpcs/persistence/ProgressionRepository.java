package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storynpcs.domain.progression.PlayerProgression;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player progression persistence with atomic write safety:
 * writes to .tmp, flushes, and atomically moves to target file.
 */
public class ProgressionRepository {
    private final Path storageDirectory;
    private final ObjectMapper mapper;
    private final Map<UUID, PlayerProgression> cache = new ConcurrentHashMap<>();
    /** Players whose durable record exists but could not be loaded; writes are refused. */
    private final Map<UUID, String> unavailableRecords = new ConcurrentHashMap<>();

    public ProgressionRepository(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not create progression storage directory: " + storageDirectory, e);
        }
    }

    public PlayerProgression getOrCreate(UUID playerUuid) {
        return cache.computeIfAbsent(playerUuid, this::loadFromDisk);
    }

    /** Root used by sibling runtime stores that share the world persistence lifecycle. */
    public Path storageDirectory() {
        return storageDirectory;
    }

    /** True when the player's durable record is present but unrecoverable; operations fail closed. */
    public boolean isUnavailable(UUID playerUuid) {
        return unavailableRecords.containsKey(playerUuid);
    }

    /** Diagnostic describing why the player's record is blocked, or null when loadable. */
    public String unavailabilityReason(UUID playerUuid) {
        return unavailableRecords.get(playerUuid);
    }

    private PlayerProgression loadFromDisk(UUID playerUuid) {
        DurableJsonStore store = store(playerUuid);
        try {
            DurableJsonStore.ReadResult<PlayerProgression> result = store.read(PlayerProgression.class);
            reportDiagnostics("progression", playerUuid, result);
            if (result.hasValue()) return result.value();
            if (result.sourcePresent() || store.hasProtectedArtifacts()) {
                throw blockRecord(playerUuid,
                        "durable progression record is unrecoverable; refusing to initialize empty state");
            }
        } catch (IOException e) {
            throw blockRecord(playerUuid,
                    "could not inspect durable progression record: " + e.getMessage());
        }
        return new PlayerProgression(playerUuid);
    }

    private UnrecoverablePlayerDataException blockRecord(UUID playerUuid, String reason) {
        unavailableRecords.put(playerUuid, reason);
        System.err.println("[StoryNPCs] progression blocked for " + playerUuid + ": " + reason);
        return new UnrecoverablePlayerDataException("progression", playerUuid, reason);
    }

    public void save(UUID playerUuid) throws IOException {
        String blocked = unavailableRecords.get(playerUuid);
        if (blocked != null) {
            throw new IOException("progression write blocked for " + playerUuid + ": " + blocked);
        }
        PlayerProgression progression = cache.get(playerUuid);
        if (progression == null) return;

        synchronized (progression) {
            writeProgression(playerUuid, progression);
        }
    }

    /**
     * Persists a specific progression instance. Callers that already hold the
     * instance (e.g. inside {@code synchronized (progression)}) must use this
     * overload: a cache eviction between fetch and write must never turn a
     * committed mutation into a silent no-op.
     */
    public void save(UUID playerUuid, PlayerProgression progression) throws IOException {
        if (progression == null) {
            save(playerUuid);
            return;
        }
        String blocked = unavailableRecords.get(playerUuid);
        if (blocked != null) {
            throw new IOException("progression write blocked for " + playerUuid + ": " + blocked);
        }
        synchronized (progression) {
            writeProgression(playerUuid, progression);
        }
    }

    /** The single durable boundary every progression write funnels through (virtual for fault injection). */
    protected void writeProgression(UUID playerUuid, PlayerProgression progression) throws IOException {
        store(playerUuid).write(progression);
    }

    private DurableJsonStore store(UUID playerUuid) {
        return new DurableJsonStore(storageDirectory.resolve(playerUuid.toString() + ".json"), mapper);
    }

    private void reportDiagnostics(String kind, UUID playerUuid,
                                   DurableJsonStore.ReadResult<?> result) {
        for (String diagnostic : result.diagnostics()) {
            System.err.println("[StoryNPCs] " + kind + " recovery for " + playerUuid + ": " + diagnostic);
        }
    }

    public void unload(UUID playerUuid) {
        if (playerUuid == null) return;
        // Evict under the progression monitor so an in-flight mutation finishes
        // its durable write before the instance leaves the cache.
        PlayerProgression progression = cache.get(playerUuid);
        if (progression != null) {
            synchronized (progression) {
                cache.remove(playerUuid, progression);
            }
        } else {
            cache.remove(playerUuid);
        }
        unavailableRecords.remove(playerUuid);
    }

    public void saveAll() {
        for (UUID uuid : cache.keySet()) {
            try {
                save(uuid);
            } catch (Exception e) {
                System.err.println("Error saving progression for " + uuid + ": " + e.getMessage());
            }
        }
    }

    public void clearCache() {
        cache.clear();
        unavailableRecords.clear();
    }
}
