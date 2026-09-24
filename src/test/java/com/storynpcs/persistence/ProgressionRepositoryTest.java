package com.storynpcs.persistence;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgressionRepositoryTest {
    @TempDir
    Path tempDir;

    private ProgressionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ProgressionRepository(tempDir);
    }

    @Test
    void shouldAtomicallyPersistAndReloadProgression() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        PlayerProgression progression = repository.getOrCreate(playerUuid);

        // Mutate progression
        NamespacedId questId = NamespacedId.of("storynpcs:tutorial_quest");
        progression.getQuestState(questId).setStatus(QuestProgressState.Status.IN_PROGRESS);
        progression.getQuestState(questId).incrementCount("kill_slime", 3);

        NamespacedId factionId = NamespacedId.of("storynpcs:town_guard");
        progression.setFactionScore(factionId, 1250);

        progression.recordDialogueNodeVisit("welcome_node");

        // Save atomically
        repository.save(playerUuid);

        // Verify JSON file created, .tmp file cleaned up
        Path targetFile = tempDir.resolve(playerUuid.toString() + ".json");
        Path tmpFile = tempDir.resolve(playerUuid.toString() + ".tmp");
        assertThat(Files.exists(targetFile)).isTrue();
        assertThat(Files.exists(tmpFile)).isFalse();

        // Clear in-memory cache and reload from disk
        repository.clearCache();
        PlayerProgression reloaded = repository.getOrCreate(playerUuid);

        assertThat(reloaded.getPlayerUuid()).isEqualTo(playerUuid);
        assertThat(reloaded.getQuestState(questId).getStatus()).isEqualTo(QuestProgressState.Status.IN_PROGRESS);
        assertThat(reloaded.getQuestState(questId).getCount("kill_slime")).isEqualTo(3);
        assertThat(reloaded.getFactionScore(factionId, 1000)).isEqualTo(1250);
        assertThat(reloaded.hasVisitedDialogueNode("welcome_node")).isTrue();
    }

    @Test
    void corruptedRecordFailsClosedAndRefusesWrites() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        Path targetFile = tempDir.resolve(playerUuid.toString() + ".json");
        Files.writeString(targetFile, "{ corrupt json unclosed");

        // Fail closed: an unrecoverable record must never become empty state.
        assertThatThrownBy(() -> repository.getOrCreate(playerUuid))
                .isInstanceOf(UnrecoverablePlayerDataException.class);
        assertThat(repository.isUnavailable(playerUuid)).isTrue();
        assertThat(repository.unavailabilityReason(playerUuid)).isNotBlank();

        // The corrupt bytes are quarantined — durable evidence is preserved.
        try (var stream = Files.list(tempDir)) {
            boolean hasBackup = stream.anyMatch(p -> p.getFileName().toString().contains(".corrupted."));
            assertThat(hasBackup).isTrue();
        }

        // A later save must not overwrite the surviving evidence with empty state.
        assertThatThrownBy(() -> repository.save(playerUuid))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    void futureSchemaRecordFailsClosed() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        Path targetFile = tempDir.resolve(playerUuid.toString() + ".json");
        Files.writeString(targetFile,
                "{\"schemaVersion\":999,\"data\":{\"playerUuid\":\"" + playerUuid + "\"}}");

        assertThatThrownBy(() -> repository.getOrCreate(playerUuid))
                .isInstanceOf(UnrecoverablePlayerDataException.class);
        assertThat(repository.isUnavailable(playerUuid)).isTrue();
        assertThatThrownBy(() -> repository.save(playerUuid)).isInstanceOf(IOException.class);
    }

    @Test
    void quarantinedArtifactAloneBlocksInitialization() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        // The record itself is gone — only quarantine evidence remains. A fresh
        // empty progression must not be minted over it.
        Files.writeString(tempDir.resolve(playerUuid + ".json.corrupted.1"), "garbage");

        assertThatThrownBy(() -> repository.getOrCreate(playerUuid))
                .isInstanceOf(UnrecoverablePlayerDataException.class);
        assertThatThrownBy(() -> repository.save(playerUuid)).isInstanceOf(IOException.class);
    }

    @Test
    void unloadDoesNotEraseDurableCorruptionEvidence() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        Files.writeString(tempDir.resolve(playerUuid + ".json"), "{ nope");

        assertThatThrownBy(() -> repository.getOrCreate(playerUuid))
                .isInstanceOf(UnrecoverablePlayerDataException.class);
        assertThat(repository.isUnavailable(playerUuid)).isTrue();

        // Unloading clears the in-memory block, but the durable .corrupted
        // artifact re-blocks the record on the next access — evidence is never lost.
        repository.unload(playerUuid);
        assertThat(repository.isUnavailable(playerUuid)).isFalse();
        assertThatThrownBy(() -> repository.getOrCreate(playerUuid))
                .isInstanceOf(UnrecoverablePlayerDataException.class);
        assertThat(repository.isUnavailable(playerUuid)).isTrue();
    }

    @Test
    void genuinelyMissingRecordStillInitializesEmptyProgression() {
        UUID playerUuid = UUID.randomUUID();
        PlayerProgression progression = repository.getOrCreate(playerUuid);
        assertThat(progression).isNotNull();
        assertThat(progression.getPlayerUuid()).isEqualTo(playerUuid);
        assertThat(repository.isUnavailable(playerUuid)).isFalse();
    }

    @Test
    void instanceBoundSaveCommitsEvenAfterCacheEviction() throws IOException {
        UUID playerUuid = UUID.randomUUID();
        PlayerProgression progression = repository.getOrCreate(playerUuid);
        progression.setQuestRevision(41);

        // A cache eviction between mutation and write must not silently drop
        // the commit — the instance-bound save writes the passed instance.
        repository.unload(playerUuid);
        repository.save(playerUuid, progression);

        repository.clearCache();
        assertThat(repository.getOrCreate(playerUuid).getQuestRevision()).isEqualTo(41);
    }
}
