package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DialogueGraphLayoutTest {

    @Test
    @DisplayName("DialogueGraphLayout converts from DialogueGraph with automatic layout and entry detection")
    void testFromDialogueGraph() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("test:talk"), "Test Talk", "hub");
        DialogueNode hub = new DialogueNode("hub", "Welcome to the village!");
        hub.addOption(new DialogueEdge("Who are you?", "about"));
        hub.addOption(new DialogueEdge("Goodbye.", "bye"));

        DialogueNode about = new DialogueNode("about", "I am the village elder.");
        about.addOption(new DialogueEdge("Tell me more.", "hub"));

        DialogueNode bye = new DialogueNode("bye", "Safe travels!");

        graph.addNode(hub);
        graph.addNode(about);
        graph.addNode(bye);

        DialogueGraphLayout layout = DialogueGraphLayout.fromDialogueGraph(graph);

        assertEquals("hub", layout.getEntryNodeId());
        assertEquals(3, layout.getNodes().size());
        assertEquals(3, layout.getEdges().size());

        VisualNode hubNode = layout.getNodes().get("hub");
        assertNotNull(hubNode);
        assertTrue(hubNode.isEntryNode());

        VisualNode aboutNode = layout.getNodes().get("about");
        assertNotNull(aboutNode);
        assertFalse(aboutNode.isEntryNode());
    }

    @Test
    @DisplayName("DialogueGraphLayout detects intentional narrative cycles")
    void testCycleDetection() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("test:loop"), "Loop Talk", "start");
        DialogueNode start = new DialogueNode("start", "Start");
        start.addOption(new DialogueEdge("Go to B", "node_b"));

        DialogueNode nodeB = new DialogueNode("node_b", "Node B");
        nodeB.addOption(new DialogueEdge("Loop back to Start", "start"));

        graph.addNode(start);
        graph.addNode(nodeB);

        DialogueGraphLayout layout = DialogueGraphLayout.fromDialogueGraph(graph);

        assertTrue(layout.hasCycles(), "Graph must detect the cycle between start and node_b");

        boolean foundCyclicEdge = layout.getEdges().stream().anyMatch(VisualEdge::isCyclic);
        assertTrue(foundCyclicEdge, "At least one edge must be marked cyclic");
    }

    @Test
    @DisplayName("Round-trip conversion to DialogueGraph preserves nodes and connections")
    void testRoundTripConversion() {
        DialogueGraphLayout layout = new DialogueGraphLayout();
        layout.setEntryNodeId("root");

        VisualNode root = new VisualNode("root", "Root text", 0, 0);
        VisualNode child = new VisualNode("child", "Child text", 200, 100);
        layout.addNode(root);
        layout.addNode(child);

        layout.addEdge(new VisualEdge("root", "child", "Go child"));

        DialogueGraph graph = layout.toDialogueGraph(NamespacedId.of("test:roundtrip"), "Roundtrip");

        assertEquals("root", graph.getEntryNodeId());
        assertEquals(2, graph.getNodes().size());
        assertTrue(graph.getNode("root").isPresent());
        assertTrue(graph.getNode("child").isPresent());

        DialogueNode convertedRoot = graph.getNode("root").get();
        assertEquals(1, convertedRoot.getOptions().size());
        assertEquals("child", convertedRoot.getOptions().get(0).getTargetNodeId());
        assertEquals("Go child", convertedRoot.getOptions().get(0).getText());
    }

    @Test
    @DisplayName("Round-trip preserves per-node speaker and sound fields")
    void testSpeakerAndSoundRoundTrip() {
        DialogueGraphLayout layout = new DialogueGraphLayout();
        layout.setEntryNodeId("root");

        VisualNode root = new VisualNode("root", "Root text", 0, 0);
        root.setSpeaker("Innkeeper Mara");
        root.setSound("minecraft:entity.villager.ambient");
        VisualNode child = new VisualNode("child", "Child text", 200, 100);
        // child deliberately leaves speaker/sound at defaults
        layout.addNode(root);
        layout.addNode(child);
        layout.addEdge(new VisualEdge("root", "child", "Go child"));

        DialogueGraph graph = layout.toDialogueGraph(NamespacedId.of("test:speaker_rt"), "RT");
        DialogueNode convertedRoot = graph.getNode("root").orElseThrow();
        assertEquals("Innkeeper Mara", convertedRoot.getSpeaker());
        assertEquals("minecraft:entity.villager.ambient", convertedRoot.getSound());
        assertEquals("", graph.getNode("child").orElseThrow().getSpeaker(), "unset speaker stays blank");

        // and back: a graph -> layout -> graph round-trip must not drop them either
        DialogueGraphLayout relayout = DialogueGraphLayout.fromDialogueGraph(graph);
        DialogueGraph reexported = relayout.toDialogueGraph(NamespacedId.of("test:speaker_rt"), "RT");
        assertEquals("Innkeeper Mara", reexported.getNode("root").orElseThrow().getSpeaker());
        assertEquals("minecraft:entity.villager.ambient", reexported.getNode("root").orElseThrow().getSound());
    }

    @Test
    @DisplayName("Canvas coordinate transformations with pan and zoom")
    void testCoordinateTransformations() {
        double panX = 100;
        double panY = 50;
        double zoom = 2.0;

        double screenX = 300;
        double screenY = 150;

        double canvasX = DialogueGraphLayout.screenToCanvasX(screenX, panX, zoom);
        double canvasY = DialogueGraphLayout.screenToCanvasY(screenY, panY, zoom);

        assertEquals(100.0, canvasX, 0.001); // (300 - 100) / 2 = 100
        assertEquals(50.0, canvasY, 0.001);  // (150 - 50) / 2 = 50

        double backScreenX = DialogueGraphLayout.canvasToScreenX(canvasX, panX, zoom);
        double backScreenY = DialogueGraphLayout.canvasToScreenY(canvasY, panY, zoom);

        assertEquals(screenX, backScreenX, 0.001);
        assertEquals(screenY, backScreenY, 0.001);
    }

    @Test
    @DisplayName("GraphEditorState handles node selection, dragging, and connecting")
    void testEditorStateInteractions() {
        DialogueGraphLayout layout = new DialogueGraphLayout();
        VisualNode nodeA = new VisualNode("A", "Node A", 10, 10);
        VisualNode nodeB = new VisualNode("B", "Node B", 300, 10);
        layout.addNode(nodeA);
        layout.addNode(nodeB);

        GraphEditorState state = new GraphEditorState(layout);

        // Node hit test at screen point
        VisualNode hit = state.findNodeAtScreen(50, 40);
        assertNotNull(hit);
        assertEquals("A", hit.getId());

        // Node dragging
        state.startDragNode("A", 50, 40);
        assertTrue(state.isDragging());
        state.dragNode(150, 90); // drag +100x, +50y
        state.endDrag();
        assertFalse(state.isDragging());

        assertEquals(110.0, nodeA.getX(), 0.001); // 10 + 100
        assertEquals(60.0, nodeA.getY(), 0.001);  // 10 + 50

        // Edge connection
        state.setConnectingSourceNodeId("A");
        state.connectEdge("B", "Connect to B");

        assertEquals(1, layout.getEdges().size());
        assertEquals("A", layout.getEdges().get(0).getSourceNodeId());
        assertEquals("B", layout.getEdges().get(0).getTargetNodeId());
    }
}