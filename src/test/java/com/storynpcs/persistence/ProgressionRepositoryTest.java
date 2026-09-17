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
}
