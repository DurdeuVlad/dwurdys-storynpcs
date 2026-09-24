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

    private PlayerProgression loadFromDisk(UUID playerUuid) {
        try {
            DurableJsonStore.ReadResult<PlayerProgression> result = store(playerUuid).read(PlayerProgression.class);
            reportDiagnostics("progression", playerUuid, result);
            if (result.hasValue()) return result.value();
        } catch (IOException e) {
            System.err.println("Could not load progression for " + playerUuid + ": " + e.getMessage());
        }
        return new PlayerProgression(playerUuid);
    }

    public void save(UUID playerUuid) throws IOException {
        PlayerProgression progression = cache.get(playerUuid);
        if (progression == null) return;

        synchronized (progression) {
            store(playerUuid).write(progression);
        }
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
        if (playerUuid != null) {
            cache.remove(playerUuid);
        }
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
    }
}
