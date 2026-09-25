package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DurableJsonStoreTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesVersionedRecordAndRetainsThreePreviousGenerations() throws Exception {
        DurableJsonStore store = store("state.json");

        for (int value = 0; value <= 4; value++) {
            store.write(Map.of("value", value));
        }

        assertThat(mapper.readTree(Files.readString(store.target())).get("schemaVersion").intValue())
                .isEqualTo(DurableJsonStore.CURRENT_VERSION);
        assertThat(Files.exists(store.backup(1))).isTrue();
        assertThat(Files.exists(store.backup(2))).isTrue();
        assertThat(Files.exists(store.backup(3))).isTrue();
        assertThat(Files.exists(store.target().resolveSibling("state.json.bak.4"))).isFalse();
    }

    @Test
    void readsLegacyRawRecordAndMigratesOnNextWrite() throws Exception {
        DurableJsonStore store = store("legacy.json");
        Files.writeString(store.target(), "{\"value\":\"legacy\"}");

        DurableJsonStore.ReadResult<Map> loaded = store.read(Map.class);
        assertThat(loaded.value()).containsEntry("value", "legacy");
        assertThat(loaded.recovered()).isFalse();

        store.write(loaded.value());
        assertThat(mapper.readTree(Files.readString(store.target())).get("schemaVersion").intValue())
                .isEqualTo(DurableJsonStore.CURRENT_VERSION);
    }

    @Test
    void quarantinesCorruptTargetAndRestoresNewestValidBackup() throws Exception {
        DurableJsonStore store = store("recoverable.json");
        store.write(Map.of("value", "good"));
        store.write(Map.of("value", "newer"));
        Files.writeString(store.target(), "{broken");

        DurableJsonStore.ReadResult<Map> loaded = store.read(Map.class);

        assertThat(loaded.value()).containsEntry("value", "good");
        assertThat(loaded.recovered()).isTrue();
        assertThat(loaded.diagnostics()).anyMatch(message -> message.contains("Quarantined invalid record"));
        assertThat(Files.readString(store.target())).contains("good");
        try (var files = Files.list(tempDir)) {
            assertThat(files).anyMatch(path -> path.getFileName().toString().contains("recoverable.json.corrupted."));
        }
    }

    @Test
    void refusesFutureSchemaAndPreservesDiagnosticEvidence() throws Exception {
        DurableJsonStore store = store("future.json");
        Files.writeString(store.target(), "{\"schemaVersion\":99,\"data\":{\"value\":\"future\"}}");

        DurableJsonStore.ReadResult<Map> loaded = store.read(Map.class);

        assertThat(loaded.value()).isNull();
        assertThat(loaded.sourcePresent()).isTrue();
        assertThat(loaded.diagnostics()).anyMatch(message -> message.contains("unsupported future schemaVersion 99"));
        try (var files = Files.list(tempDir)) {
            assertThat(files).anyMatch(path -> path.getFileName().toString().contains("future.json.corrupted."));
        }
    }

    @Test
    void rejectsConcatenatedJsonValuesInsteadOfReadingOnlyTheFirst() throws Exception {
        DurableJsonStore store = store("multiple.json");
        Files.writeString(store.target(), "{\"value\":1}{\"value\":2}");

        DurableJsonStore.ReadResult<Map> loaded = store.read(Map.class);

        assertThat(loaded.value()).isNull();
        assertThat(loaded.diagnostics()).anyMatch(message -> message.contains("multiple JSON values"));
    }

    @Test
    void rejectsNullVersionedPayload() throws Exception {
        DurableJsonStore store = store("null-data.json");
        Files.writeString(store.target(), "{\"schemaVersion\":1,\"data\":null}");

        DurableJsonStore.ReadResult<Map> loaded = store.read(Map.class);

        assertThat(loaded.value()).isNull();
        assertThat(loaded.diagnostics()).anyMatch(message -> message.contains("data cannot be null"));
    }

    @Test
    void failedForceLeavesPreviousGenerationAndCleansTemporaryFile() throws Exception {
        DurableJsonStore stable = store("force.json");
        stable.write(Map.of("value", "stable"));
        DurableJsonStore failing = new DurableJsonStore(
                stable.target(), mapper,
                point -> {
                    if (point == DurableJsonStore.FailurePoint.FORCE) {
                        throw new java.io.IOException("injected force failure");
                    }
                });

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.write(Map.of("value", "new")));

        assertThat(Files.readString(stable.target())).contains("stable");
        assertThat(Files.exists(stable.target().resolveSibling("force.json.tmp"))).isFalse();
    }

    @Test
    void failedTemporaryWriteLeavesExistingTargetUntouched() throws Exception {
        DurableJsonStore stable = store("write.json");
        stable.write(Map.of("value", "stable"));
        DurableJsonStore failing = failingAt(stable.target(), DurableJsonStore.FailurePoint.TEMP_WRITE);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.write(Map.of("value", "new")));

        assertThat(Files.readString(stable.target())).contains("stable");
        assertThat(Files.exists(stable.target().resolveSibling("write.json.tmp"))).isFalse();
    }

    @Test
    void failedBackupRotationLeavesExistingTargetUntouched() throws Exception {
        DurableJsonStore stable = store("rotation.json");
        stable.write(Map.of("value", "stable"));
        DurableJsonStore failing = failingAt(stable.target(), DurableJsonStore.FailurePoint.BACKUP_ROTATION);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.write(Map.of("value", "new")));

        assertThat(Files.readString(stable.target())).contains("stable");
        assertThat(Files.exists(stable.target().resolveSibling("rotation.json.tmp"))).isFalse();
    }

    @Test
    void failedFinalRenameRecoversPreviousGenerationFromBackup() throws Exception {
        DurableJsonStore stable = store("rename.json");
        stable.write(Map.of("value", "stable"));
        DurableJsonStore failing = new DurableJsonStore(
                stable.target(), mapper,
                point -> {
                    if (point == DurableJsonStore.FailurePoint.TARGET_RENAME) {
                        throw new java.io.IOException("injected rename failure");
                    }
                });

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.write(Map.of("value", "new")));

        DurableJsonStore.ReadResult<Map> recovered = stable.read(Map.class);
        assertThat(recovered.value()).containsEntry("value", "stable");
        assertThat(recovered.recovered()).isTrue();
        assertThat(Files.exists(stable.target().resolveSibling("rename.json.tmp"))).isFalse();
    }

    private DurableJsonStore store(String fileName) {
        return new DurableJsonStore(tempDir.resolve(fileName), mapper);
    }

    private DurableJsonStore failingAt(Path target, DurableJsonStore.FailurePoint point) {
        return new DurableJsonStore(target, mapper, actual -> {
            if (actual == point) throw new java.io.IOException("injected " + point + " failure");
        });
    }
}
