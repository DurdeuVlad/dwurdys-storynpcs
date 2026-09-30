package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexedRecordStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    private IndexedRecordStore openStore() throws IOException {
        return openStore(DurableJsonStore.FailureInjector.none());
    }

    private IndexedRecordStore openStore(DurableJsonStore.FailureInjector injector) throws IOException {
        var store = new IndexedRecordStore(tempDir.resolve("store"), mapper, injector);
        store.open();
        return store;
    }

    @Test
    void writeReadListRoundTripsThroughIndex() throws IOException {
        var store = openStore();
        store.write("storynpcs:spawn_a", Map.of("x", 1, "y", 64));
        store.write("storynpcs:spawn_b", Map.of("x", 7, "y", 70));

        assertThat(store.listIds()).containsExactlyInAnyOrder("storynpcs:spawn_a", "storynpcs:spawn_b");
        assertThat(store.read("storynpcs:spawn_a", Map.class)).contains(Map.of("x", 1, "y", 64));
        assertThat(store.read("storynpcs:missing", Map.class)).isEmpty();

        // Index survives a reopen without rebuilding.
        var reopened = openStore();
        assertThat(reopened.listIds()).hasSize(2);
        assertThat(reopened.read("storynpcs:spawn_b", Map.class)).isPresent();
    }

    @Test
    void crashBetweenRecordAndIndexCommitRecoversViaRebuild() throws IOException {
        var injectorCalls = new AtomicInteger();
        // Fail only on the second write's INDEX_UPDATE stage.
        var store = new IndexedRecordStore(tempDir.resolve("store"), mapper, point -> {
            if (point == DurableJsonStore.FailurePoint.INDEX_UPDATE
                    && injectorCalls.incrementAndGet() == 2) {
                throw new IOException("injected index-update crash");
            }
        });
        store.open();
        store.write("storynpcs:first", Map.of("v", 1));
        assertThatThrownBy(() -> store.write("storynpcs:second", Map.of("v", 2)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("index-update crash");

        // The record itself is committed and readable even though the index is stale.
        assertThat(store.read("storynpcs:second", Map.class)).contains(Map.of("v", 2));

        // Reopen: the stale index is rebuilt from the record files.
        var reconciled = openStore();
        assertThat(reconciled.listIds())
                .containsExactlyInAnyOrder("storynpcs:first", "storynpcs:second");
        assertThat(reconciled.read("storynpcs:second", Map.class)).isPresent();
    }

    @Test
    void openRebuildsStaleOrCorruptIndexWithDiagnostics() throws IOException {
        var store = openStore();
        store.write("storynpcs:a", Map.of("v", 1));

        // Corrupt the index file, then reopen.
        Path dir = tempDir.resolve("store");
        Files.writeString(dir.resolve("_index.json"), "not: [valid");
        var fresh = new IndexedRecordStore(dir, mapper);
        var result = fresh.open();

        assertThat(result.indexRebuilt()).isTrue();
        assertThat(result.recordCount()).isEqualTo(1);
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(fresh.read("storynpcs:a", Map.class)).isPresent();
        // Quarantined index artifacts remain as evidence (not silently replaced).
        assertThat(fresh.hasProtectedIndexArtifacts()).isTrue();
    }

    @Test
    void corruptRecordIsSkippedWithoutErasingValidRecords() throws IOException {
        var store = openStore();
        store.write("storynpcs:good", Map.of("v", 1));
        Files.writeString(tempDir.resolve("store").resolve("record-corrupt-dead.json"), "{{{{bad");

        var reopened = new IndexedRecordStore(tempDir.resolve("store"), mapper);
        var result = reopened.open();

        assertThat(result.recordCount()).isEqualTo(1);
        assertThat(result.diagnostics().toString()).contains("record-corrupt-dead");
        assertThat(reopened.listIds()).containsExactly("storynpcs:good");
        // The invalid record is quarantined (renamed) for evidence, never silently dropped.
        try (var stream = Files.list(tempDir.resolve("store"))) {
            assertThat(stream.anyMatch(p -> p.getFileName().toString().contains(".corrupted."))).isTrue();
        }
    }

    @Test
    void deleteCommitsRecordRemovalThroughIndexUpdate() throws IOException {
        var store = openStore();
        store.write("storynpcs:a", Map.of("v", 1));
        store.write("storynpcs:b", Map.of("v", 2));

        assertThat(store.delete("storynpcs:a")).isTrue();
        assertThat(store.listIds()).containsExactly("storynpcs:b");
        assertThat(store.read("storynpcs:a", Map.class)).isEmpty();

        var reopened = openStore();
        assertThat(reopened.listIds()).containsExactly("storynpcs:b");
    }

    @Test
    void writeRequiresOpenAndValidIds() throws IOException {
        var store = new IndexedRecordStore(tempDir.resolve("store"), mapper);
        assertThatThrownBy(() -> store.write("storynpcs:x", Map.of()))
                .isInstanceOf(IllegalStateException.class);
        var opened = openStore();
        assertThatThrownBy(() -> opened.write(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opened.write("storynpcs:x", null))
                .isInstanceOf(NullPointerException.class);
    }
}
