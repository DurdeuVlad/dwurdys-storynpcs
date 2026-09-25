package com.storynpcs.lifecycle;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.runtime.actor.ActorProjectionRegistry;
import com.storynpcs.runtime.actor.ActorRegistrySnapshot;
import com.storynpcs.runtime.actor.ActorStateRepository;
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

    // ---------------------------------------------------------------
    // PR #94 finding 1: world relocation must not destroy the logical
    // actor registry, and a failed restore must never let a later save
    // overwrite durable identities with an empty registry.
    // ---------------------------------------------------------------

    private static Path registryFile(Path storyNpcsDir) {
        return storyNpcsDir.resolve("actors").resolve("registry.json");
    }

    private static ActorProjectionRegistry registryWithActor(String scope, String actorId) {
        ActorProjectionRegistry registry = new ActorProjectionRegistry(scope);
        registry.bind(NamespacedId.of(actorId), NamespacedId.of("storynpcs:test/actor"), UUID.randomUUID());
        return registry;
    }

    @Test
    @DisplayName("Relocating the world directory preserves the logical actor registry")
    void relocatedWorldDirectoryPreservesLogicalActorRegistry(@TempDir Path tempDir) throws IOException {
        Path worldA = tempDir.resolve("world-original");
        Path worldB = tempDir.resolve("world-relocated");
        Path storyA = worldA.resolve("storynpcs");
        Path storyB = worldB.resolve("storynpcs");

        String scope = WorldScopeIdentity.resolve(storyA, registryFile(storyA));
        ActorProjectionRegistry actors = registryWithActor(scope, "storynpcs:actor/one");
        new ActorStateRepository(registryFile(storyA), scope).save(actors);

        // Simulate host-level relocation of the whole world tree.
        Files.createDirectories(worldB.getParent());
        Files.move(worldA, worldB);

        // The scope token travels with the directory — same logical identity at the new path.
        String relocatedScope = WorldScopeIdentity.resolve(storyB, registryFile(storyB));
        assertEquals(scope, relocatedScope);

        ActorProjectionRegistry restored = new ActorProjectionRegistry(relocatedScope);
        assertTrue(new ActorStateRepository(registryFile(storyB), relocatedScope).restore(restored));
        assertEquals(1, restored.records().size());
        assertEquals(NamespacedId.of("storynpcs:actor/one"), restored.records().get(0).actorId());
    }

    @Test
    @DisplayName("A corrupt registry blocks restore and refuses any later save")
    void corruptRegistryBlocksRestoreAndSubsequentSave(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        String scope = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));

        Files.createDirectories(registryFile(storyDir).getParent());
        Files.writeString(registryFile(storyDir), "{ not valid json !!!");

        ActorStateRepository repo = new ActorStateRepository(registryFile(storyDir), scope);
        ActorProjectionRegistry empty = new ActorProjectionRegistry(scope);
        assertThrows(IOException.class, () -> repo.restore(empty));
        assertTrue(repo.isBlocked());

        // The critical invariant: a save after the failed restore must not clobber evidence.
        IOException writeFailure = assertThrows(IOException.class, () -> repo.save(empty));
        assertTrue(writeFailure.getMessage().contains("blocked"));
    }

    @Test
    @DisplayName("A registry written by a different world scope is refused on read and write")
    void foreignScopeRegistryIsRefusedOnReadAndWrite(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");

        // A registry written by a different world (different durable scope token).
        ActorProjectionRegistry foreign = registryWithActor("world-foreign", "storynpcs:actor/x");
        new ActorStateRepository(registryFile(storyDir), "world-foreign").save(foreign);

        ActorStateRepository repo = new ActorStateRepository(registryFile(storyDir), "world-local");
        ActorProjectionRegistry local = new ActorProjectionRegistry("world-local");
        assertThrows(IOException.class, () -> repo.restore(local));
        assertTrue(repo.isBlocked());
        assertTrue(repo.blockedReason().contains("scope mismatch"));
        assertThrows(IOException.class, () -> repo.save(local));
    }

    @Test
    @DisplayName("A genuinely missing registry initializes without being blocked")
    void genuinelyMissingRegistryIsNotBlocked(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        String scope = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));

        ActorStateRepository repo = new ActorStateRepository(registryFile(storyDir), scope);
        ActorProjectionRegistry fresh = new ActorProjectionRegistry(scope);
        // Brand-new world: nothing to restore, and writes are allowed.
        assertFalse(repo.restore(fresh));
        assertFalse(repo.isBlocked());
        repo.save(registryWithActor(scope, "storynpcs:actor/first"));
        assertEquals(1, repo.load().actors().size());
    }

    @Test
    @DisplayName("Quarantined registry artifacts block writes even when the target file is absent")
    void quarantinedArtifactsBlockWriteEvenWhenTargetIsAbsent(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        String scope = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));

        Path actorsDir = registryFile(storyDir).getParent();
        Files.createDirectories(actorsDir);
        // Target deleted by quarantine; only the corrupted artifact remains.
        Files.writeString(actorsDir.resolve("registry.json.corrupted.1"), "garbage");

        ActorStateRepository repo = new ActorStateRepository(registryFile(storyDir), scope);
        assertThrows(IOException.class, () -> repo.restore(new ActorProjectionRegistry(scope)));
        assertTrue(repo.isBlocked());
        assertThrows(IOException.class, () -> repo.save(new ActorProjectionRegistry(scope)));
        assertTrue(Files.exists(actorsDir.resolve("registry.json.corrupted.1")));
    }

    @Test
    @DisplayName("A malformed scope.id fails closed instead of minting a divergent scope")
    void malformedScopeIdentityFileFailsClosed(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        Files.createDirectories(storyDir);
        Files.writeString(storyDir.resolve(WorldScopeIdentity.SCOPE_FILE), "not a valid scope\n\n!!");
        assertThrows(IOException.class,
                () -> WorldScopeIdentity.resolve(storyDir, registryFile(storyDir)));
    }

    @Test
    @DisplayName("A scope.id path that is not a regular file fails closed")
    void scopeIdentityPathThatIsNotAFileFailsClosed(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        Files.createDirectories(storyDir.resolve(WorldScopeIdentity.SCOPE_FILE));
        assertThrows(IOException.class,
                () -> WorldScopeIdentity.resolve(storyDir, registryFile(storyDir)));
    }

    @Test
    @DisplayName("Legacy worlds adopt the scope recorded inside their existing registry")
    void legacyWorldAdoptsScopeFromExistingRegistry(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        // Pre-scope.id world: registry exists with its own scope but no scope.id file.
        ActorProjectionRegistry legacy = registryWithActor("world-legacy-1", "storynpcs:actor/old");
        new ActorStateRepository(registryFile(storyDir)).save(legacy);
        Files.deleteIfExists(storyDir.resolve(WorldScopeIdentity.SCOPE_FILE));

        String adopted = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));
        assertEquals("world-legacy-1", adopted);
        // The adopted scope is now durable — a second resolve returns the same token.
        assertEquals("world-legacy-1", WorldScopeIdentity.resolve(storyDir, registryFile(storyDir)));
    }

    @Test
    @DisplayName("A legacy v0 unenveloped registry adopts its root-level scopeId")
    void legacyV0RegistryAdoptsRootScopeId(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        Files.createDirectories(registryFile(storyDir).getParent());
        // Pre-envelope registries carry scopeId at the document root.
        Files.writeString(registryFile(storyDir),
                "{\"scopeId\":\"world-legacy-v0\",\"actors\":[]}");

        String adopted = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));
        assertEquals("world-legacy-v0", adopted);
        assertEquals("world-legacy-v0",
                Files.readString(storyDir.resolve(WorldScopeIdentity.SCOPE_FILE)).trim());
    }

    @Test
    @DisplayName("An unreadable registry without scope.id fails scope resolution closed")
    void unreadableRegistryFailsScopeResolutionClosed(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        Files.createDirectories(registryFile(storyDir).getParent());
        // Registry exists but cannot be parsed, and no scope.id to arbitrate identity.
        Files.writeString(registryFile(storyDir), "### not json ###");
        assertThrows(IOException.class,
                () -> WorldScopeIdentity.resolve(storyDir, registryFile(storyDir)));
    }

    @Test
    @DisplayName("Distinct world directories mint distinct durable scopes")
    void distinctWorldsMintDistinctScopes(@TempDir Path tempDir) throws IOException {
        String scopeA = WorldScopeIdentity.resolve(
                tempDir.resolve("a").resolve("storynpcs"), null);
        String scopeB = WorldScopeIdentity.resolve(
                tempDir.resolve("b").resolve("storynpcs"), null);
        assertNotNull(scopeA);
        assertNotNull(scopeB);
        assertFalse(scopeA.equals(scopeB));
    }

    @Test
    @DisplayName("Actor snapshots persist identities and drop volatile projection UUIDs")
    void snapshotRoundTripPreservesActorsAndDropsProjections(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        String scope = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));
        ActorProjectionRegistry registry = registryWithActor(scope, "storynpcs:actor/one");
        registry.bind(NamespacedId.of("storynpcs:actor/two"),
                NamespacedId.of("storynpcs:test/actor"), UUID.randomUUID());

        ActorStateRepository repo = new ActorStateRepository(registryFile(storyDir), scope);
        repo.save(registry);

        ActorRegistrySnapshot snapshot = repo.load();
        assertEquals(scope, snapshot.scopeId());
        assertEquals(2, snapshot.actors().size());
        // Projection UUIDs are intentionally absent from the durable record.
        ActorProjectionRegistry restored = new ActorProjectionRegistry(scope);
        assertEquals(2, restored.restore(snapshot));
        assertTrue(restored.records().stream().allMatch(r -> r.projectionId() == null));
    }

    @Test
    @DisplayName("The actor registry envelope carries schemaVersion, scopeId, and actors")
    void snapshotSerializationShapeUsesExpectedEnvelope(@TempDir Path tempDir) throws IOException {
        Path storyDir = tempDir.resolve("world").resolve("storynpcs");
        String scope = WorldScopeIdentity.resolve(storyDir, registryFile(storyDir));
        new ActorStateRepository(registryFile(storyDir), scope)
                .save(registryWithActor(scope, "storynpcs:actor/env"));
        String raw = Files.readString(registryFile(storyDir));
        assertTrue(raw.contains("\"schemaVersion\""));
        assertTrue(raw.contains("\"scopeId\""));
        assertTrue(raw.contains(scope));
        assertTrue(raw.contains("storynpcs:actor/env"));
        assertTrue(raw.contains("\"actors\""));
    }
}
