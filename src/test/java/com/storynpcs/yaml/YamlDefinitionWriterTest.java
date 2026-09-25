package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TraderRole;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YamlDefinitionWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Written dialogue YAML loads back through YamlDefinitionLoader")
    void testWriteDialogueRoundTrip() throws Exception {
        DialogueGraph graph = new DialogueGraph(
                NamespacedId.of("storynpcs:written_dialogue"), "Written", "entry");
        DialogueNode entry = new DialogueNode("entry", "First node.");
        entry.setSound("minecraft:entity.villager.yes");
        entry.addOption(new DialogueEdge("Next", "second"));
        graph.addNode(entry);
        graph.addNode(new DialogueNode("second", "Last node."));

        Path written = new YamlDefinitionWriter().writeDialogue(tempDir, graph);

        assertEquals(tempDir.resolve("dialogues").resolve("written_dialogue.yaml"), written);
        assertTrue(Files.exists(written));
        assertTrue(Files.readString(written).contains("schemaVersion: 1"));
        assertNoTemporaryFiles(written.getParent());

        DefinitionRegistry registry = new DefinitionRegistry();
        YamlDefinitionLoader loader = new YamlDefinitionLoader(registry);
        var result = loader.loadDirectory(tempDir);

        assertTrue(result.isValid(), () -> "load errors: " + result.formatReport());
        DialogueGraph loaded = registry.getDialogue(NamespacedId.of("storynpcs:written_dialogue")).orElseThrow();
        assertEquals("Written", loaded.getTitle());
        assertEquals("entry", loaded.getEntryNodeId());
        assertEquals("minecraft:entity.villager.yes", loaded.getNode("entry").orElseThrow().getSound());
        assertEquals("second", loaded.getNode("entry").orElseThrow().getOptions().get(0).getTargetNodeId());
    }

    @Test
    @DisplayName("Written quest YAML round-trips every definition field")
    void testWrittenQuestRoundTrip() throws Exception {
        NamespacedId prerequisiteId = NamespacedId.of("storynpcs:prior_expedition");
        Quest prerequisite = new Quest(prerequisiteId, "Prior Expedition");
        prerequisite.setObjectives(List.of(new QuestObjective(
                "visit_outpost", QuestObjective.Type.VISIT_LOCATION, "storynpcs:outpost", 1)));

        NamespacedId questId = NamespacedId.of("storynpcs:northern_expedition");
        Quest quest = new Quest(questId, "Northern Expedition");
        quest.setDescription("Resolve the remaining danger beyond the pass.");
        quest.setCategory("exploration");
        quest.setRepeatType(Quest.RepeatType.DAILY);
        quest.setPrerequisites(List.of(prerequisiteId));
        quest.setObjectives(List.of(
                new QuestObjective("defeat_raiders", QuestObjective.Type.KILL_ENTITY,
                        "storynpcs:raider", 7),
                new QuestObjective("recover_map", QuestObjective.Type.COLLECT_ITEM,
                        "storynpcs:weathered_map", 1)));
        quest.setRewards(List.of(
                new QuestReward(QuestReward.Type.ITEM, "minecraft:emerald", 3),
                new QuestReward(QuestReward.Type.EXPERIENCE, "minecraft:experience", 125)));

        YamlDefinitionWriter writer = new YamlDefinitionWriter();
        writer.writeDefinition(tempDir, "quests", YamlDefinitionWriter.fileNameFor(prerequisiteId), prerequisite);
        Path written = writer.writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(questId), quest);

        assertEquals(tempDir.resolve("quests").resolve("northern_expedition.yaml"), written);
        assertTrue(Files.readString(written).contains("schemaVersion: 1"));
        assertNoTemporaryFiles(written.getParent());

        DefinitionRegistry registry = new DefinitionRegistry();
        ValidationResult result = new YamlDefinitionLoader(registry).loadDirectory(tempDir);

        assertTrue(result.isValid(), () -> "load errors: " + result.formatReport());
        Quest loaded = registry.getQuest(questId).orElseThrow();
        assertEquals(quest.getTitle(), loaded.getTitle());
        assertEquals(quest.getDescription(), loaded.getDescription());
        assertEquals(quest.getCategory(), loaded.getCategory());
        assertEquals(Quest.RepeatType.DAILY, loaded.getRepeatType());
        assertEquals(List.of(prerequisiteId), loaded.getPrerequisites());
        assertEquals(2, loaded.getObjectives().size());
        assertEquals("defeat_raiders", loaded.getObjectives().get(0).getId());
        assertEquals(QuestObjective.Type.KILL_ENTITY, loaded.getObjectives().get(0).getType());
        assertEquals("storynpcs:raider", loaded.getObjectives().get(0).getTarget());
        assertEquals(7, loaded.getObjectives().get(0).getRequiredCount());
        assertEquals("recover_map", loaded.getObjectives().get(1).getId());
        assertEquals(QuestObjective.Type.COLLECT_ITEM, loaded.getObjectives().get(1).getType());
        assertEquals("storynpcs:weathered_map", loaded.getObjectives().get(1).getTarget());
        assertEquals(1, loaded.getObjectives().get(1).getRequiredCount());
        assertEquals(2, loaded.getRewards().size());
        assertEquals(QuestReward.Type.ITEM, loaded.getRewards().get(0).getType());
        assertEquals("minecraft:emerald", loaded.getRewards().get(0).getTarget());
        assertEquals(3, loaded.getRewards().get(0).getAmount());
        assertEquals(QuestReward.Type.EXPERIENCE, loaded.getRewards().get(1).getType());
        assertEquals("minecraft:experience", loaded.getRewards().get(1).getTarget());
        assertEquals(125, loaded.getRewards().get(1).getAmount());
    }

    @Test
    @DisplayName("Quest YAML filenames preserve namespace identity")
    void testQuestFilenamePreservesNamespaceIdentity() throws Exception {
        Quest alphaQuest = new Quest(NamespacedId.of("alpha:delivery"), "Alpha Delivery");
        alphaQuest.setObjectives(List.of(new QuestObjective(
                "alpha_objective", QuestObjective.Type.CUSTOM, "alpha:crate", 1)));
        Quest betaQuest = new Quest(NamespacedId.of("beta:delivery"), "Beta Delivery");
        betaQuest.setObjectives(List.of(new QuestObjective(
                "beta_objective", QuestObjective.Type.CUSTOM, "beta:crate", 2)));

        YamlDefinitionWriter writer = new YamlDefinitionWriter();
        Path alphaFile = writer.writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(alphaQuest.getId()), alphaQuest);
        Path betaFile = writer.writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(betaQuest.getId()), betaQuest);

        assertNotEquals(alphaFile, betaFile, "distinct namespaced IDs must not overwrite one YAML file");
        assertTrue(Files.exists(alphaFile));
        assertTrue(Files.exists(betaFile));

        DefinitionRegistry registry = new DefinitionRegistry();
        ValidationResult result = new YamlDefinitionLoader(registry).loadDirectory(tempDir);

        assertTrue(result.isValid(), () -> "load errors: " + result.formatReport());
        assertEquals("Alpha Delivery", registry.getQuest(alphaQuest.getId()).orElseThrow().getTitle());
        assertEquals("Beta Delivery", registry.getQuest(betaQuest.getId()).orElseThrow().getTitle());
    }

    @Test
    @DisplayName("Namespaced quest saves migrate matching legacy files without overwriting another namespace")
    void testNamespacedQuestSaveMigratesLegacyFileWithoutOverwritingNamespaceCollision() throws Exception {
        NamespacedId alphaId = NamespacedId.of("alpha:delivery");
        NamespacedId betaId = NamespacedId.of("beta:delivery");
        Quest alphaQuest = new Quest(alphaId, "Alpha Delivery");
        Quest betaQuest = new Quest(betaId, "Beta Delivery");
        alphaQuest.setObjectives(List.of(new QuestObjective(
                "alpha_objective", QuestObjective.Type.CUSTOM, "alpha:crate", 1)));
        betaQuest.setObjectives(List.of(new QuestObjective(
                "beta_objective", QuestObjective.Type.CUSTOM, "beta:crate", 1)));
        YamlDefinitionWriter writer = new YamlDefinitionWriter();

        // A prior writer used only the path, so an alpha definition may already be delivery.yaml.
        Path legacyAlphaFile = writer.writeDefinition(tempDir, "quests", "delivery", alphaQuest);
        Path betaFile = writer.writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(betaId), betaQuest);

        assertNotEquals(legacyAlphaFile, betaFile);
        assertEquals("Alpha Delivery", readQuest(tempDir, alphaId).getTitle());
        assertEquals("Beta Delivery", readQuest(tempDir, betaId).getTitle());

        alphaQuest.setTitle("Updated Alpha Delivery");
        Path updatedAlphaFile = writer.writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(alphaId), alphaQuest);

        assertEquals(legacyAlphaFile, updatedAlphaFile,
                "saving a pre-upgrade definition must update its existing file instead of creating a duplicate");
        assertFalse(Files.exists(tempDir.resolve("quests").resolve("alpha@delivery.yaml")));
        assertEquals("Updated Alpha Delivery", readQuest(tempDir, alphaId).getTitle());
        assertEquals("Beta Delivery", readQuest(tempDir, betaId).getTitle());
    }

    @Test
    @DisplayName("Sanitized legacy path collisions never overwrite a different quest ID")
    void testSanitizedLegacyPathCollisionDoesNotOverwriteDifferentQuest() throws Exception {
        NamespacedId slashId = NamespacedId.of("storynpcs:a/b");
        NamespacedId flatId = NamespacedId.of("storynpcs:a_b");
        Quest slashQuest = new Quest(slashId, "Slash Path Quest");
        Quest flatQuest = new Quest(flatId, "Flat Path Quest");
        slashQuest.setObjectives(List.of(new QuestObjective(
                "slash_objective", QuestObjective.Type.CUSTOM, "storynpcs:crate", 1)));
        flatQuest.setObjectives(List.of(new QuestObjective(
                "flat_objective", QuestObjective.Type.CUSTOM, "storynpcs:crate", 1)));
        YamlDefinitionWriter writer = new YamlDefinitionWriter();

        // The pre-fix filename sanitizer mapped both IDs to a_b.yaml.
        Path legacySlashFile = writer.writeDefinition(tempDir, "quests", "a_b", slashQuest);
        Path flatFile = writer.writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(flatId), flatQuest);

        assertNotEquals(legacySlashFile, flatFile);
        assertTrue(Files.exists(legacySlashFile));
        assertTrue(Files.exists(flatFile));
        assertEquals("Slash Path Quest", readQuest(tempDir, slashId).getTitle());
        assertEquals("Flat Path Quest", readQuest(tempDir, flatId).getTitle());
    }

    @Test
    @DisplayName("Malformed existing YAML is preserved when its definition identity cannot be checked")
    void testMalformedExistingYamlIsNotOverwritten() throws Exception {
        Path questsDir = Files.createDirectories(tempDir.resolve("quests"));
        Path existing = questsDir.resolve("damaged.yaml");
        String malformedYaml = "id: [unterminated\n";
        Files.writeString(existing, malformedYaml);
        Quest quest = new Quest(NamespacedId.of("storynpcs:damaged"), "Recovered Quest");

        assertThrows(IOException.class, () -> new YamlDefinitionWriter().writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(quest.getId()), quest));
        assertEquals(malformedYaml, Files.readString(existing));
        assertNoTemporaryFiles(questsDir);
    }

    @Test
    @DisplayName("Unrelated malformed YAML does not block saving a different definition")
    void testUnrelatedMalformedYamlDoesNotBlockDefinitionSave() throws Exception {
        Path questsDir = Files.createDirectories(tempDir.resolve("quests"));
        Files.writeString(questsDir.resolve("unrelated_broken.yaml"), "id: [unterminated\n");
        Quest quest = new Quest(NamespacedId.of("storynpcs:healthy_save"), "Healthy Save");
        quest.setObjectives(List.of(new QuestObjective(
                "objective", QuestObjective.Type.CUSTOM, "storynpcs:crate", 1)));

        Path written = new YamlDefinitionWriter().writeDefinition(
                tempDir, "quests", YamlDefinitionWriter.fileNameFor(quest.getId()), quest);

        assertTrue(Files.exists(written));
        DefinitionRegistry registry = new DefinitionRegistry();
        ValidationResult loadResult = ValidationResult.valid();
        Quest loaded = new YamlDefinitionLoader(registry).loadQuest(
                Files.readString(written), written.toString(), loadResult);
        assertTrue(loadResult.isValid(), () -> "load errors: " + loadResult.formatReport());
        assertEquals(quest.getId(), loaded.getId());
        assertNoTemporaryFiles(questsDir);
    }

    @Test
    @DisplayName("Writer updates a renamed definition and fails closed on duplicate IDs")
    void testRenamedDefinitionIsReusedAndDuplicateIdsFailClosed() throws Exception {
        NamespacedId id = NamespacedId.of("storynpcs:renamed");
        Quest quest = new Quest(id, "Original Title");
        quest.setObjectives(List.of(new QuestObjective(
                "objective", QuestObjective.Type.CUSTOM, "storynpcs:crate", 1)));
        YamlDefinitionWriter writer = new YamlDefinitionWriter();
        Path original = writer.writeDefinition(tempDir, "quests", "renamed", quest);
        Path renamedByAuthor = original.resolveSibling("operator_named_this.yaml");
        Files.move(original, renamedByAuthor);

        DefinitionRegistry loadedRegistry = new DefinitionRegistry();
        YamlDefinitionLoader loader = new YamlDefinitionLoader(loadedRegistry);
        ValidationResult initialLoad = loader.loadDirectory(tempDir);
        assertTrue(initialLoad.isValid(), () -> "load errors: " + initialLoad.formatReport());
        quest.setTitle("Updated Through Renamed File");
        Path updated = writer.writeDefinition(tempDir, "quests", YamlDefinitionWriter.fileNameFor(id), quest,
                loader.getDefinitionFiles("quests", id));

        assertEquals(renamedByAuthor, updated,
                "a unique same-ID file must be updated in place even when its filename was changed");
        assertEquals("Updated Through Renamed File", readQuest(tempDir, id).getTitle());
        assertNoTemporaryFiles(renamedByAuthor.getParent());

        Path duplicate = renamedByAuthor.resolveSibling("duplicate-copy.yaml");
        Files.copy(renamedByAuthor, duplicate);
        String renamedBytes = Files.readString(renamedByAuthor);
        String duplicateBytes = Files.readString(duplicate);
        DefinitionRegistry duplicateRegistry = new DefinitionRegistry();
        YamlDefinitionLoader duplicateLoader = new YamlDefinitionLoader(duplicateRegistry);
        ValidationResult duplicateLoad = duplicateLoader.loadDirectory(tempDir);
        assertFalse(duplicateLoad.isValid(), "loader must observe the deliberately duplicated ID");
        assertEquals(2, duplicateLoader.getDefinitionFiles("quests", id).size());
        assertThrows(IOException.class, () -> writer.writeDefinition(tempDir, "quests",
                YamlDefinitionWriter.fileNameFor(id), quest, duplicateLoader.getDefinitionFiles("quests", id)));
        assertEquals(renamedBytes, Files.readString(renamedByAuthor));
        assertEquals(duplicateBytes, Files.readString(duplicate));
        assertNoTemporaryFiles(renamedByAuthor.getParent());
    }

    @Test
    @DisplayName("Loader refuses duplicate-ID deletion without removing either source")
    void testDeleteDuplicateDefinitionSourcesFailsClosed() throws Exception {
        NamespacedId id = NamespacedId.of("storynpcs:duplicate_delete");
        Quest quest = new Quest(id, "Duplicate Delete");
        quest.setObjectives(List.of(new QuestObjective(
                "objective_1", QuestObjective.Type.CUSTOM, "describe_the_objective", 1)));
        Path original = new YamlDefinitionWriter().writeDefinition(tempDir, "quests", "duplicate_delete", quest);
        Path duplicate = original.resolveSibling("duplicate_delete_copy.yaml");
        Files.copy(original, duplicate);

        DefinitionRegistry registry = new DefinitionRegistry();
        YamlDefinitionLoader loader = new YamlDefinitionLoader(registry);
        ValidationResult loadResult = loader.loadDirectory(tempDir);
        assertFalse(loadResult.isValid(), "loading duplicate IDs must be reported as invalid");
        assertEquals(2, loader.getDefinitionFiles("quests", id).size());

        IOException failure = assertThrows(IOException.class, () -> loader.deleteDefinitionFile("quest", id));

        assertTrue(failure.getMessage().contains("multiple definition files"));
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(duplicate));
        assertEquals(2, loader.getDefinitionFiles("quests", id).size(),
                "a rejected multi-source delete must preserve the complete loader index");
    }

    @Test
    @DisplayName("Writer rejects subdirectory traversal outside the definitions root")
    void testSubdirectoryTraversalIsRejected() throws Exception {
        Path definitionsRoot = Files.createDirectory(tempDir.resolve("definitions"));
        Quest quest = new Quest(NamespacedId.of("storynpcs:subdirectory_guard"), "Guarded");
        YamlDefinitionWriter writer = new YamlDefinitionWriter();
        String uniqueDirectory = "outside_" + Long.toUnsignedString(System.nanoTime());
        Path escapedDirectory = definitionsRoot.getParent().resolve(uniqueDirectory);

        for (String subdirectory : List.of("../" + uniqueDirectory, "..\\" + uniqueDirectory)) {
            assertThrows(IOException.class, () -> writer.writeDefinition(
                    definitionsRoot, subdirectory, "subdirectory_guard", quest));
        }

        assertFalse(Files.exists(escapedDirectory), "rejected paths must not create directories outside the root");
    }

    @Test
    @DisplayName("Writer rejects a symlinked type directory that escapes the definitions root")
    void testSymlinkTypeDirectoryEscapeIsRejected() throws Exception {
        Path definitionsRoot = Files.createDirectory(tempDir.resolve("definitions"));
        Quest quest = new Quest(NamespacedId.of("storynpcs:symlink_guard"), "Guarded");
        YamlDefinitionWriter writer = new YamlDefinitionWriter();
        Path outsideTypeDirectory = Files.createDirectory(tempDir.resolve("outside_target"));
        boolean symlinkSupported = true;
        try {
            Files.createSymbolicLink(definitionsRoot.resolve("quests"), outsideTypeDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            symlinkSupported = false;
        }
        Assumptions.assumeTrue(symlinkSupported, "current filesystem/user cannot create symbolic links");
        assertThrows(IOException.class, () -> writer.writeDefinition(
                definitionsRoot, "quests", "symlink_guard", quest));
        assertFalse(Files.exists(outsideTypeDirectory.resolve("symlink_guard.yaml")));
    }

    @Test
    @DisplayName("Concurrent writes to one definition use separate atomic temporary files")
    void testConcurrentWritesUseIndependentTemporaryFiles() throws Exception {
        NamespacedId id = NamespacedId.of("storynpcs:concurrent_writer");
        DefinitionWriteCoordinator coordinator = new DefinitionWriteCoordinator();
        IntStream.range(0, 12).parallel().forEach(version -> {
            Quest quest = new Quest(id, "Concurrent version " + version);
            quest.setObjectives(List.of(new QuestObjective(
                    "objective", QuestObjective.Type.CUSTOM, "storynpcs:crate", 1)));
            try {
                new YamlDefinitionWriter(coordinator).writeDefinition(
                        tempDir, "quests", YamlDefinitionWriter.fileNameFor(id), quest);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        assertTrue(readQuest(tempDir, id).getTitle().startsWith("Concurrent version "));
        assertNoTemporaryFiles(tempDir.resolve("quests"));
    }

    private Quest readQuest(Path root, NamespacedId id) throws Exception {
        DefinitionRegistry registry = new DefinitionRegistry();
        ValidationResult result = new YamlDefinitionLoader(registry).loadDirectory(root);
        assertTrue(result.isValid(), () -> "load errors: " + result.formatReport());
        return registry.getQuest(id).orElseThrow();
    }

    private void assertNoTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    @DisplayName("fileNameFor preserves identity with safe paths")
    void testFileNamePreservesNamespacedIdentity() {
        assertEquals("guard_captain", YamlDefinitionWriter.fileNameFor(NamespacedId.of("storynpcs:guard_captain")));
        assertEquals("alpha@guard_captain", YamlDefinitionWriter.fileNameFor(NamespacedId.of("alpha:guard_captain")));
        // Distinct namespaces and paths containing '/' must remain distinguishable.
        assertNotEquals(
                YamlDefinitionWriter.fileNameFor(NamespacedId.of("alpha:delivery")),
                YamlDefinitionWriter.fileNameFor(NamespacedId.of("beta:delivery")));
        assertNotEquals(
                YamlDefinitionWriter.fileNameFor(NamespacedId.of("storynpcs:a/b")),
                YamlDefinitionWriter.fileNameFor(NamespacedId.of("storynpcs:a_b")));
        assertEquals("storynpcs@a%2Fb%2Fc", YamlDefinitionWriter.fileNameFor(NamespacedId.of("storynpcs:a/b/c")));
        assertEquals("storynpcs@con", YamlDefinitionWriter.fileNameFor(NamespacedId.of("storynpcs:con")));
        assertEquals("unnamed", YamlDefinitionWriter.fileNameFor(null));
    }

    @Test
    @DisplayName("Runtime trader listing uses are not written into YAML content")
    void testRuntimeTradeUsesAreExcludedFromYaml() throws Exception {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:merchant"), "Merchant");
        TraderRole trader = new TraderRole("Market");
        TradeListing listing = new TradeListing("minecraft:emerald", 1, "minecraft:bread", 3);
        listing.setMaxUses(10);
        listing.setUses(7);
        trader.addListing(listing);
        npc.setTrader(trader);

        Path written = new YamlDefinitionWriter().writeDefinition(tempDir, "npcs", "merchant", npc);
        String yaml = Files.readString(written);

        assertTrue(yaml.contains("maxUses: 10"));
        assertFalse(yaml.contains("uses:"), () -> "runtime trade state leaked into YAML:\n" + yaml);
    }
}
