package com.storynpcs.lifecycle;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.progression.PlayerProgression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldLifecycleHandlerTest {

    private StoryNpcs mod;
    private WorldLifecycleHandler handler;

    @BeforeEach
    void setUp() {
        mod = StoryNpcs.createForTesting();
        handler = mod.getLifecycleHandler();
    }

    @Test
    @DisplayName("initializeWorld sets up directories, repositories, application service, and loads YAML definitions")
    void testInitializeWorld(@TempDir Path worldDir) throws IOException {
        Path defDir = worldDir.resolve("storynpcs").resolve("definitions").resolve("factions");
        Files.createDirectories(defDir);

        String factionYaml = """
                id: "storynpcs:town_guard"
                name: "Town Guard"
                defaultPoints: 500
                hostileThreshold: -500
                friendlyThreshold: 1000
                """;
        Files.writeString(defDir.resolve("town_guard.yaml"), factionYaml);

        handler.initializeWorld(worldDir);

        assertTrue(Files.exists(worldDir.resolve("storynpcs").resolve("definitions")));
        assertTrue(Files.exists(worldDir.resolve("storynpcs").resolve("progression")));
        assertNotNull(mod.getProgressionRepository());
        assertNotNull(mod.getApplicationService());

        assertTrue(mod.getRegistry().getFaction(NamespacedId.of("storynpcs:town_guard")).isPresent());
        assertEquals("Town Guard", mod.getRegistry().getFaction(NamespacedId.of("storynpcs:town_guard")).get().getName());
    }

    @Test
    @DisplayName("Player login and logout handles progression caching and persistence")
    void testPlayerLoginLogoutLifecycle(@TempDir Path worldDir) {
        handler.initializeWorld(worldDir);

        UUID playerUuid = UUID.randomUUID();
        handler.handlePlayerLogin(playerUuid);

        PlayerProgression progression = mod.getProgressionRepository().getOrCreate(playerUuid);
        assertNotNull(progression);
        progression.setFactionScore(NamespacedId.of("storynpcs:town_guard"), 750);

        handler.handlePlayerLogout(playerUuid);

        Path expectedFile = worldDir.resolve("storynpcs").resolve("progression").resolve(playerUuid + ".json");
        assertTrue(Files.exists(expectedFile), "Player progression file must exist on disk after logout");

        // Clear memory cache and reload to verify saved state
        mod.getProgressionRepository().clearCache();
        PlayerProgression loaded = mod.getProgressionRepository().getOrCreate(playerUuid);
        assertNotNull(loaded);
        assertEquals(750, loaded.getFactionScore(NamespacedId.of("storynpcs:town_guard"), 0));
    }

    @Test
    @DisplayName("onServerStopping flushes all pending player progressions")
    void testServerStoppingSavesAll(@TempDir Path worldDir) {
        handler.initializeWorld(worldDir);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        mod.getProgressionRepository().getOrCreate(p1).setFactionScore(NamespacedId.of("storynpcs:town_guard"), 200);
        mod.getProgressionRepository().getOrCreate(p2).setFactionScore(NamespacedId.of("storynpcs:town_guard"), 800);

        handler.handleServerStop();

        Path p1File = worldDir.resolve("storynpcs").resolve("progression").resolve(p1 + ".json");
        Path p2File = worldDir.resolve("storynpcs").resolve("progression").resolve(p2 + ".json");

        assertTrue(Files.exists(p1File));
        assertTrue(Files.exists(p2File));
    }
}