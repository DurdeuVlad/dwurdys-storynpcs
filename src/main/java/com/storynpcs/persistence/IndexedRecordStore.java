package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Indexed world-scope record store: a directory of {@link DurableJsonStore}
 * record files plus a durable index file tracking id → file.
 *
 * <p>The index is an accelerator and reconciliation oracle, not the source of
 * truth — record file names are derived deterministically from their ids and
 * each record payload embeds its id, so a missing, corrupt, or stale index is
 * rebuilt from a directory scan on {@link #open()}. A crash between the record
 * commit and the index commit (the {@code INDEX_UPDATE} stage) therefore leaves
 * a stale index that recovers the last valid commit on next open; a corrupt
 * record file never erases unrelated records.</p>
 */
public final class IndexedRecordStore {
    private static final String INDEX_NAME = "_index.json";
    private static final String RECORD_PREFIX = "record-";
    private static final String RECORD_SUFFIX = ".json";

    /** Record payload: the id is stored inside the file so a directory scan can rebuild the index. */
    private record StoredRecord(String id, JsonNode value) {}

    /** Index payload: generation is observational; reconciliation compares file sets. */
    private record IndexSnapshot(long generation, Map<String, String> files) {}

    /** Result of opening/reconciling the index against the record directory. */
    public record ReconcileResult(int recordCount, boolean indexRebuilt, List<String> diagnostics) {
        public ReconcileResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private final Path directory;
    private final ObjectMapper mapper;
    private final DurableJsonStore.FailureInjector failureInjector;
    private final DurableJsonStore indexStore;
    private final Map<String, String> files = new LinkedHashMap<>();
    private long indexGeneration;
    private boolean opened;

    public IndexedRecordStore(Path directory, ObjectMapper mapper) {
        this(directory, mapper, DurableJsonStore.FailureInjector.none());
    }

    public IndexedRecordStore(Path directory, ObjectMapper mapper,
                              DurableJsonStore.FailureInjector failureInjector) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
        this.indexStore = new DurableJsonStore(directory.resolve(INDEX_NAME), mapper, failureInjector);
    }

    /**
     * Reconciles the durable index with the record files actually present.
     * Every record file that decodes is indexed; a record that fails to decode
     * is reported and left untouched — it does not erase unrelated records.
     */
    public synchronized ReconcileResult open() throws IOException {
        Files.createDirectories(directory);
        List<String> diagnostics = new ArrayList<>();
        Map<String, String> scanned = scanRecords(diagnostics);

        var indexRead = indexStore.read(IndexSnapshot.class);
        diagnostics.addAll(indexRead.diagnostics());
        Map<String, String> indexed = indexRead.hasValue() && indexRead.value().files() != null
                ? indexRead.value().files() : Map.of();
        indexGeneration = indexRead.hasValue() ? indexRead.value().generation() : 0;

        files.clear();
        files.putAll(scanned);
        boolean rebuilt = !scanned.equals(indexed);
        if (rebuilt) {
            diagnostics.add("Index rebuilt from " + scanned.size() + " record file(s); prior index "
                    + (indexRead.hasValue() ? "listed " + indexed.size() : "was missing or invalid"));
            indexStore.write(new IndexSnapshot(++indexGeneration, Map.copyOf(files)));
        }
        opened = true;
        return new ReconcileResult(files.size(), rebuilt, List.copyOf(diagnostics));
    }

    /** Commits a record, then updates the durable index (INDEX_UPDATE stage). */
    public synchronized void write(String id, Object value) throws IOException {
        requireOpened();
        requireId(id);
        Objects.requireNonNull(value, "value");
        recordStore(id).write(new StoredRecord(id, mapper.valueToTree(value)));
        failureInjector.before(DurableJsonStore.FailurePoint.INDEX_UPDATE);
        files.put(id, fileNameOf(id));
        indexStore.write(new IndexSnapshot(++indexGeneration, Map.copyOf(files)));
    }

    /** Reads a record by id. Independent of the index — the file name is deterministic. */
    public synchronized <T> Optional<T> read(String id, Class<T> type) throws IOException {
        requireOpened();
        requireId(id);
        var read = recordStore(id).read(StoredRecord.class);
        if (!read.hasValue() || read.value().id() == null || !read.value().id().equals(id)
                || read.value().value() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.treeToValue(read.value().value(), type));
    }

    /**
     * Atomic read-modify-write under the store monitor: the read, update
     * function, and conditional write execute as one critical section, so no
     * concurrent caller can interleave between the check and the commit. The
     * function returns the replacement value, or {@code null} to decline the
     * update (leaving the record untouched).
     */
    public synchronized <T> Optional<T> computeIfPresent(
            String id, Class<T> type, java.util.function.UnaryOperator<T> update) throws IOException {
        Optional<T> existing = read(id, type);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        T updated = update.apply(existing.get());
        if (updated == null) {
            return Optional.empty();
        }
        write(id, updated);
        return Optional.of(updated);
    }

    /** True when quarantined index artifacts remain — evidence a prior index was corrupt. */
    public boolean hasProtectedIndexArtifacts() throws IOException {
        return indexStore.hasProtectedArtifacts();
    }

    /** Indexed ids (as reconciled by {@link #open()} or updated by {@link #write}). */
    public synchronized List<String> listIds() {
        requireOpened();
        return List.copyOf(files.keySet());
    }

    /** Deletes a record file and its backups, then updates the durable index. */
    public synchronized boolean delete(String id) throws IOException {
        requireOpened();
        requireId(id);
        Path target = directory.resolve(fileNameOf(id));
        boolean existed = Files.deleteIfExists(target);
        var recordStore = recordStore(id);
        for (int generation = 1; generation <= DurableJsonStore.BACKUP_RETENTION; generation++) {
            existed |= Files.deleteIfExists(recordStore.backup(generation));
        }
        existed |= files.remove(id) != null;
        failureInjector.before(DurableJsonStore.FailurePoint.INDEX_UPDATE);
        indexStore.write(new IndexSnapshot(++indexGeneration, Map.copyOf(files)));
        return existed;
    }

    private Map<String, String> scanRecords(List<String> diagnostics) throws IOException {
        Map<String, String> scanned = new TreeMap<>();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(RECORD_PREFIX) && name.endsWith(RECORD_SUFFIX);
                    })
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {
                try {
                    var read = new DurableJsonStore(file, mapper, failureInjector).read(StoredRecord.class);
                    diagnostics.addAll(read.diagnostics());
                    String embeddedId = read.hasValue() ? read.value().id() : null;
                    if (embeddedId == null || embeddedId.isBlank()) {
                        diagnostics.add("Skipped record file without a readable id: " + file);
                        continue;
                    }
                    String fileName = file.getFileName().toString();
                    if (!fileNameOf(embeddedId).equals(fileName)) {
                        // The index must never point an id at a name read(id) cannot resolve.
                        diagnostics.add("Skipped record file " + file + " whose embedded id "
                                + embeddedId + " belongs in " + fileNameOf(embeddedId));
                        continue;
                    }
                    if (scanned.putIfAbsent(embeddedId, fileName) != null) {
                        diagnostics.add("Skipped record file " + file
                                + " duplicating already-indexed id " + embeddedId);
                    }
                } catch (Exception failure) {
                    diagnostics.add("Skipped invalid record file " + file + ": " + failure.getMessage());
                }
            }
        }
        return scanned;
    }

    private DurableJsonStore recordStore(String id) {
        return new DurableJsonStore(directory.resolve(fileNameOf(id)), mapper, failureInjector);
    }

    /**
     * Deterministic record file name: sanitized prefix plus a 64-bit SHA-256
     * suffix. {@code String.hashCode()} (32-bit) makes unrelated id collisions a
     * realistic silent-overwrite risk at large record counts; 64 bits of SHA-256
     * keeps the birthday bound far beyond any reachable store size while
     * remaining stable across JVMs and runs.
     */
    private static String fileNameOf(String id) {
        String sanitized = id.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > 24) sanitized = sanitized.substring(0, 24);
        return RECORD_PREFIX + sanitized + "-" + hashSuffix(id) + RECORD_SUFFIX;
    }

    private static String hashSuffix(String id) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (digest[i] & 0xFF);
            return Long.toHexString(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void requireOpened() {
        if (!opened) throw new IllegalStateException("IndexedRecordStore must be opened before use");
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("record id is required");
    }
}
