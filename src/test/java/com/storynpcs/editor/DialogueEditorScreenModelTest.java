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

    @Test
    @DisplayName("START_QUEST action: set, prefill, replace-in-place, and clear")
    void testStartQuestActionEditing() {
        AtomicReference<DialogueGraph> saved = new AtomicReference<>();
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, saved::set);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "Take the bounty");
        VisualEdge edge = model.getLayout().getEdges().get(0);
        model.getEditorState().setSelectedEdge(edge);

        assertEquals("", model.getSelectedEdgeStartQuest(), "no action initially");

        model.setSelectedEdgeStartQuest("storynpcs:bounty_goblins");
        assertTrue(model.hasUnsavedChanges());
        assertEquals("storynpcs:bounty_goblins", model.getSelectedEdgeStartQuest());

        // Replacing the target keeps the action's position and value payload
        edge.getActions().add(new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.GIVE_ITEM, "minecraft:paper", "1"));
        model.setSelectedEdgeStartQuest("storynpcs:other_quest");
        assertEquals(2, edge.getActions().size());
        assertEquals(com.storynpcs.domain.dialogue.DialogueAction.Type.START_QUEST,
                edge.getActions().get(0).getType(), "START_QUEST stays first");
        assertEquals("storynpcs:other_quest", edge.getActions().get(0).getTarget());

        model.save();
        DialogueEdge exported = saved.get().getNode("a").orElseThrow().getOptions().get(0);
        assertEquals(2, exported.getActions().size());
        assertEquals("storynpcs:other_quest", exported.getActions().get(0).getTarget());

        // Blank clears the action but leaves the rest of the list alone
        model.setSelectedEdgeStartQuest("   ");
        assertEquals("", model.getSelectedEdgeStartQuest());
        assertEquals(1, edge.getActions().size());
        assertEquals(com.storynpcs.domain.dialogue.DialogueAction.Type.GIVE_ITEM,
                edge.getActions().get(0).getType());
    }

    // ── P5-3 authoring surface ───────────────────────────────────────────────

    @Test
    @DisplayName("Undo/redo restores prior layout states; coalesced field edits collapse to one step")
    void testUndoRedo() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        assertEquals(2, model.getLayout().getNodes().size());

        assertTrue(model.undo());
        assertNull(model.getLayout().getNodes().get("b"), "undo removes the second add");
        assertTrue(model.redo());
        assertNotNull(model.getLayout().getNodes().get("b"), "redo restores it");

        // Keystroke-frequency edits under one coalesce key collapse: two text
        // updates = one undo step back to "A".
        model.getEditorState().setSelectedNodeId("a");
        model.updateSelectedNodeText("A1");
        model.updateSelectedNodeText("A12");
        assertTrue(model.undo());
        assertEquals("A", model.getLayout().getNodes().get("a").getText(),
                "coalesced keystroke edits revert in a single undo");
        assertTrue(model.canRedo(), "undo leaves the undone step on the redo stack");
    }

    @Test
    @DisplayName("Redo survives an undo and is cleared by a fresh mutation")
    void testRedoLifecycle() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.undo();
        assertTrue(model.canRedo());
        model.addNode("c", "C", 400, 0);
        assertFalse(model.canRedo(), "a new mutation clears the redo stack");
        assertNotNull(model.getLayout().getNodes().get("c"));
    }

    @Test
    @DisplayName("Undo snapshots are deep copies — post-snapshot mutation of live lists cannot corrupt them")
    void testUndoSnapshotIsolation() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "opt");
        VisualEdge edge = model.getLayout().getEdges().get(0);
        model.getEditorState().setSelectedEdge(edge);
        model.addSelectedEdgeCondition(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.QUEST_STATUS,
                "storynpcs:q", "==", "DONE"));

        // A subsequent edit pushes a snapshot that must deep-copy the condition list
        model.getEditorState().setSelectedNodeId("a");
        model.updateSelectedNodeText("edited");

        // Corrupt the live condition list directly — if the snapshot aliased it,
        // undo would restore the corrupted state.
        model.getLayout().getEdges().get(0).getConditions().clear();
        model.getLayout().getEdges().get(0).getConditions()
                .add(new com.storynpcs.domain.dialogue.DialogueCondition(
                        com.storynpcs.domain.dialogue.DialogueCondition.Type.HAS_PERMISSION, "x", "", ""));

        assertTrue(model.undo());
        var restored = model.getLayout().getEdges().get(0).getConditions();
        assertEquals(1, restored.size(), "snapshot kept the original condition");
        assertEquals("storynpcs:q", restored.get(0).getTarget(),
                "the restored condition is the pre-mutation one, not the corrupted list");
    }

    @Test
    @DisplayName("Search spans node and edge payload fields; match cycling wraps both ends")
    void testSearch() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "Hello there", 0, 0);
        model.addNode("b", "Farewell", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "Take the bounty");

        var hits = model.search("bounty");
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).edge(), "edge option text is searchable");

        hits = model.search("fare");
        assertEquals(1, hits.size());
        assertEquals("b", hits.get(0).nodeId());

        // Cycling walks every match and wraps.
        model.search("e"); // matches both nodes and the edge
        int total = model.searchMatches().size();
        assertTrue(total >= 2);
        for (int i = 0; i < total; i++) assertTrue(model.nextSearchMatch(true));
        assertTrue(model.nextSearchMatch(true), "cycle wraps past the last match");
        boolean somethingSelected = model.getEditorState().getSelectedNodeId() != null
                || model.getEditorState().getSelectedEdge() != null;
        assertTrue(somethingSelected, "cycling selects each match");

        assertTrue(model.search("zzzz").isEmpty());
        assertTrue(model.searchMatches().isEmpty(), "empty result resets the match list");
    }

    @Test
    @DisplayName("Renaming a node remaps edges, entry id, and selection; collisions are refused")
    void testRenameSelectedNode() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "go");

        model.getEditorState().setSelectedNodeId("a");
        assertTrue(model.renameSelectedNode("root"));
        assertNull(model.getLayout().getNodes().get("a"));
        assertNotNull(model.getLayout().getNodes().get("root"));
        assertEquals("root", model.getLayout().getEntryNodeId());
        assertEquals("root", model.getLayout().getEdges().get(0).getSourceNodeId());
        assertEquals("root", model.getEditorState().getSelectedNodeId());

        assertFalse(model.renameSelectedNode("b"), "colliding ids are refused");
        assertNotNull(model.getLayout().getNodes().get("root"), "original survives a refused rename");
        assertFalse(model.renameSelectedNode(""), "blank ids are refused");
        assertFalse(model.renameSelectedNode("  "), "whitespace ids are refused");
        assertEquals(2, model.getLayout().getNodes().size());
    }

    @Test
    @DisplayName("Node position edits are undoable and reject non-finite values")
    void testNodePositionEditing() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.getEditorState().setSelectedNodeId("a");

        assertTrue(model.updateSelectedNodePosition(150, -40));
        assertEquals(150, model.getLayout().getNodes().get("a").getX());
        assertEquals(-40, model.getLayout().getNodes().get("a").getY());

        assertFalse(model.updateSelectedNodePosition(Double.NaN, 0));
        assertFalse(model.updateSelectedNodePosition(0, Double.POSITIVE_INFINITY));
        assertEquals(150, model.getLayout().getNodes().get("a").getX(), "invalid input rejected");

        assertTrue(model.undo());
        assertEquals(0, model.getLayout().getNodes().get("a").getX(), "undo restores the prior position");
    }

    @Test
    @DisplayName("Edge fields edit in place: text, once-only, textKey, retarget; bad retarget refused")
    void testEdgePropertyEditing() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.addNode("c", "C", 400, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "go");
        model.getEditorState().setSelectedEdge(model.getLayout().getEdges().get(0));

        model.updateSelectedEdgeText("new label");
        model.setSelectedEdgeOnceOnly(true);
        model.updateSelectedEdgeTextKey("dlg.opt.b");
        VisualEdge edge = model.getLayout().getEdges().get(0);
        assertEquals("new label", edge.getText());
        assertTrue(edge.isOnceOnly());
        assertEquals("dlg.opt.b", edge.getTextKey());

        assertTrue(model.retargetSelectedEdge("c"));
        assertEquals("c", edge.getTargetNodeId());
        assertFalse(model.retargetSelectedEdge("missing"), "retarget to a missing node is refused");
        assertEquals("c", edge.getTargetNodeId(), "refused retarget leaves the edge intact");
    }

    @Test
    @DisplayName("Condition and action CRUD applies to the selected edge; availability edits apply to the graph")
    void testConditionAndActionCrud() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "go");
        model.getEditorState().setSelectedEdge(model.getLayout().getEdges().get(0));

        var cond = new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.QUEST_STATUS,
                "storynpcs:q", "==", "IN_PROGRESS");
        model.addSelectedEdgeCondition(cond);
        assertEquals(1, model.getSelectedEdgeConditions().size());
        model.updateSelectedEdgeCondition(0, new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_STANDING,
                "storynpcs:f", ">=", "10"));
        assertEquals(com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_STANDING,
                model.getSelectedEdgeConditions().get(0).getType());
        model.removeSelectedEdgeCondition(0);
        assertTrue(model.getSelectedEdgeConditions().isEmpty());
        model.removeSelectedEdgeCondition(-1); // bounds-safe no-op

        var act = new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.START_QUEST, "storynpcs:q2", "");
        model.addSelectedEdgeAction(act);
        model.updateSelectedEdgeAction(0, new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.GIVE_ITEM, "minecraft:paper", "2"));
        assertEquals("minecraft:paper", model.getSelectedEdgeActions().get(0).getTarget());
        model.removeSelectedEdgeAction(0);
        assertTrue(model.getSelectedEdgeActions().isEmpty());

        // Dialogue-level availability
        model.addAvailabilityCondition(cond);
        assertEquals(1, model.getAvailability().size());
        model.updateAvailabilityCondition(0, new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.HAS_PERMISSION, "perm.test", "", ""));
        assertEquals(com.storynpcs.domain.dialogue.DialogueCondition.Type.HAS_PERMISSION,
                model.getAvailability().get(0).getType());
        model.removeAvailabilityCondition(0);
        assertTrue(model.getAvailability().isEmpty());
    }

    @Test
    @DisplayName("validate() runs the server validator — a dangling edge reports a diagnostic")
    void testValidate() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        assertTrue(model.validate().isEmpty(), "healthy single-node graph validates clean");

        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "go");
        // Corrupt the target id directly (UI never produces this; YAML/import can)
        model.getLayout().getEdges().get(0).setTargetNodeId("missing");
        var diags = model.validate();
        assertTrue(diags.stream().anyMatch(d -> d.code().equals("DIALOGUE_DANGLING_EDGE")),
                "dangling edge must surface the same diagnostic the save path uses: " + diags);
    }

    @Test
    @DisplayName("Preview walks breadth-first from entry, annotates gated options, and survives cycles")
    void testPreview() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "Start", 0, 0);
        model.addNode("b", "End", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "continue");
        model.getEditorState().setSelectedEdge(model.getLayout().getEdges().get(0));
        model.setSelectedEdgeOnceOnly(true);
        model.addSelectedEdgeCondition(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.QUEST_STATUS,
                "storynpcs:q", "==", "DONE"));
        model.addSelectedEdgeAction(new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.START_QUEST, "storynpcs:q", ""));
        // Self-loop on b — preview must terminate anyway
        model.startConnectingEdge("b");
        model.completeConnectingEdge("b", "loop");

        var preview = model.preview();
        assertEquals(2, preview.lines().size(), "BFS visits each node once despite the cycle");
        assertEquals("a", preview.lines().get(0).nodeId());
        assertTrue(preview.lines().get(0).entry());
        String opt = preview.lines().get(0).options().get(0);
        assertTrue(opt.contains("[if 1 cond]") && opt.contains("[1 action]") && opt.contains("[once]"),
                "option carries gate/action annotations: " + opt);
    }

    @Test
    @DisplayName("Export/import round-trips every authored field; bad JSON leaves the graph untouched")
    void testImportExportRoundTrip() {
        AtomicReference<DialogueGraph> saved = new AtomicReference<>();
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, saved::set);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "go");
        model.getEditorState().setSelectedEdge(model.getLayout().getEdges().get(0));
        model.setSelectedEdgeOnceOnly(true);
        model.updateSelectedEdgeTextKey("dlg.key");
        model.addSelectedEdgeCondition(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.QUEST_STATUS,
                "storynpcs:q", "==", "DONE"));
        model.addSelectedEdgeAction(new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.START_QUEST, "storynpcs:q", ""));
        model.getEditorState().setSelectedNodeId("a");
        model.updateSelectedNodeTextKey("node.key");
        model.setTitleKey("dialogue.title.key");
        model.addAvailabilityCondition(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.HAS_PERMISSION, "perm.test", "", ""));

        String json = model.exportJson();
        assertFalse(json.isBlank());

        DialogueEditorScreenModel copy = new DialogueEditorScreenModel(null, null);
        assertTrue(copy.importJson(json));
        assertEquals("dialogue.title.key", copy.getTitleKey());
        assertEquals(1, copy.getAvailability().size());
        assertEquals("node.key", copy.getLayout().getNodes().get("a").getTextKey());
        var copiedEdge = copy.getLayout().getEdges().get(0);
        assertTrue(copiedEdge.isOnceOnly());
        assertEquals("dlg.key", copiedEdge.getTextKey());
        assertEquals(1, copiedEdge.getConditions().size());
        assertEquals(1, copiedEdge.getActions().size());
        assertEquals("storynpcs:q", copiedEdge.getActions().get(0).getTarget());

        // Import is one undoable step
        assertTrue(copy.undo());
        assertTrue(copy.getLayout().getNodes().isEmpty());

        // Garbage JSON is refused without touching the working graph
        assertFalse(model.importJson("not a dialogue graph"));
        assertEquals(2, model.getLayout().getNodes().size());
    }

    @Test
    @DisplayName("Tab cycling walks nodes then edges and wraps at both ends")
    void testCycleSelection() {
        DialogueEditorScreenModel model = new DialogueEditorScreenModel(null, null);
        model.addNode("a", "A", 0, 0);
        model.addNode("b", "B", 200, 0);
        model.startConnectingEdge("a");
        model.completeConnectingEdge("b", "go");

        model.getEditorState().setSelectedNodeId(null);
        model.cycleSelection(true);
        assertEquals("a", model.getEditorState().getSelectedNodeId());
        model.cycleSelection(true);
        assertEquals("b", model.getEditorState().getSelectedNodeId());
        model.cycleSelection(true);
        assertNull(model.getEditorState().getSelectedNodeId());
        assertNotNull(model.getEditorState().getSelectedEdge(), "edge comes after nodes");
        model.cycleSelection(true);
        assertEquals("a", model.getEditorState().getSelectedNodeId(), "wraps to the start");

        model.cycleSelection(false);
        assertNotNull(model.getEditorState().getSelectedEdge(), "backward wrap lands on the edge");
    }
}
