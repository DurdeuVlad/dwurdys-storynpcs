package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** Reads one operation record without changing it. */
    public synchronized OperationRecord read(UUID operationId) throws IOException {
        if (operationId == null) throw new IllegalArgumentException("operationId cannot be null");
        return readExisting(recordPath(operationId));
    }

    /** Returns pending operations in deterministic ID order for recovery services. */
    public synchronized List<OperationRecord> pending() throws IOException {
        return pendingForSubject(null);
    }

    /** Returns only pending operations owned by one subject, or all when subject is null. */
    public synchronized List<OperationRecord> pendingForSubject(String subject) throws IOException {
        if (subject != null) validateRequiredText(subject, "subject", MAX_SUBJECT_LENGTH);
        if (!Files.exists(storageDirectory)) return List.of();
        List<OperationRecord> pending = new ArrayList<>();
        try (var paths = Files.list(storageDirectory)) {
            for (Path path : paths
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                OperationRecord record = readExisting(path);
                if (record != null && record.state() == State.PREPARED
                        && (subject == null || subject.equals(record.subject()))) pending.add(record);
                if (pending.size() > MAX_PENDING_RECORDS) {
                    throw new IOException("operation journal contains more than " + MAX_PENDING_RECORDS
                            + " pending records");
                }
            }
        }
        return List.copyOf(pending);
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

    private static void verifyIdentity(OperationRecord existing, UUID operationId,
                                       String operationType, String subject) throws IOException {
        if (!operationId.equals(existing.operationId())
                || !operationType.equals(existing.operationType())
                || !subject.equals(existing.subject())) {
            throw new IOException("operation ID is already bound to a different operation identity: " + operationId);
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
