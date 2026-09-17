package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossReferenceValidatorTest {
    private DefinitionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefinitionRegistry();
    }

    @Test
    void shouldDetectMissingNpcDialogueReference() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:guard"), "Guard");
        npc.setDialogueId(NamespacedId.of("storynpcs:missing_dialogue"));
        registry.registerNpc(npc);

        ValidationResult result = CrossReferenceValidator.validate(registry);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("REF_NPC_DIALOGUE_MISSING"));
    }

    @Test
    void shouldDetectDanglingDialogueEdge() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:intro"), "Intro", "start");
        DialogueNode start = new DialogueNode("start", "Hello!");
        // Points to non-existent node
        start.addOption(new DialogueEdge("Go away", "non_existent_node"));
        graph.addNode(start);
        registry.registerDialogue(graph);

        ValidationResult result = CrossReferenceValidator.validate(registry);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("GRAPH_DANGLING_EDGE"));
    }

    @Test
    void shouldDetectCircularQuestPrerequisites() {
        Quest q1 = new Quest(NamespacedId.of("storynpcs:quest_a"), "Quest A");
        q1.setPrerequisites(List.of(NamespacedId.of("storynpcs:quest_b")));

        Quest q2 = new Quest(NamespacedId.of("storynpcs:quest_b"), "Quest B");
        q2.setPrerequisites(List.of(NamespacedId.of("storynpcs:quest_a"))); // Circular!

        registry.registerQuest(q1);
        registry.registerQuest(q2);

        ValidationResult result = CrossReferenceValidator.validate(registry);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("CYCLE_QUEST_PREREQUISITE"));
    }
}
