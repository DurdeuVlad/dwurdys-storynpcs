package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Durable lifecycle records for multi-step operations.
 *
 * <p>This class records the operation boundary; it does not pretend that a
 * Minecraft inventory, world mutation, and file write become one atomic
 * system automatically. A {@link State#PREPARED} record is explicit pending
 * work that an operation-specific recovery handler must resolve.</p>
 */
public final class DurableOperationJournal {
    private static final int OPERATION_LOCK_STRIPES = 64;

    public static final int MAX_OPERATION_TYPE_LENGTH = 128;
    public static final int MAX_SUBJECT_LENGTH = 256;
    public static final int MAX_OUTCOME_CODE_LENGTH = 128;
    public static final int MAX_DETAIL_LENGTH = 4096;
    public static final int MAX_PENDING_RECORDS = 4096;

    /**
     * Terminal records at least this old are eligible for the default
     * retention sweep; prepared records are never pruned.
     */
    public static final long DEFAULT_TERMINAL_RETENTION_MILLIS = Duration.ofDays(30).toMillis();

    /**
     * The newest terminal records always retained, so a sweep never erases
     * the most recent audit trail even when every record is past the cutoff.
     */
    public static final int MIN_TERMINAL_RECORDS_RETAINED = 256;

    private final Path storageDirectory;
    private final ObjectMapper mapper;
    private final DurableJsonStore.FailureInjector failureInjector;
    private final Object[] operationLocks = createOperationLocks();

    public DurableOperationJournal(Path storageDirectory) {
        this(storageDirectory, DurableJsonStore.FailureInjector.none());
    }

    public DurableOperationJournal(Path storageDirectory,
                                   DurableJsonStore.FailureInjector failureInjector) {
        if (storageDirectory == null) throw new IllegalArgumentException("storageDirectory cannot be null");
        this.storageDirectory = storageDirectory;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.failureInjector = failureInjector == null
                ? DurableJsonStore.FailureInjector.none()
                : failureInjector;
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Could not create operation journal directory: " + storageDirectory, e);
        }
    }

    public enum State {
        PREPARED,
        COMMITTED,
        ABORTED
    }

    public enum BeginStatus {
        STARTED,
        COMMITTED,
        ABORTED,
        PENDING
    }

    /**
     * Runs one request-ID lifecycle decision under a bounded in-process lock.
     * Withdrawal execution and recovery must share this lock so recovery cannot
     * abort a request between its durable prepare and bank commit decision.
     */
    public <T> T withOperationLock(UUID operationId, Supplier<T> action) {
        if (operationId == null) throw new IllegalArgumentException("operationId cannot be null");
        if (action == null) throw new IllegalArgumentException("action cannot be null");
        Object lock = operationLocks[Math.floorMod(operationId.hashCode(), operationLocks.length)];
        synchronized (lock) {
            return action.get();
        }
    }

    private static Object[] createOperationLocks() {
        Object[] locks = new Object[OPERATION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }

    public record OperationRecord(
            UUID operationId,
            String operationType,
            String subject,
            State state,
            String outcomeCode,
            String detail,
            long createdAtEpochMillis,
            long updatedAtEpochMillis,
            String preparedIntent
    ) {
        /** Compatibility constructor for callers that do not have a prepared intent. */
        public OperationRecord(UUID operationId, String operationType, String subject,
                               State state, String outcomeCode, String detail,
                               long createdAtEpochMillis, long updatedAtEpochMillis) {
            this(operationId, operationType, subject, state, outcomeCode, detail,
                    createdAtEpochMillis, updatedAtEpochMillis, null);
        }
    }

    public record BeginResult(BeginStatus status, OperationRecord record) {}

    /** Starts a new operation or classifies an existing operation ID for replay/recovery. */
    public synchronized BeginResult begin(UUID operationId, String operationType, String subject)
            throws IOException {
        return begin(operationId, operationType, subject, null);
    }

    /**
     * Starts an operation with bounded recovery intent. The intent is retained
     * while the record is {@link State#PREPARED}; operation adapters should put
     * only validated, non-secret data here.
     */
    public synchronized BeginResult begin(UUID operationId, String operationType,
                                          String subject, String preparedIntent)
            throws IOException {
        requireIdentity(operationId, operationType, subject);
        validateOptionalText(preparedIntent, "preparedIntent", MAX_DETAIL_LENGTH);
        Path recordPath = recordPath(operationId);
        return withRecordLock(recordPath, () -> {
            OperationRecord existing = readExisting(recordPath);
            if (existing != null) {
                verifyIdentity(existing, operationId, operationType, subject);
                return new BeginResult(statusOf(existing.state()), existing);
            }

            long now = System.currentTimeMillis();
            OperationRecord prepared = new OperationRecord(
                    operationId, operationType, subject, State.PREPARED,
                    null, preparedIntent, now, now, preparedIntent);
            write(recordPath, prepared);
            return new BeginResult(BeginStatus.STARTED, prepared);
        });
    }

    /** Marks a prepared operation committed. Repeating the same transition is safe. */
    public synchronized OperationRecord commit(UUID operationId, String outcomeCode, String detail)
            throws IOException {
        validateOptionalText(outcomeCode, "outcomeCode", MAX_OUTCOME_CODE_LENGTH);
        validateOptionalText(detail, "detail", MAX_DETAIL_LENGTH);
        return transition(operationId, State.COMMITTED, outcomeCode, detail);
    }

    /** Marks a prepared operation aborted. Repeating the same transition is safe. */
    public synchronized OperationRecord abort(UUID operationId, String outcomeCode, String detail)
            throws IOException {
        validateOptionalText(outcomeCode, "outcomeCode", MAX_OUTCOME_CODE_LENGTH);
        validateOptionalText(detail, "detail", MAX_DETAIL_LENGTH);
        return transition(operationId, State.ABORTED, outcomeCode, detail);
    }

    /**
     * Reads one operation record without changing it.
     *
     * <p>Fail-closed note: when no live record exists but a quarantined
     * {@code .corrupted.} artifact does, this throws — that operation id stays
     * poisoned until an operator inspects and removes the artifact (or restores
     * a valid record file) under the journal directory. There is intentionally
     * no automated un-quarantine path.
     */
    public synchronized OperationRecord read(UUID operationId) throws IOException {
        if (operationId == null) throw new IllegalArgumentException("operationId cannot be null");
        return readExisting(recordPath(operationId));
    }

    /** Pending recovery scan: readable PREPARED records plus per-record diagnostics. */
    public record PendingScan(List<OperationRecord> records, List<String> diagnostics) {
        public PendingScan {
            records = records == null ? List.of() : List.copyOf(records);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * Returns pending operations in deterministic filename order for recovery
     * services. A record that cannot be decoded or fails field validation is
     * quarantined (preserved as {@code .corrupted.*} evidence) and reported
     * through {@link PendingScan#diagnostics()} — one invalid record must not
     * block recovery of every other pending operation.
     */
    public synchronized PendingScan pending() throws IOException {
        return pendingForSubject(null);
    }

    /** Returns only pending operations owned by one subject, or all when subject is null. */
    public synchronized PendingScan pendingForSubject(String subject) throws IOException {
        if (subject != null) validateRequiredText(subject, "subject", MAX_SUBJECT_LENGTH);
        List<String> diagnostics = new ArrayList<>();
        if (!Files.exists(storageDirectory)) return new PendingScan(List.of(), diagnostics);
        List<OperationRecord> pending = new ArrayList<>();
        try (var paths = Files.list(storageDirectory)) {
            for (Path path : paths
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                OperationRecord record;
                try {
                    record = readExisting(path);
                } catch (IOException | RuntimeException invalid) {
                    diagnostics.add("Skipped invalid journal record " + path + ": " + invalid.getMessage());
                    DurableJsonStore.quarantine(path, diagnostics);
                    continue;
                }
                if (record != null && record.state() == State.PREPARED
                        && (subject == null || subject.equals(record.subject()))) pending.add(record);
                if (pending.size() > MAX_PENDING_RECORDS) {
                    throw new IOException("operation journal contains more than " + MAX_PENDING_RECORDS
                            + " pending records");
                }
            }
        }
        return new PendingScan(List.copyOf(pending), List.copyOf(diagnostics));
    }

    /**
     * Applies the default retention policy: terminal records older than
     * {@link #DEFAULT_TERMINAL_RETENTION_MILLIS} are deleted once more than
     * {@link #MIN_TERMINAL_RECORDS_RETAINED} newer terminal records remain.
     *
     * @return the number of record files deleted
     */
    public synchronized int pruneTerminalRecords() throws IOException {
        return pruneTerminalRecords(DEFAULT_TERMINAL_RETENTION_MILLIS, MIN_TERMINAL_RECORDS_RETAINED);
    }

    /**
     * Deletes terminal (committed/aborted) record files that are both older
     * than {@code maxAgeMillis} and beyond the {@code minRetained} newest
     * terminal records, together with each record's {@code .lock} file and
     * rotated {@code .bak.*} generations. {@link State#PREPARED} records,
     * quarantined {@code .corrupted.*} artifacts, and unreadable record files
     * are never touched — they may be evidence for manual recovery. Orphaned
     * {@code .lock} files whose record is gone are removed once they age past
     * the same cutoff; fresh orphans are left alone because another process
     * may be mid-{@link #begin} on them. Individual delete failures are
     * skipped so a locked file cannot abort the sweep.
     *
     * @return the number of record files deleted
     */
    public synchronized int pruneTerminalRecords(long maxAgeMillis, int minRetained) throws IOException {
        if (maxAgeMillis < 0) throw new IllegalArgumentException("maxAgeMillis cannot be negative");
        if (minRetained < 0) throw new IllegalArgumentException("minRetained cannot be negative");
        if (!Files.exists(storageDirectory)) return 0;
        long cutoff = System.currentTimeMillis() - maxAgeMillis;

        Map<String, Path> filesByName = new HashMap<>();
        try (var paths = Files.list(storageDirectory)) {
            for (Path path : paths.toList()) {
                filesByName.put(path.getFileName().toString(), path);
            }
        }

        List<OperationRecord> terminal = new ArrayList<>();
        for (Map.Entry<String, Path> entry : filesByName.entrySet()) {
            if (!entry.getKey().endsWith(".json")) continue;
            OperationRecord record;
            try {
                record = readExisting(entry.getValue());
            } catch (IOException | RuntimeException unreadable) {
                continue; // a corrupt record may be evidence — never auto-delete
            }
            if (record != null && record.state() != State.PREPARED) terminal.add(record);
        }
        terminal.sort(Comparator.comparingLong(OperationRecord::updatedAtEpochMillis).reversed());

        int deleted = 0;
        for (int index = minRetained; index < terminal.size(); index++) {
            OperationRecord record = terminal.get(index);
            if (record.updatedAtEpochMillis() > cutoff) continue;
            String baseName = record.operationId() + ".json";
            if (!deleteQuietly(filesByName.get(baseName))) continue;
            deleteQuietly(filesByName.get(baseName + ".lock"));
            for (Map.Entry<String, Path> entry : filesByName.entrySet()) {
                String name = entry.getKey();
                if (name.startsWith(baseName + ".bak.") && !name.contains(".corrupted.")) {
                    deleteQuietly(entry.getValue());
                }
            }
            deleted++;
        }

        String lockSuffix = ".lock";
        for (Map.Entry<String, Path> entry : filesByName.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".json.lock")) continue;
            Path owner = entry.getValue().resolveSibling(
                    name.substring(0, name.length() - lockSuffix.length()));
            if (filesByName.containsKey(owner.getFileName().toString())) continue;
            try {
                if (Files.getLastModifiedTime(entry.getValue()).toMillis() <= cutoff) {
                    deleteQuietly(entry.getValue());
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        return deleted;
    }

    private static boolean deleteQuietly(Path path) {
        if (path == null) return false;
        try {
            return Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private OperationRecord transition(UUID operationId, State targetState,
                                       String outcomeCode, String detail) throws IOException {
        if (operationId == null) throw new IllegalArgumentException("operationId cannot be null");
        Path recordPath = recordPath(operationId);
        return withRecordLock(recordPath, () -> {
            OperationRecord existing = readExisting(recordPath);
            if (existing == null) {
                throw new IOException("operation journal record does not exist: " + operationId);
            }
            if (existing.state() == targetState) return existing;
            if (existing.state() != State.PREPARED) {
                throw new IOException("operation " + operationId + " is already " + existing.state()
                        + " and cannot transition to " + targetState);
            }
            OperationRecord transitioned = new OperationRecord(
                    existing.operationId(), existing.operationType(), existing.subject(), targetState,
                    outcomeCode, detail, existing.createdAtEpochMillis(), System.currentTimeMillis(),
                    existing.preparedIntent() != null
                            ? existing.preparedIntent() : existing.detail());
            write(recordPath, transitioned);
            return transitioned;
        });
    }

    private OperationRecord readExisting(Path recordPath) throws IOException {
        DurableJsonStore.ReadResult<OperationRecord> result = store(recordPath).read(OperationRecord.class);
        if (result.hasValue()) {
            validateRecord(result.value(), recordPath);
            return result.value();
        }
        if (result.sourcePresent() || hasQuarantinedArtifact(recordPath)) {
            throw new IOException("operation journal record is invalid: " + recordPath
                    + " (" + String.join("; ", result.diagnostics()) + ")");
        }
        return null;
    }

    private boolean hasQuarantinedArtifact(Path recordPath) throws IOException {
        Path parent = recordPath.getParent();
        if (parent == null || !Files.exists(parent)) return false;
        String base = recordPath.getFileName().toString();
        try (var paths = Files.list(parent)) {
            return paths.anyMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(base + ".corrupted.")
                        || (name.startsWith(base + ".bak.") && name.contains(".corrupted."));
            });
        }
    }

    private static void validateRecord(OperationRecord record, Path recordPath) throws IOException {
        if (record == null || record.operationId() == null || record.state() == null) {
            throw new IOException("operation journal record has missing identity/state: " + recordPath);
        }
        try {
            validateRequiredText(record.operationType(), "operationType", MAX_OPERATION_TYPE_LENGTH);
            validateRequiredText(record.subject(), "subject", MAX_SUBJECT_LENGTH);
            validateOptionalText(record.outcomeCode(), "outcomeCode", MAX_OUTCOME_CODE_LENGTH);
            validateOptionalText(record.detail(), "detail", MAX_DETAIL_LENGTH);
            validateOptionalText(record.preparedIntent(), "preparedIntent", MAX_DETAIL_LENGTH);
        } catch (IllegalArgumentException e) {
            throw new IOException("operation journal record has invalid text: " + recordPath, e);
        }
        if (record.createdAtEpochMillis() < 0 || record.updatedAtEpochMillis() < record.createdAtEpochMillis()) {
            throw new IOException("operation journal record has invalid timestamps: " + recordPath);
        }
    }

    private void write(Path recordPath, OperationRecord record) throws IOException {
        store(recordPath).write(record);
    }

    private DurableJsonStore store(Path recordPath) {
        return new DurableJsonStore(recordPath, mapper, failureInjector);
    }

    private Path recordPath(UUID operationId) {
        return storageDirectory.resolve(operationId + ".json");
    }

    private <T> T withRecordLock(Path recordPath, IoSupplier<T> operation) throws IOException {
        Path lockPath = recordPath.resolveSibling(recordPath.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.get();
        }
    }

    private static BeginStatus statusOf(State state) {
        return switch (state) {
            case PREPARED -> BeginStatus.PENDING;
            case COMMITTED -> BeginStatus.COMMITTED;
            case ABORTED -> BeginStatus.ABORTED;
        };
    }

    private static void requireIdentity(UUID operationId, String operationType, String subject) {
        if (operationId == null) throw new IllegalArgumentException("operationId cannot be null");
        validateRequiredText(operationType, "operationType", MAX_OPERATION_TYPE_LENGTH);
        validateRequiredText(subject, "subject", MAX_SUBJECT_LENGTH);
    }

    /**
     * Thrown when an operation ID is reused with a different operation type or
     * subject — a request-identity conflict, distinct from storage failures.
     */
    public static final class OperationIdentityMismatchException extends IOException {
        public OperationIdentityMismatchException(String message) {
            super(message);
        }
    }

    private static void verifyIdentity(OperationRecord existing, UUID operationId,
                                       String operationType, String subject) throws IOException {
        if (!operationId.equals(existing.operationId())
                || !operationType.equals(existing.operationType())
                || !subject.equals(existing.subject())) {
            throw new OperationIdentityMismatchException(
                    "operation ID is already bound to a different operation identity: " + operationId);
        }
    }

    private static void validateRequiredText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        validateOptionalText(value, name, maxLength);
    }

    private static void validateOptionalText(String value, String name, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
