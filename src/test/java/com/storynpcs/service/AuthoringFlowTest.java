package com.storynpcs.service;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringFlowTest {

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private ProgressionRepository progressionRepository;
    private EventPublisher eventPublisher;
    private StoryNpcsApplicationService service;
    private YamlDefinitionLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DefinitionRegistry();
        progressionRepository = new ProgressionRepository(tempDir.resolve("progression"));
        eventPublisher = new EventPublisher();
        service = new StoryNpcsApplicationService(registry, progressionRepository, eventPublisher);

        Path defsDir = tempDir.resolve("definitions");
        Files.createDirectories(defsDir);
        loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(defsDir);
        service.setLoader(loader);
    }

    @Test
    void testCreateDialogueScaffoldValid() {
        NamespacedId dialId = NamespacedId.of("storynpcs:innkeeper_talk");
        var result = service.createDialogue(dialId, "Innkeeper Welcome");

        assertThat(result.isValid()).isTrue();
        assertThat(registry.getDialogue(dialId)).isPresent();
        DialogueGraph graph = registry.getDialogue(dialId).get();
        assertThat(graph.getTitle()).isEqualTo("Innkeeper Welcome");
        assertThat(graph.getEntryNodeId()).isEqualTo("start");
        assertThat(graph.getNode("start")).isPresent();
        assertThat(graph.getNode("start").orElseThrow().getText()).isEqualTo("Hello, traveler!");
    }

    @Test
    void testCreateDialogueDuplicateRefused() {
        NamespacedId dialId = NamespacedId.of("storynpcs:innkeeper_talk");
        var res1 = service.createDialogue(dialId, "First");
        assertThat(res1.isValid()).isTrue();

        var res2 = service.createDialogue(dialId, "Duplicate");
        assertThat(res2.hasErrors()).isTrue();
        assertThat(res2.getErrors().get(0).code()).isEqualTo("DIALOGUE_ALREADY_EXISTS");
    }

    @Test
    void testDeleteDialogueRemovesFromRegistryAndDisk() {
        NamespacedId dialId = NamespacedId.of("storynpcs:temp_chat");
        service.createDialogue(dialId, "Temp Chat");
        assertThat(registry.getDialogue(dialId)).isPresent();

        boolean deleted = service.deleteDialogue(dialId);
        assertThat(deleted).isTrue();
        assertThat(registry.getDialogue(dialId)).isEmpty();

        // Deleting again returns false
        assertThat(service.deleteDialogue(dialId)).isFalse();
    }

    @Test
    void testFindNpcsReferencingDialogue() {
        NamespacedId dialId = NamespacedId.of("storynpcs:shared_dialogue");
        service.createDialogue(dialId, "Shared");

        NamespacedId npc1 = NamespacedId.of("storynpcs:guard_1");
        NamespacedId npc2 = NamespacedId.of("storynpcs:guard_2");
        NamespacedId npc3 = NamespacedId.of("storynpcs:merchant");

        NpcDefinition d1 = new NpcDefinition(npc1, "Guard 1");
        d1.setDialogueId(dialId);
        service.createNpc(d1);

        NpcDefinition d2 = new NpcDefinition(npc2, "Guard 2");
        d2.setDialogueId(dialId);
        service.createNpc(d2);

        NpcDefinition d3 = new NpcDefinition(npc3, "Merchant");
        service.createNpc(d3);

        List<NamespacedId> refs = service.findNpcsReferencingDialogue(dialId);
        assertThat(refs).containsExactlyInAnyOrder(npc1, npc2);
    }

    @Test
    void testAssignDialoguePersistsAndValidates() {
        NamespacedId npcId = NamespacedId.of("storynpcs:blacksmith");
        service.createNpc(new NpcDefinition(npcId, "Blacksmith"));

        NamespacedId dialId = NamespacedId.of("storynpcs:smith_crafting");
        service.createDialogue(dialId, "Smith Crafting");

        var res = service.assignDialogue(npcId, dialId);
        assertThat(res.isValid()).isTrue();

        NpcDefinition updated = registry.getNpc(npcId).orElseThrow();
        assertThat(updated.getDialogueId()).isEqualTo(dialId);
    }

    @Test
    void testAssignDialogueFailsWhenDialogueMissing() {
        NamespacedId npcId = NamespacedId.of("storynpcs:miner");
        service.createNpc(new NpcDefinition(npcId, "Miner"));

        NamespacedId missingDial = NamespacedId.of("storynpcs:non_existent");
        assertThatThrownBy(() -> service.assignDialogue(npcId, missingDial))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Dialogue not found");
    }

    @Test
    void testAssignFactionPersistsAndValidates() {
        NamespacedId npcId = NamespacedId.of("storynpcs:knight");
        service.createNpc(new NpcDefinition(npcId, "Knight"));

        NamespacedId factionId = NamespacedId.of("storynpcs:royal_guard");
        Faction faction = new Faction(factionId, "Royal Guard", 500, 0, 1000);
        registry.registerFaction(faction);

        var res = service.assignFaction(npcId, factionId);
        assertThat(res.isValid()).isTrue();

        NpcDefinition updated = registry.getNpc(npcId).orElseThrow();
        assertThat(updated.getFactionId()).isEqualTo(factionId);
    }

    @Test
    void testAssignFactionFailsWhenFactionMissing() {
        NamespacedId npcId = NamespacedId.of("storynpcs:archer");
        service.createNpc(new NpcDefinition(npcId, "Archer"));

        NamespacedId missingFaction = NamespacedId.of("storynpcs:ghost_faction");
        assertThatThrownBy(() -> service.assignFaction(npcId, missingFaction))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Faction not found");
    }

    @Test
    void testDefinitionRegistryRemoveMethods() {
        NamespacedId dId = NamespacedId.of("storynpcs:test_d");
        NamespacedId fId = NamespacedId.of("storynpcs:test_f");
        NamespacedId qId = NamespacedId.of("storynpcs:test_q");

        registry.registerDialogue(new DialogueGraph(dId, "Test D", "start"));
        registry.registerFaction(new Faction(fId, "Test F", 100, 0, 200));
        registry.registerQuest(new Quest(qId, "Test Q"));

        assertThat(registry.getDialogue(dId)).isPresent();
        assertThat(registry.getFaction(fId)).isPresent();
        assertThat(registry.getQuest(qId)).isPresent();

        registry.removeDialogue(dId);
        registry.removeFaction(fId);
        registry.removeQuest(qId);

        assertThat(registry.getDialogue(dId)).isEmpty();
        assertThat(registry.getFaction(fId)).isEmpty();
        assertThat(registry.getQuest(qId)).isEmpty();
    }
}
