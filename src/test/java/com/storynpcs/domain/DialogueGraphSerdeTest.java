package com.storynpcs.domain;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueAction;
import com.storynpcs.domain.dialogue.DialogueCondition;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueGraphSerde;
import com.storynpcs.domain.dialogue.DialogueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DialogueGraphSerdeTest {

    @Test
    @DisplayName("JSON round-trip preserves all graph fields")
    void testRoundTrip() {
        DialogueGraph graph = new DialogueGraph(
                NamespacedId.of("storynpcs:test_dialogue"), "Test Dialogue", "start");
        DialogueNode start = new DialogueNode("start", "Hello there.");
        start.setSound("minecraft:entity.villager.ambient");
        DialogueEdge edge = new DialogueEdge("Take quest", "quest_node");
        edge.setOnceOnly(true);
        edge.setConditions(List.of(new DialogueCondition(
                DialogueCondition.Type.FACTION_POINTS, "storynpcs:guard", ">=", "100")));
        edge.setActions(List.of(new DialogueAction(
                DialogueAction.Type.START_QUEST, "storynpcs:bounty", "")));
        start.addOption(edge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("quest_node", "End."));

        String json = DialogueGraphSerde.toJson(graph);
        DialogueGraph restored = DialogueGraphSerde.fromJson(json).orElseThrow();

        assertEquals(graph.getId(), restored.getId());
        assertEquals("Test Dialogue", restored.getTitle());
        assertEquals("start", restored.getEntryNodeId());
        assertEquals(2, restored.getNodes().size());

        DialogueNode rStart = restored.getNode("start").orElseThrow();
        assertEquals("Hello there.", rStart.getText());
        assertEquals("minecraft:entity.villager.ambient", rStart.getSound());
        assertEquals(1, rStart.getOptions().size());

        DialogueEdge rEdge = rStart.getOptions().get(0);
        assertEquals("Take quest", rEdge.getText());
        assertEquals("quest_node", rEdge.getTargetNodeId());
        assertTrue(rEdge.isOnceOnly());
        assertEquals(1, rEdge.getConditions().size());
        assertEquals(DialogueCondition.Type.FACTION_POINTS, rEdge.getConditions().get(0).getType());
        assertEquals(1, rEdge.getActions().size());
        assertEquals(DialogueAction.Type.START_QUEST, rEdge.getActions().get(0).getType());
        assertEquals("storynpcs:bounty", rEdge.getActions().get(0).getTarget());
    }

    @Test
    @DisplayName("fromJson returns empty for null, blank, and malformed input")
    void testFromJsonRobustness() {
        assertTrue(DialogueGraphSerde.fromJson(null).isEmpty());
        assertTrue(DialogueGraphSerde.fromJson("").isEmpty());
        assertTrue(DialogueGraphSerde.fromJson("   ").isEmpty());
        assertTrue(DialogueGraphSerde.fromJson("{not valid json").isEmpty());
    }
}
