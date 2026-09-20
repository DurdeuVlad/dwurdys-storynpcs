package com.storynpcs.command;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.yaml.DefinitionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the pure decision logic behind /storynpcs quickstart: which demo NPC to
 * use and whether the scaffolded fallback dialogue is a working graph.
 */
class QuickstartLogicTest {

    private static final NamespacedId SEEDED = NamespacedId.of("storynpcs:guard_captain");
    private static final NamespacedId SEEDED_DIALOGUE = NamespacedId.of("storynpcs:captain_dialogue");

    private static NpcDefinition npcWithDialogue(NamespacedId id, NamespacedId dialogueId) {
        NpcDefinition npc = new NpcDefinition(id, "Test NPC");
        npc.setDialogueId(dialogueId);
        return npc;
    }

    private static DialogueGraph minimalDialogue(NamespacedId id) {
        DialogueGraph g = new DialogueGraph(id, "T", "a");
        g.addNode(new DialogueNode("a", "hi"));
        return g;
    }

    @Test
    @DisplayName("Empty registry → null (scaffold path)")
    void resolve_emptyRegistry_needsScaffolding() {
        assertNull(StoryNpcsCommands.resolveDemoNpcId(new DefinitionRegistry()));
    }

    @Test
    @DisplayName("Seeded NPC with working dialogue is preferred")
    void resolve_seededNpcUsable_returnsSeeded() {
        DefinitionRegistry reg = new DefinitionRegistry();
        reg.registerNpc(npcWithDialogue(SEEDED, SEEDED_DIALOGUE));
        reg.registerDialogue(minimalDialogue(SEEDED_DIALOGUE));
        assertEquals(SEEDED, StoryNpcsCommands.resolveDemoNpcId(reg));
    }

    @Test
    @DisplayName("Seeded NPC whose dialogue is not loaded falls back to scaffolding")
    void resolve_seededNpcDialogueMissing_returnsNull() {
        DefinitionRegistry reg = new DefinitionRegistry();
        reg.registerNpc(npcWithDialogue(SEEDED, SEEDED_DIALOGUE)); // dialogue never registered
        assertNull(StoryNpcsCommands.resolveDemoNpcId(reg));
    }

    @Test
    @DisplayName("Seeded NPC with no dialogueId falls back to scaffolding")
    void resolve_seededNpcNoDialogueId_returnsNull() {
        DefinitionRegistry reg = new DefinitionRegistry();
        reg.registerNpc(new NpcDefinition(SEEDED, "Captain"));
        assertNull(StoryNpcsCommands.resolveDemoNpcId(reg));
    }

    @Test
    @DisplayName("Already-scaffolded demo NPC is reused on repeat runs")
    void resolve_demoNpcAlreadyScaffolded_reused() {
        DefinitionRegistry reg = new DefinitionRegistry();
        reg.registerNpc(npcWithDialogue(StoryNpcsCommands.QUICKSTART_DEMO_NPC,
                StoryNpcsCommands.QUICKSTART_DEMO_DIALOGUE));
        reg.registerDialogue(minimalDialogue(StoryNpcsCommands.QUICKSTART_DEMO_DIALOGUE));
        assertEquals(StoryNpcsCommands.QUICKSTART_DEMO_NPC, StoryNpcsCommands.resolveDemoNpcId(reg));
    }

    @Test
    @DisplayName("Scaffolded dialogue is a working graph: entry resolves, every edge target exists, terminal reachable")
    void buildQuickstartDialogue_isWorkingGraph() {
        DialogueGraph g = StoryNpcsCommands.buildQuickstartDialogue();

        assertEquals(StoryNpcsCommands.QUICKSTART_DEMO_DIALOGUE, g.getId());
        assertFalse(g.getTitle().isBlank(), "Dialogue must have a title");

        DialogueNode entry = g.getEntryNode()
                .orElseThrow(() -> new AssertionError("entry node must resolve"));
        assertFalse(entry.isTerminal(), "entry node must offer at least one option");

        for (DialogueNode node : g.getNodes().values()) {
            for (DialogueEdge edge : node.getOptions()) {
                assertTrue(g.getNode(edge.getTargetNodeId()).isPresent(),
                        "edge '" + edge.getText() + "' targets missing node " + edge.getTargetNodeId());
            }
        }
        assertTrue(g.getNodes().values().stream().anyMatch(DialogueNode::isTerminal),
                "graph needs a reachable terminal node");
    }
}
