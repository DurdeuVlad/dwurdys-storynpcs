package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storynpcs.domain.role.banker.BankVault;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Manages player bank vault persistence with transactional atomic write safety.
 */
public class BankRepository {
    /** Describes a vault mutation before it is committed durably. */
    public record BankMutation<T>(boolean changed, T value) {
        public static <T> BankMutation<T> changed(T value) {
            return new BankMutation<>(true, value);
        }

        public static <T> BankMutation<T> unchanged(T value) {
            return new BankMutation<>(false, value);
        }
    }

    /** Outcome of a mutation, including whether durable commit actually succeeded. */
    public record BankTransactionResult<T>(boolean committed, T value, String failureReason) {
        public static <T> BankTransactionResult<T> committed(T value) {
            return new BankTransactionResult<>(true, value, null);
        }

        public static <T> BankTransactionResult<T> rejected(T value) {
            return new BankTransactionResult<>(false, value, null);
        }

        public static <T> BankTransactionResult<T> failed(T value, String failureReason) {
            return new BankTransactionResult<>(false, value, failureReason);
        }
    }

    private final Path storageDirectory;
    private final ObjectMapper mapper;
    private final DurableJsonStore.FailureInjector failureInjector;
    private final DurableOperationJournal operationJournal;
    private final Map<UUID, BankVault> cache = new ConcurrentHashMap<>();
    /** Players whose durable vault exists but could not be loaded; writes are refused. */
    private final Map<UUID, String> unavailableRecords = new ConcurrentHashMap<>();

    public BankRepository(Path storageDirectory) {
        this(storageDirectory, DurableJsonStore.FailureInjector.none());
    }

    public BankRepository(Path storageDirectory, DurableJsonStore.FailureInjector failureInjector) {
        this.storageDirectory = storageDirectory;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.failureInjector = failureInjector == null
                ? DurableJsonStore.FailureInjector.none()
                : failureInjector;
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not create bank storage directory: " + storageDirectory, e);
        }
        this.operationJournal = new DurableOperationJournal(
                storageDirectory.resolve("operations"), this.failureInjector);
    }

    public DurableOperationJournal operationJournal() {
        return operationJournal;
    }

    public BankVault getOrCreate(UUID playerUuid) {
        return cache.computeIfAbsent(playerUuid, this::loadFromDisk);
    }

    /** True when the player's durable vault is present but unrecoverable; operations fail closed. */
    public boolean isUnavailable(UUID playerUuid) {
        return unavailableRecords.containsKey(playerUuid);
    }

    /** Diagnostic describing why the player's vault is blocked, or null when loadable. */
    public String unavailabilityReason(UUID playerUuid) {
        return unavailableRecords.get(playerUuid);
    }

    private BankVault loadFromDisk(UUID playerUuid) {
        DurableJsonStore store = store(playerUuid);
        try {
            DurableJsonStore.ReadResult<BankVault> result = store.read(BankVault.class);
            reportDiagnostics("bank vault", playerUuid, result);
            if (result.hasValue()) return result.value();
            if (result.sourcePresent() || store.hasProtectedArtifacts()) {
                throw blockRecord(playerUuid,
                        "durable bank vault is unrecoverable; refusing to initialize empty state");
            }
        } catch (IOException e) {
            throw blockRecord(playerUuid,
                    "could not inspect durable bank vault: " + e.getMessage());
        }
        return new BankVault(playerUuid);
    }

    private UnrecoverablePlayerDataException blockRecord(UUID playerUuid, String reason) {
        unavailableRecords.put(playerUuid, reason);
        System.err.println("[StoryNPCs] bank vault blocked for " + playerUuid + ": " + reason);
        return new UnrecoverablePlayerDataException("bank vault", playerUuid, reason);
    }

    public void save(UUID playerUuid) throws IOException {
        String blocked = unavailableRecords.get(playerUuid);
        if (blocked != null) {
            throw new IOException("bank vault write blocked for " + playerUuid + ": " + blocked);
        }
        BankVault vault = cache.get(playerUuid);
        if (vault == null) return;

        synchronized (vault) {
            saveVault(playerUuid, vault);
        }
    }

    /**
     * Persists a specific vault instance — the single durable boundary every
     * write funnels through (kept virtual so fault-injection tests exercise it).
     * Callers that already hold a reference (e.g. {@link #transact} inside
     * {@code synchronized (vault)}) must use this overload: a cache eviction
     * between fetch and write must never turn a committed mutation into a
     * silent no-op.
     */
    void saveVault(UUID playerUuid, BankVault vault) throws IOException {
        String blocked = unavailableRecords.get(playerUuid);
        if (blocked != null) {
            throw new IOException("bank vault write blocked for " + playerUuid + ": " + blocked);
        }
        store(playerUuid).write(vault);
    }

    /**
     * Applies one vault mutation and commits it as a single cached-state /
     * durable-state boundary. A rejected operation is not written. If the
     * write fails, the detached snapshot is restored before the failure is
     * returned, so callers cannot publish or act on an uncommitted mutation.
     */
    public <T> BankTransactionResult<T> transact(
            UUID playerUuid,
            Function<BankVault, BankMutation<T>> operation) {
        if (playerUuid == null || operation == null) {
            return BankTransactionResult.failed(null, "playerUuid and operation are required");
        }

        BankVault vault;
        try {
            vault = getOrCreate(playerUuid);
        } catch (UnrecoverablePlayerDataException unavailable) {
            return BankTransactionResult.failed(null, unavailable.getMessage());
        }
        synchronized (vault) {
            BankVault snapshot = vault.copy();
            final BankMutation<T> mutation;
            try {
                mutation = operation.apply(vault);
            } catch (RuntimeException e) {
                vault.restoreFrom(snapshot);
                return BankTransactionResult.failed(null, "bank mutation failed: " + safeMessage(e));
            }

            if (mutation == null) {
                vault.restoreFrom(snapshot);
                return BankTransactionResult.failed(null, "bank mutation returned null");
            }
            if (!mutation.changed()) {
                vault.restoreFrom(snapshot);
                return BankTransactionResult.rejected(mutation.value());
            }

            try {
                vault.advanceRevision();
                // Persist the mutated instance itself — a cache eviction must
                // never turn this commit into a silent no-op.
                saveVault(playerUuid, vault);
                return BankTransactionResult.committed(mutation.value());
            } catch (IOException | RuntimeException e) {
                vault.restoreFrom(snapshot);
                return BankTransactionResult.failed(mutation.value(),
                        "bank durable commit failed: " + safeMessage(e));
            }
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private DurableJsonStore store(UUID playerUuid) {
        return new DurableJsonStore(storageDirectory.resolve(playerUuid.toString() + ".json"), mapper, failureInjector);
    }

    private void reportDiagnostics(String kind, UUID playerUuid,
                                   DurableJsonStore.ReadResult<?> result) {
        for (String diagnostic : result.diagnostics()) {
            System.err.println("[StoryNPCs] " + kind + " recovery for " + playerUuid + ": " + diagnostic);
        }
    }

    public void unload(UUID playerUuid) {
        if (playerUuid == null) return;
        // Evict under the vault monitor so an in-flight transact finishes its
        // durable write before the instance leaves the cache.
        BankVault vault = cache.get(playerUuid);
        if (vault != null) {
            synchronized (vault) {
                cache.remove(playerUuid, vault);
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
                System.err.println("Error saving bank vault for " + uuid + ": " + e.getMessage());
            }
        }
    }

    public void clearCache() {
        cache.clear();
        unavailableRecords.clear();
    }
}
