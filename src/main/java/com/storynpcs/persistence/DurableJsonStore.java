package com.storynpcs.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Crash-conscious JSON file storage for mutable runtime state.
 *
 * <p>Records are wrapped in a small versioned envelope. Writes serialize to a
 * temporary file, force the bytes, rotate up to three previous generations,
 * and then replace the target. Reads quarantine an invalid target and restore
 * the newest valid backup without affecting other record files.</p>
 */
public final class DurableJsonStore {
    public static final int LEGACY_VERSION = 0;
    public static final int CURRENT_VERSION = 1;
    public static final int BACKUP_RETENTION = 3;

    private static final String VERSION_FIELD = "schemaVersion";
    private static final String DATA_FIELD = "data";

    private final Path target;
    private final ObjectMapper mapper;
    private final FailureInjector failureInjector;

    public DurableJsonStore(Path target, ObjectMapper mapper) {
        this(target, mapper, FailureInjector.none());
    }

    public DurableJsonStore(Path target, ObjectMapper mapper, FailureInjector failureInjector) {
        if (target == null) throw new IllegalArgumentException("target cannot be null");
        if (mapper == null) throw new IllegalArgumentException("mapper cannot be null");
        if (failureInjector == null) throw new IllegalArgumentException("failureInjector cannot be null");
        this.target = target;
        this.mapper = mapper;
        this.failureInjector = failureInjector;
    }

    public Path target() {
        return target;
    }

    public Path backup(int generation) {
        if (generation < 1 || generation > BACKUP_RETENTION) {
            throw new IllegalArgumentException("backup generation must be between 1 and " + BACKUP_RETENTION);
        }
        return target.resolveSibling(target.getFileName() + ".bak." + generation);
    }

    /** Writes one durable version and retains the previous three valid generations. */
    public synchronized void write(Object value) throws IOException {
        if (value == null) throw new IllegalArgumentException("value cannot be null");
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put(VERSION_FIELD, CURRENT_VERSION);
        envelope.set(DATA_FIELD, mapper.valueToTree(value));
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        boolean installed = false;
        try {
            failureInjector.before(FailurePoint.TEMP_WRITE);
            writeAndForce(temporary, bytes);
            failureInjector.before(FailurePoint.BACKUP_ROTATION);
            rotateBackups();
            failureInjector.before(FailurePoint.TARGET_RENAME);
            moveReplace(temporary, target);
            installed = true;
        } finally {
            if (!installed) Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads a record, recovering from the newest valid backup when necessary.
     * A missing or unrecoverable record returns an empty value with diagnostics;
     * callers can then create a domain default without erasing the evidence.
     */
    public synchronized <T> ReadResult<T> read(Class<T> type) throws IOException {
        if (type == null) throw new IllegalArgumentException("type cannot be null");
        List<String> diagnostics = new ArrayList<>();
        boolean sourcePresent = Files.exists(target);
        for (int generation = 1; generation <= BACKUP_RETENTION; generation++) {
            sourcePresent |= Files.exists(backup(generation));
        }

        if (Files.exists(target)) {
            try {
                return new ReadResult<>(decode(target, type), true, false, diagnostics);
            } catch (Exception failure) {
                diagnostics.add("Target " + target + " is invalid: " + messageOf(failure));
                quarantine(target, diagnostics);
            }
        }

        for (int generation = 1; generation <= BACKUP_RETENTION; generation++) {
            Path backup = backup(generation);
            if (!Files.exists(backup)) continue;
            try {
                T value = decode(backup, type);
                try {
                    restoreBackup(backup);
                } catch (IOException restoreFailure) {
                    diagnostics.add("Recovered " + backup + " in memory but could not restore target: "
                            + messageOf(restoreFailure));
                }
                return new ReadResult<>(value, true, true, diagnostics);
            } catch (Exception failure) {
                diagnostics.add("Backup " + backup + " is invalid: " + messageOf(failure));
                quarantine(backup, diagnostics);
            }
        }

        return new ReadResult<>(null, sourcePresent, false, diagnostics);
    }

    private <T> T decode(Path source, Class<T> type) throws IOException {
        JsonNode root;
        try (JsonParser parser = mapper.getFactory().createParser(Files.readAllBytes(source))) {
            root = mapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IOException("record contains multiple JSON values");
            }
        }
        if (root == null || root.isNull()) {
            throw new IOException("record is empty");
        }

        JsonNode payload = root;
        if (root.isObject() && root.has(VERSION_FIELD)) {
            JsonNode versionNode = root.get(VERSION_FIELD);
            if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
                throw new IOException("schemaVersion must be a 32-bit integer");
            }
            int version = versionNode.intValue();
            if (version < LEGACY_VERSION) {
                throw new IOException("schemaVersion cannot be negative");
            }
            if (version > CURRENT_VERSION) {
                throw new IOException("unsupported future schemaVersion " + version
                        + "; highest supported version is " + CURRENT_VERSION);
            }
            if (version == CURRENT_VERSION) {
                payload = root.get(DATA_FIELD);
                if (payload == null) throw new IOException("versioned record is missing data");
                if (payload.isNull()) throw new IOException("versioned record data cannot be null");
            }
            // Version 0 is reserved for raw legacy domain JSON. If a future
            // migration introduces an envelope at v0, it must be explicit.
        }
        return mapper.treeToValue(payload, type);
    }

    private void rotateBackups() throws IOException {
        Path overflow = backup(BACKUP_RETENTION);
        if (Files.exists(overflow)) {
            Files.delete(overflow);
        }
        for (int generation = BACKUP_RETENTION - 1; generation >= 1; generation--) {
            Path current = backup(generation);
            if (Files.exists(current)) {
                moveReplace(current, backup(generation + 1));
            }
        }
        if (Files.exists(target)) {
            moveReplace(target, backup(1));
        }
    }

    private void restoreBackup(Path source) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".recovery.tmp");
        boolean installed = false;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            force(temporary);
            moveReplace(temporary, target);
            installed = true;
        } finally {
            if (!installed) Files.deleteIfExists(temporary);
        }
    }

    private void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            failureInjector.before(FailurePoint.FORCE);
            channel.force(true);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void quarantine(Path source, List<String> diagnostics) {
        if (!Files.exists(source)) return;
        Path quarantine = source.resolveSibling(source.getFileName() + ".corrupted." + Instant.now().toEpochMilli());
        int suffix = 1;
        while (Files.exists(quarantine)) {
            quarantine = source.resolveSibling(source.getFileName() + ".corrupted."
                    + Instant.now().toEpochMilli() + "." + suffix++);
        }
        try {
            moveNoReplace(source, quarantine);
            diagnostics.add("Quarantined invalid record at " + quarantine);
        } catch (IOException failure) {
            diagnostics.add("Could not quarantine invalid record " + source + ": " + messageOf(failure));
        }
    }

    private static void moveNoReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    public record ReadResult<T>(T value, boolean sourcePresent, boolean recovered, List<String> diagnostics) {
        public ReadResult {
            diagnostics = diagnostics == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public boolean hasValue() {
            return value != null;
        }
    }

    public enum FailurePoint {
        TEMP_WRITE,
        FORCE,
        BACKUP_ROTATION,
        TARGET_RENAME
    }

    @FunctionalInterface
    public interface FailureInjector {
        void before(FailurePoint point) throws IOException;

        static FailureInjector none() {
            return point -> { };
        }
    }
}
