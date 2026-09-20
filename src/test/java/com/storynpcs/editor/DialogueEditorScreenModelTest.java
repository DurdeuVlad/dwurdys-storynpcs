package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DialogueEditorScreenModelTest {

    @Test
    @DisplayName("DialogueEditorScreenModel initializes from DialogueGraph and tracks status")
    void testModelInitialization() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:elder_talk"), "Elder Conversation", "entry");
        graph.addNode(new DialogueNode("entry", "Hello traveler."));

        DialogueEditorScreenModel model = new DialogueEditorScreenModel(graph, null);

        assertEquals(NamespacedId.of("storynpcs:elder_talk"), model.getDialogueId());
        assertEquals("Elder Conversation", model.getTitle());
        assertEquals(1, model.getLayout().getNodes().size());
        assertEquals("entry", model.getLayout().getEntryNodeId());
        assertFalse(model.hasUnsavedChanges());
    }

    @Test
    @DisplayName("Adding and editing nodes updates layout, selection, and dirty flag")
    void testAddAndEditNodes() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);

        VisualNode n1 = model.addNode("node_1", "First node", 100, 50);
        assertNotNull(n1);
        assertEquals("node_1", model.getEditorState().getSelectedNodeId());
        assertEquals("node_1", model.getLayout().getEntryNodeId(), "First added node should become entry node");
        assertTrue(model.hasUnsavedChanges());

        model.updateSelectedNodeText("Updated text content");
        assertEquals("Updated text content", model.getLayout().getNodes().get("node_1").getText());

        VisualNode n2 = model.addNode("node_2", "Second node", 300, 50);
        assertEquals("node_2", model.getEditorState().getSelectedNodeId());
        assertEquals(2, model.getLayout().getNodes().size());

        // Set second node as entry
        model.setAsEntryNode("node_2");
        assertEquals("node_2", model.getLayout().getEntryNodeId());
        assertTrue(model.getLayout().getNodes().get("node_2").isEntryNode());
        assertFalse(model.getLayout().getNodes().get("node_1").isEntryNode());
    }

    @Test
    @DisplayName("Connecting edges and removing nodes cleans up connections")
    void testEdgeConnectingAndNodeRemoval() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("src", "Source", 0, 0);
        model.addNode("dst", "Destination", 200, 0);

        model.startConnectingEdge("src");
        assertEquals("src", model.getEditorState().getConnectingSourceNodeId());

        model.completeConnectingEdge("dst", "Go to destination");
        assertNull(model.getEditorState().getConnectingSourceNodeId());
        assertEquals(1, model.getLayout().getEdges().size());

        VisualEdge edge = model.getLayout().getEdges().get(0);
        assertEquals("src", edge.getSourceNodeId());
        assertEquals("dst", edge.getTargetNodeId());
        assertEquals("Go to destination", edge.getText());

        // Select and remove destination node
        model.getEditorState().setSelectedNodeId("dst");
        model.removeSelectedNode();

        assertEquals(1, model.getLayout().getNodes().size());
        assertEquals(0, model.getLayout().getEdges().size(), "Edges attached to removed node must be deleted");
    }

    @Test
    @DisplayName("Saving graph invokes callback, exports valid DialogueGraph, and clears dirty flag")
    void testSaveWorkflow() {
        AtomicReference<DialogueGraph> saved = new AtomicReference<>();
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(
                new DialogueGraph(NamespacedId.of("storynpcs:save_test"), "Save Test", "start"),
                saved::set
        );

        model.addNode("start", "Start text", 0, 0);
        model.addNode("next", "Next text", 200, 0);
        model.startConnectingEdge("start");
        model.completeConnectingEdge("next", "Next choice");

        assertTrue(model.hasUnsavedChanges());

        model.save();

        assertNotNull(saved.get(), "Save callback must have received exported graph");
        assertTrue(model.hasUnsavedChanges(), "Dirty flag stays set until the server confirms the save");

        DialogueGraph exported = saved.get();
        assertEquals(NamespacedId.of("storynpcs:save_test"), exported.getId());
        assertEquals(2, exported.getNodes().size());
        assertTrue(exported.getNode("start").isPresent());
        assertEquals(1, exported.getNode("start").get().getOptions().size());
        assertEquals("next", exported.getNode("start").get().getOptions().get(0).getTargetNodeId());

        model.onSaveResult(true, "Saved.");
        assertFalse(model.hasUnsavedChanges(), "Dirty flag clears only on confirmed save");
        assertEquals("Saved.", model.getStatusMessage());

        // A rejected save keeps the dirty flag so no work is silently lost
        model.addNode("extra", "More", 400, 0);
        model.save();
        model.onSaveResult(false, "Rejected.");
        assertTrue(model.hasUnsavedChanges());
        assertEquals("Rejected.", model.getStatusMessage());
    }

    @Test
    @DisplayName("Node text edits are live-committed: switching selection then exporting keeps both nodes' text")
    void testNodeTextEditPersistsAcrossSelectionChangeAndExport() {
        AtomicReference<DialogueGraph> saved = new AtomicReference<>();
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:text_edit"), "Text Edit", "a");
        graph.addNode(new DialogueNode("a", "Original A"));
        graph.addNode(new DialogueNode("b", "Original B"));
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(graph, saved::set);

        // Select A, edit its text — the model commits immediately (auto-commit contract)
        model.getEditorState().setSelectedNodeId("a");
        model.updateSelectedNodeText("Rewritten A with a much longer line that would overflow a fixed box");

        // Switch to B without any explicit apply — A's text must already be committed
        model.getEditorState().setSelectedNodeId("b");
        model.updateSelectedNodeText("Rewritten B");

        model.save();
        DialogueGraph exported = saved.get();
        assertNotNull(exported);
        assertEquals("Rewritten A with a much longer line that would overflow a fixed box",
                exported.getNode("a").orElseThrow().getText());
        assertEquals("Rewritten B", exported.getNode("b").orElseThrow().getText());
        assertTrue(model.hasUnsavedChanges());
    }

    @Test
    @DisplayName("Entry node deletion is blocked with a clear status; non-entry nodes delete normally")
    void testEntryNodeDeletionBlocked() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("entry", "Start", 0, 0);
        model.addNode("other", "Other", 200, 0);
        model.setAsEntryNode("entry");

        model.getEditorState().setSelectedNodeId("entry");
        assertFalse(model.removeSelectedNode(), "entry node deletion must be refused");
        assertNotNull(model.getLayout().getNodes().get("entry"), "entry node must survive");
        assertTrue(model.getStatusMessage().contains("entry"), "refusal must explain itself");

        model.getEditorState().setSelectedNodeId("other");
        assertTrue(model.removeSelectedNode());
        assertNull(model.getLayout().getNodes().get("other"));
        assertEquals("entry", model.getLayout().getEntryNodeId(), "entry flag untouched by other deletion");
    }

    @Test
    @DisplayName("Deleting an edge removes only that edge — endpoints and other edges intact")
    void testRemoveSelectedEdge() {
        AtomicReference<DialogueGraph> saved = new AtomicReference<>();
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, saved::set);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.addNode("c", "C", 400, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "to B");
        model.startConnectingEdge("a");
        model.completeConnectingEdge("c", "to C");
        assertEquals(2, model.getLayout().getEdges().size());

        VisualEdge doomed = model.getLayout().getEdges().get(0);
        model.getEditorState().setSelectedEdge(doomed);
        assertTrue(model.removeSelectedEdge());

        assertEquals(1, model.getLayout().getEdges().size());
        assertEquals("to C", model.getLayout().getEdges().get(0).getText());
        assertEquals(3, model.getLayout().getNodes().size(), "endpoints must survive");
        assertTrue(model.hasUnsavedChanges());

        model.save();
        DialogueGraph exported = saved.get();
        assertEquals(1, exported.getNode("a").orElseThrow().getOptions().size());
        assertEquals("c", exported.getNode("a").orElseThrow().getOptions().get(0).getTargetNodeId());
    }

    @Test
    @DisplayName("Edge labels are hit-testable at their rendered midpoint box")
    void testFindEdgeAtScreen() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);      // center canvas (80, 40)
        model.addNode("b", "B", 300, 0);    // center canvas (380, 40)
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "to B");

        // zoom=1, pan=0 → canvas == screen; label midpoint is (230, 40)
        assertNotNull(model.getEditorState().findEdgeAtScreen(230, 40));
        assertNotNull(model.getEditorState().findEdgeAtScreen(230 + 20, 40 + 4), "inside the 50x12 label box");
        assertNull(model.getEditorState().findEdgeAtScreen(230, 90), "outside the label box must miss");
        assertNull(model.getEditorState().findEdgeAtScreen(10, 10));
    }

    @Test
    @DisplayName("Speaker and sound edits mark dirty and survive export")
    void testSpeakerAndSoundEditing() {
        AtomicReference<DialogueGraph> saved = new AtomicReference<>();
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, saved::set);
        model.addNode("a", "A", 0, 0);

        model.getEditorState().setSelectedNodeId("a");
        model.updateSelectedNodeSpeaker("Innkeeper Mara");
        model.updateSelectedNodeSound("minecraft:entity.villager.yes");
        assertTrue(model.hasUnsavedChanges());

        model.save();
        DialogueNode exported = saved.get().getNode("a").orElseThrow();
        assertEquals("Innkeeper Mara", exported.getSpeaker());
        assertEquals("minecraft:entity.villager.yes", exported.getSound());
    }
}