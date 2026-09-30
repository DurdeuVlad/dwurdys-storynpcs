package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.progression.PlayerProgression;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player progression persistence with atomic write safety:
 * writes to .tmp, flushes, and atomically moves to target file.
 */
public class ProgressionRepository {
    private static final int PROGRESSION_LOCK_STRIPES = 256;

    public record FactionProgressionSnapshot(boolean hasFactionPoints, int factionPoints, long revision) {}

    @FunctionalInterface
    public interface ProgressionOperation {
        void apply(PlayerProgression progression) throws IOException;
    }

    private final Path storageDirectory;
    private final ObjectMapper mapper;
    private final Object[] progressionLocks = createProgressionLocks();
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
        synchronized (progressionLock(playerUuid)) {
            return cache.computeIfAbsent(playerUuid, this::loadFromDisk);
        }
    }

    public void withProgression(UUID playerUuid, ProgressionOperation operation) throws IOException {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid cannot be null");
        if (operation == null) throw new IllegalArgumentException("operation cannot be null");
        synchronized (progressionLock(playerUuid)) {
            PlayerProgression progression = cache.get(playerUuid);
            if (progression == null) progression = loadFromDisk(playerUuid);
            synchronized (progression) {
                operation.apply(progression);
            }
        }
    }

    private Object progressionLock(UUID playerUuid) {
        return progressionLocks[Math.floorMod(playerUuid.hashCode(), progressionLocks.length)];
    }

    private static Object[] createProgressionLocks() {
        Object[] locks = new Object[PROGRESSION_LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    /** Root used by sibling runtime stores that share the world persistence lifecycle. */
    public Path storageDirectory() {
        return storageDirectory;
    }

    public Map<UUID, FactionProgressionSnapshot> factionProgressionSnapshots(NamespacedId factionId)
            throws IOException {
        if (factionId == null) throw new IllegalArgumentException("factionId cannot be null");
        Set<UUID> playerUuids = new HashSet<>();
        try (var paths = Files.list(storageDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                int jsonIndex = name.indexOf(".json");
                if (jsonIndex < 0) continue;
                String suffix = name.substring(jsonIndex + ".json".length());
                if (!suffix.isEmpty() && !suffix.startsWith(".bak.")
                        && !suffix.startsWith(".corrupted.")) continue;
                String playerText = name.substring(0, jsonIndex);
                UUID playerUuid;
                try {
                    playerUuid = UUID.fromString(playerText);
                } catch (IllegalArgumentException invalidName) {
                    throw new IOException("cannot identify progression owner for durable artifact " + name,
                            invalidName);
                }
                if (!playerUuid.toString().equals(playerText)) {
                    throw new IOException("non-canonical progression record name: " + name);
                }
                playerUuids.add(playerUuid);
            }
        }
        playerUuids.addAll(cache.keySet());

        Map<UUID, FactionProgressionSnapshot> snapshots = new LinkedHashMap<>();
        for (UUID playerUuid : playerUuids.stream().sorted().toList()) {
            synchronized (progressionLock(playerUuid)) {
                PlayerProgression progression = cache.get(playerUuid);
                if (progression == null) {
                    DurableJsonStore durableStore = store(playerUuid);
                    try {
                        DurableJsonStore.ReadResult<PlayerProgression> result =
                                durableStore.read(PlayerProgression.class);
                        reportDiagnostics("progression", playerUuid, result);
                        if (result.hasValue()) {
                            progression = result.value();
                        } else if (result.sourcePresent() || durableStore.hasProtectedArtifacts()) {
                            throw new IOException("durable progression record is unrecoverable");
                        } else {
                            continue;
                        }
                    } catch (IOException failure) {
                        throw new IOException("cannot inspect faction progression for player " + playerUuid
                                + ": " + failure.getMessage(), failure);
                    }
                }
                synchronized (progression) {
                    if (!playerUuid.equals(progression.getPlayerUuid())) {
                        throw new IOException("progression record owner mismatch for player " + playerUuid);
                    }
                    Map<NamespacedId, Integer> factionPoints = progression.getFactionPoints();
                    if (factionPoints == null) {
                        throw new IOException("faction reputation map is unavailable for player " + playerUuid);
                    }
                    boolean hasFactionPoints = factionPoints.containsKey(factionId);
                    Integer points = factionPoints.get(factionId);
                    if (hasFactionPoints && points == null) {
                        throw new IOException("faction reputation is invalid for player " + playerUuid);
                    }
                    snapshots.put(playerUuid, new FactionProgressionSnapshot(
                            hasFactionPoints, hasFactionPoints ? points : 0, progression.getFactionRevision()));
                }
            }
        }
        return Collections.unmodifiableMap(snapshots);
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
        synchronized (progressionLock(playerUuid)) {
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
