package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storynpcs.domain.role.banker.BankVault;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player bank vault persistence with transactional atomic write safety.
 */
public class BankRepository {
    private final Path storageDirectory;
    private final ObjectMapper mapper;
    private final Map<UUID, BankVault> cache = new ConcurrentHashMap<>();

    public BankRepository(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not create bank storage directory: " + storageDirectory, e);
        }
    }

    public BankVault getOrCreate(UUID playerUuid) {
        return cache.computeIfAbsent(playerUuid, this::loadFromDisk);
    }

    private BankVault loadFromDisk(UUID playerUuid) {
        Path filePath = storageDirectory.resolve(playerUuid.toString() + ".json");
        if (Files.exists(filePath)) {
            try {
                return mapper.readValue(Files.readAllBytes(filePath), BankVault.class);
            } catch (IOException e) {
                Path backupPath = storageDirectory.resolve(playerUuid.toString() + ".corrupted." + System.currentTimeMillis());
                try {
                    Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("Corrupted bank vault for " + playerUuid + " backed up to: " + backupPath);
                } catch (IOException backupEx) {
                    System.err.println("Failed to backup corrupted bank vault: " + backupEx.getMessage());
                }
            }
        }
        return new BankVault(playerUuid);
    }

    public void save(UUID playerUuid) throws IOException {
        BankVault vault = cache.get(playerUuid);
        if (vault == null) return;

        Path targetPath = storageDirectory.resolve(playerUuid.toString() + ".json");
        Path tempPath = storageDirectory.resolve(playerUuid.toString() + ".tmp");

        byte[] data;
        synchronized (vault) {
            data = mapper.writeValueAsBytes(vault);
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
                System.err.println("Error saving bank vault for " + uuid + ": " + e.getMessage());
            }
        }
    }

    public void clearCache() {
        cache.clear();
    }
}