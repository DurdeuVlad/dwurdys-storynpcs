package com.storynpcs.command;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
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
    @DisplayName("Bundled quickstart YAMLs are versioned, namespaced, loadable, and resolve the demo NPC")
    void bundledQuickstartResources_loadAndResolve() throws Exception {
        String dialogueYaml = readBundledDefinition("dialogues/quickstart_dialogue.yaml");
        String npcYaml = readBundledDefinition("npcs/quickstart_demo.yaml");

        assertTrue(dialogueYaml.contains("schemaVersion: 1"),
                "bundled quickstart dialogue must declare the current schema version");
        assertTrue(npcYaml.contains("schemaVersion: 1"),
                "bundled quickstart NPC must declare the current schema version");

        DefinitionRegistry reg = new DefinitionRegistry();
        var loader = new com.storynpcs.yaml.YamlDefinitionLoader(reg);
        ValidationResult result = ValidationResult.valid();
        assertNotNull(loader.loadDialogue(dialogueYaml, "quickstart_dialogue.yaml", result),
                "bundled dialogue must load cleanly: " + result.formatReport());
        assertNotNull(loader.loadNpc(npcYaml, "quickstart_demo.yaml", result),
                "bundled NPC must load cleanly: " + result.formatReport());
        assertFalse(result.hasErrors(), "bundled quickstart resources must load without errors: "
                + result.formatReport());

        // The bundled dialogue is a working graph: entry resolves, every edge
        // target exists, and a terminal is reachable.
        DialogueGraph g = reg.getDialogue(StoryNpcsCommands.QUICKSTART_DEMO_DIALOGUE).orElseThrow();
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

        assertEquals(StoryNpcsCommands.QUICKSTART_DEMO_NPC, StoryNpcsCommands.resolveDemoNpcId(reg));
    }

    @Test
    @DisplayName("Quickstart does not construct demo definitions from Java")
    void commandsDoNotConstructDemoDefinitions() throws Exception {
        // YAML-first invariant: the commands source must not build or persist demo
        // definition objects — bundled YAML resources are the only demo source.
        String commandsSource = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/storynpcs/command/StoryNpcsCommands.java"));
        assertFalse(commandsSource.contains("buildQuickstartDialogue"),
                "quickstart must not construct a dialogue graph in Java");
        int methodStart = commandsSource.indexOf("private static int quickstart(");
        int methodEnd = commandsSource.indexOf("\n    private static", methodStart + 1);
        assertTrue(methodStart >= 0 && methodEnd > methodStart,
                "quickstart command method must be found and terminated");
        String quickstartSection = commandsSource.substring(methodStart, methodEnd);
        assertFalse(quickstartSection.contains("new NpcDefinition"),
                "quickstart must not construct NPC definitions in Java");
        assertFalse(quickstartSection.contains("createDialogue")
                || quickstartSection.contains("createNpc"),
                "quickstart must not persist scaffolded definitions");
    }

    private static String readBundledDefinition(String relative) throws Exception {
        try (var in = QuickstartLogicTest.class.getClassLoader()
                .getResourceAsStream("data/storynpcs/definitions/" + relative)) {
            assertNotNull(in, "bundled starter definition missing from jar resources: " + relative);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
