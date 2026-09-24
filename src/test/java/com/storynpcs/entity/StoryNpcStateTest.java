package com.storynpcs.entity;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDisplay;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.DialogueView;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoryNpcStateTest {

    private DefinitionRegistry registry;
    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        registry = new DefinitionRegistry();
        ProgressionRepository repo = new ProgressionRepository(tempDir);
        EventPublisher publisher = new EventPublisher();
        service = new StoryNpcsApplicationService(registry, repo, publisher);

        // Register sample dialogue
        DialogueNode startNode = new DialogueNode("start", "Welcome to the garrison, recruit!");
        startNode.addOption(new DialogueEdge("Reporting for duty!", "report"));
        DialogueNode reportNode = new DialogueNode("report", "Glad to have you with us.");

        DialogueGraph graph = new DialogueGraph(
                NamespacedId.of("storynpcs:captain_dialogue"),
                "Captain Talk",
                "start"
        );
        graph.addNode(startNode);
        graph.addNode(reportNode);
        registry.registerDialogue(graph);

        // Register sample NPC
        NamespacedId npcId = NamespacedId.of("storynpcs:guard_captain");
        NpcDefinition npc = new NpcDefinition(npcId, "Captain Marcus");
        npc.getDisplay().setTitle("Garrison Commander");
        npc.getStats().setMaxHealth(50.0);
        npc.getStats().setMovementSpeed(0.3);
        npc.getAi().setMovementType(NpcAi.MovementType.WANDERING);
        npc.setDialogueId(NamespacedId.of("storynpcs:captain_dialogue"));
        npc.setFactionId(NamespacedId.of("storynpcs:town_guard"));
        registry.registerNpc(npc);
    }

    @Test
    @DisplayName("StoryNpcState resolves NPC definition, display name, and stats from registry")
    void testStateResolution() {
        StoryNpcState state = new StoryNpcState("storynpcs:guard_captain");
        assertEquals("storynpcs:guard_captain", state.getDefinitionId());
        assertEquals("storynpcs:guard_captain", state.getActorId());

        Optional<NpcDefinition> def = state.resolveDefinition(registry);
        assertTrue(def.isPresent());
        assertEquals("Captain Marcus", state.getDisplayName(registry).orElse(null));
        assertTrue(state.getStats(registry).isPresent());
        assertEquals(50.0, state.getStats(registry).get().getMaxHealth());
        assertEquals(0.3, state.getStats(registry).get().getMovementSpeed());
        assertEquals(NamespacedId.of("storynpcs:captain_dialogue"), state.getDialogueId(registry).orElse(null));
        assertEquals(NamespacedId.of("storynpcs:town_guard"), state.getFactionId(registry).orElse(null));
        assertTrue(state.canInteract(registry));
    }

    @Test
    @DisplayName("Logical actor identity is independent from the content definition")
    void testLogicalActorIdentity() {
        StoryNpcState state = new StoryNpcState("storynpcs:guard_captain");
        state.setActorId("storynpcs:captain_instance_01");
        state.setDefinitionId("storynpcs:guard_captain_v2");

        assertEquals("storynpcs:captain_instance_01", state.getActorId());
        assertEquals("storynpcs:guard_captain_v2", state.getDefinitionId());
    }

    @Test
    @DisplayName("StoryNpcState initiates dialogue interaction successfully")
    void testStateInteraction() {
        StoryNpcState state = new StoryNpcState("storynpcs:guard_captain");
        UUID playerUuid = UUID.randomUUID();

        Optional<DialogueView> viewOpt = state.interact(playerUuid, service, registry);
        assertTrue(viewOpt.isPresent());
        DialogueView view = viewOpt.get();

        assertEquals("start", view.nodeId());
        assertEquals("Welcome to the garrison, recruit!", view.text());
        assertEquals(1, view.options().size());
        assertEquals("Reporting for duty!", view.options().get(0));
        assertFalse(view.isTerminal());
    }

    @Test
    @DisplayName("StoryNpcState handles invalid or missing definitions gracefully")
    void testInvalidDefinition() {
        StoryNpcState state = new StoryNpcState("storynpcs:non_existent");
        assertFalse(state.resolveDefinition(registry).isPresent());
        assertFalse(state.getDisplayName(registry).isPresent());
        assertFalse(state.getStats(registry).isPresent());
        assertFalse(state.canInteract(registry));

        UUID playerUuid = UUID.randomUUID();
        Optional<DialogueView> viewOpt = state.interact(playerUuid, service, registry);
        assertTrue(viewOpt.isEmpty());
    }
}
