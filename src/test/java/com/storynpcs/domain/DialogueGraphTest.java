package com.storynpcs.domain;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueGraphTest {

    @Test
    void shouldBuildAndTraverseGraphWithDeliberateCycles() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:village_elder"), "Village Elder", "hub");

        DialogueNode hub = new DialogueNode("hub", "Welcome traveler, what do you seek?");
        DialogueNode questTopic = new DialogueNode("quest_topic", "We need help clearing wolves.");
        DialogueNode farewell = new DialogueNode("farewell", "Safe travels!");

        // Edge from hub to quest_topic
        hub.addOption(new DialogueEdge("Tell me about tasks", "quest_topic"));
        // Edge from hub to farewell
        hub.addOption(new DialogueEdge("Goodbye", "farewell"));

        // Intentional cycle: edge from quest_topic back to hub!
        questTopic.addOption(new DialogueEdge("Understood, let's discuss something else.", "hub"));

        graph.addNode(hub);
        graph.addNode(questTopic);
        graph.addNode(farewell);

        assertThat(graph.getEntryNode()).isPresent();
        assertThat(graph.getEntryNode().get().getId()).isEqualTo("hub");

        // Reachability & cycle verification
        assertThat(graph.canReach("hub", "quest_topic")).isTrue();
        assertThat(graph.canReach("quest_topic", "hub")).isTrue(); // Cycle confirmed!
        assertThat(graph.canReach("hub", "farewell")).isTrue();
        assertThat(graph.canReach("farewell", "hub")).isFalse(); // farewell is terminal

        assertThat(farewell.isTerminal()).isTrue();
        assertThat(hub.isTerminal()).isFalse();
    }
}
