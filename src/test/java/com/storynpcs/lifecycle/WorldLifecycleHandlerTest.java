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

    @Test
    @DisplayName("initializeWorld seeds bundled starter definitions only when the dir is empty")
    void testStarterDefinitionSeeding(@TempDir Path worldDir) throws IOException {
        handler.initializeWorld(worldDir);

        Path defDir = worldDir.resolve("storynpcs").resolve("definitions");
        // Bundled starter content landed on disk and loaded into the registry
        assertTrue(Files.exists(defDir.resolve("npcs").resolve("guard_captain.yaml")));
        assertTrue(Files.exists(defDir.resolve("dialogues").resolve("captain_dialogue.yaml")));
        assertTrue(Files.exists(defDir.resolve("factions").resolve("town_guard.yaml")));
        assertTrue(Files.exists(defDir.resolve("quests").resolve("bounty_goblins.yaml")));
        assertTrue(mod.getRegistry().getNpc(NamespacedId.of("storynpcs:guard_captain")).isPresent());
        assertTrue(mod.getRegistry().getDialogue(NamespacedId.of("storynpcs:captain_dialogue")).isPresent());
    }

    @Test
    @DisplayName("Seeding never overwrites existing admin YAML")
    void testSeedingRespectsExistingContent(@TempDir Path worldDir) throws IOException {
        Path npcDir = worldDir.resolve("storynpcs").resolve("definitions").resolve("npcs");
        Files.createDirectories(npcDir);
        String customYaml = """
                id: "storynpcs:my_npc"
                display:
                  name: "Custom"
                """;
        Files.writeString(npcDir.resolve("my_npc.yaml"), customYaml);

        handler.initializeWorld(worldDir);

        // Admin file untouched, starter content not added on top
        assertEquals(customYaml, Files.readString(npcDir.resolve("my_npc.yaml")));
        assertFalse(Files.exists(npcDir.resolve("guard_captain.yaml")));
        assertTrue(mod.getRegistry().getNpc(NamespacedId.of("storynpcs:my_npc")).isPresent());
    }

    @Test
    @DisplayName("Seeding with no operator online parks a pending announcement marker")
    void testSeedingWritesPendingAnnouncementMarker(@TempDir Path worldDir) {
        // No server instance -> nobody can be notified live, so the marker must be parked
        handler.initializeWorld(worldDir);

        Path marker = worldDir.resolve("storynpcs").resolve(".starter_seed_pending");
        assertTrue(Files.exists(marker), "seed with nobody to tell must leave a pending marker");
    }

    @Test
    @DisplayName("Pending marker names the seeded starter NPC")
    void testPendingMarkerContainsStarterNpcId(@TempDir Path worldDir) throws IOException {
        handler.initializeWorld(worldDir);

        Path marker = worldDir.resolve("storynpcs").resolve(".starter_seed_pending");
        assertEquals("storynpcs:guard_captain", Files.readString(marker).trim());
    }

    @Test
    @DisplayName("Announcement is consumed exactly once — first op gets it, later joins do not")
    void testAnnouncementConsumedExactlyOnce(@TempDir Path worldDir) {
        handler.initializeWorld(worldDir);

        assertTrue(handler.consumePendingSeedAnnouncement().isPresent(),
                "first operator join must claim the pending announcement");
        assertTrue(handler.consumePendingSeedAnnouncement().isEmpty(),
                "marker is gone after first delivery — no repeat announcements");
        assertTrue(handler.consumePendingSeedAnnouncement().isEmpty(),
                "still quiet on the third join");
    }

    @Test
    @DisplayName("Skipped seeding (existing admin YAML) leaves no pending announcement")
    void testNoMarkerWhenSeedingSkipped(@TempDir Path worldDir) throws IOException {
        Path npcDir = worldDir.resolve("storynpcs").resolve("definitions").resolve("npcs");
        Files.createDirectories(npcDir);
        Files.writeString(npcDir.resolve("admin.yaml"), "id: \"storynpcs:admin\"\n");

        handler.initializeWorld(worldDir);

        assertFalse(Files.exists(worldDir.resolve("storynpcs").resolve(".starter_seed_pending")));
        assertTrue(handler.consumePendingSeedAnnouncement().isEmpty());
    }

    @Test
    @DisplayName("Pending announcement survives a restart when nobody ever claimed it")
    void testMarkerSurvivesRestartUntilDelivered(@TempDir Path worldDir) {
        handler.initializeWorld(worldDir); // server 1: seeds, no ops -> marker parked

        // Fresh handler instance over the SAME world dir simulates a restart:
        // seeding is skipped (YAML now exists) but the undelivered marker must persist.
        StoryNpcs mod2 = StoryNpcs.createForTesting();
        WorldLifecycleHandler handler2 = mod2.getLifecycleHandler();
        handler2.initializeWorld(worldDir);

        assertTrue(handler2.consumePendingSeedAnnouncement().isPresent(),
                "restart must not lose an undelivered announcement");
        assertTrue(handler2.consumePendingSeedAnnouncement().isEmpty());
    }

    @Test
    @DisplayName("Load diagnostics are stored on the mod so ops can be notified in-game")
    void testLoadDiagnosticsStoredForInGameSurfacing(@TempDir Path worldDir) throws IOException {
        Path defDir = worldDir.resolve("storynpcs").resolve("definitions").resolve("quests");
        Files.createDirectories(defDir);
        // Invalid: a quest with no objectives triggers QUEST_OBJ_EMPTY
        String brokenYaml = """
                id: "storynpcs:broken_quest"
                title: "Broken"
                objectives: []
                """;
        Files.writeString(defDir.resolve("broken.yaml"), brokenYaml);

        handler.initializeWorld(worldDir);

        assertNotNull(mod.getLastLoadDiagnostics(), "load result must be retained for in-game surfacing");
        assertTrue(mod.getLastLoadDiagnostics().hasErrors());
        assertTrue(mod.getLastLoadDiagnostics().formatReport(10).contains("QUEST_OBJ_EMPTY"));
    }

    @Test
    @DisplayName("A clean load leaves no error diagnostics behind")
    void testCleanLoadClearsDiagnostics(@TempDir Path worldDir) {
        handler.initializeWorld(worldDir); // seeds valid starter YAML

        assertNotNull(mod.getLastLoadDiagnostics());
        assertFalse(mod.getLastLoadDiagnostics().hasErrors());
    }
}