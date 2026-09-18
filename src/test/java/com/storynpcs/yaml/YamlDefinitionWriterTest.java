package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        // No leftover .tmp file — write was atomic
        assertFalse(Files.exists(written.resolveSibling("written_dialogue.yaml.tmp")));

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
    @DisplayName("fileNameFor sanitizes unsafe characters")
    void testFileNameSanitization() {
        assertEquals("guard_captain", YamlDefinitionWriter.fileNameFor(NamespacedId.of("storynpcs:guard_captain")));
        // '/' is legal in a namespaced path but must not become a directory separator on disk
        assertEquals("a_b_c", YamlDefinitionWriter.fileNameFor(NamespacedId.of("ns:a/b/c")));
        assertEquals("unnamed", YamlDefinitionWriter.fileNameFor(null));
    }
}
