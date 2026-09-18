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

    private PlayerProgression loadFromDisk(UUID playerUuid) {
        Path filePath = storageDirectory.resolve(playerUuid.toString() + ".json");
        if (Files.exists(filePath)) {
            try {
                return mapper.readValue(Files.readAllBytes(filePath), PlayerProgression.class);
            } catch (IOException e) {
                // If corrupted, backup to .corrupted.<timestamp> rather than silently destroying data
                Path backupPath = storageDirectory.resolve(playerUuid.toString() + ".corrupted." + System.currentTimeMillis());
                try {
                    Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("Corrupted progression for " + playerUuid + " backed up to: " + backupPath);
                } catch (IOException backupEx) {
                    System.err.println("Failed to backup corrupted progression: " + backupEx.getMessage());
                }
            }
        }
        return new PlayerProgression(playerUuid);
    }

    public void save(UUID playerUuid) throws IOException {
        PlayerProgression progression = cache.get(playerUuid);
        if (progression == null) return;

        Path targetPath = storageDirectory.resolve(playerUuid.toString() + ".json");
        Path tempPath = storageDirectory.resolve(playerUuid.toString() + ".tmp");

        byte[] data;
        synchronized (progression) {
            data = mapper.writeValueAsBytes(progression);
        }
        Files.write(tempPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        try {
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
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
