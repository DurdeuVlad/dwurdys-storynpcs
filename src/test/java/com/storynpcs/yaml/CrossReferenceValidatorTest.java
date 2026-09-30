package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueAction;
import com.storynpcs.domain.dialogue.DialogueCondition;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;
import com.storynpcs.domain.faction.Faction;
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

    @Test
    void shouldDetectMissingQuestInDialogueAction() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:quest_talk"), "Quest Talk", "start");
        DialogueNode start = new DialogueNode("start", "Take this quest!");
        DialogueEdge edge = new DialogueEdge("Accept", "done");
        edge.setActions(List.of(new DialogueAction(DialogueAction.Type.START_QUEST, "storynpcs:nonexistent_quest", "")));
        start.addOption(edge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("done", "Good luck!"));
        registry.registerDialogue(graph);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("GRAPH_ACTION_QUEST_NOT_FOUND"));
    }

    @Test
    void shouldDetectInvalidFactionPointsValueInAction() {
        Faction faction = new Faction(NamespacedId.of("storynpcs:guards"), "Guards", 1000, 500, 1500);
        registry.registerFaction(faction);

        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:faction_talk"), "Faction Talk", "start");
        DialogueNode start = new DialogueNode("start", "Help us!");
        DialogueEdge edge = new DialogueEdge("Sure", "done");
        edge.setActions(List.of(new DialogueAction(DialogueAction.Type.ADJUST_FACTION, "storynpcs:guards", "not_a_number")));
        start.addOption(edge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("done", "Thanks!"));
        registry.registerDialogue(graph);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("GRAPH_ACTION_FACTION_VALUE_INVALID"));
    }

    @Test
    void shouldDetectInvalidFactionPointsValueInCondition() {
        Faction faction = new Faction(NamespacedId.of("storynpcs:guards"), "Guards", 1000, 500, 1500);
        registry.registerFaction(faction);

        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:cond_talk"), "Cond Talk", "start");
        DialogueNode start = new DialogueNode("start", "Halt!");
        DialogueEdge edge = new DialogueEdge("Let me pass", "passed");
        edge.setConditions(List.of(new DialogueCondition(DialogueCondition.Type.FACTION_POINTS, "storynpcs:guards", ">=", "abc")));
        start.addOption(edge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("passed", "Welcome!"));
        registry.registerDialogue(graph);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("GRAPH_COND_FACTION_POINTS_INVALID"));
    }

    @Test
    void shouldDetectMissingDialogueOptionText() {
        DialogueGraph graph = new DialogueGraph(NamespacedId.of("storynpcs:empty_opt"), "Empty Opt", "start");
        DialogueNode start = new DialogueNode("start", "Hello");
        DialogueEdge edge = new DialogueEdge("", "done");
        start.addOption(edge);
        graph.addNode(start);
        graph.addNode(new DialogueNode("done", "Bye"));
        registry.registerDialogue(graph);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("GRAPH_EDGE_TEXT_MISSING"));
    }

    @Test
    void shouldDetectQuestWithNoObjectives() {
        Quest q = new Quest(NamespacedId.of("storynpcs:no_obj_quest"), "No Obj");
        registry.registerQuest(q);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("QUEST_OBJ_EMPTY"));
    }

    @Test
    void shouldDetectInvalidQuestObjective() {
        Quest q = new Quest(NamespacedId.of("storynpcs:bad_obj_quest"), "Bad Obj");
        q.setObjectives(List.of(
                new com.storynpcs.domain.quest.QuestObjective("obj1", com.storynpcs.domain.quest.QuestObjective.Type.KILL_ENTITY, "zombie", 0)
        ));
        registry.registerQuest(q);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("QUEST_OBJ_COUNT_INVALID"));
    }

    @Test
    void shouldRejectQuestObjectiveAboveProgressionCountLimit() {
        Quest q = new Quest(NamespacedId.of("storynpcs:oversized_obj_quest"), "Oversized Obj");
        q.setObjectives(List.of(new com.storynpcs.domain.quest.QuestObjective(
                "obj1", com.storynpcs.domain.quest.QuestObjective.Type.KILL_ENTITY, "zombie", 100_001)));
        registry.registerQuest(q);

        ValidationResult result = CrossReferenceValidator.validate(registry);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("QUEST_OBJ_COUNT_INVALID"));
    }

    @Test
    void shouldAcceptQuestObjectiveAtProgressionCountLimit() {
        Quest q = new Quest(NamespacedId.of("storynpcs:maximum_obj_quest"), "Maximum Obj");
        q.setObjectives(List.of(new com.storynpcs.domain.quest.QuestObjective(
                "obj1", com.storynpcs.domain.quest.QuestObjective.Type.KILL_ENTITY, "zombie", 100_000)));
        registry.registerQuest(q);

        ValidationResult result = CrossReferenceValidator.validate(registry);

        assertThat(result.getErrors()).noneMatch(e -> e.code().equals("QUEST_OBJ_COUNT_INVALID"));
    }

    @Test
    void shouldDetectQuestRewardWithUnknownFaction() {
        Quest q = new Quest(NamespacedId.of("storynpcs:reward_quest"), "Reward Quest");
        q.setObjectives(List.of(
                new com.storynpcs.domain.quest.QuestObjective("obj1", com.storynpcs.domain.quest.QuestObjective.Type.KILL_ENTITY, "zombie", 1)
        ));
        q.setRewards(List.of(
                new com.storynpcs.domain.quest.QuestReward(com.storynpcs.domain.quest.QuestReward.Type.FACTION_POINTS, "storynpcs:unknown_faction", 100)
        ));
        registry.registerQuest(q);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("QUEST_REWARD_FACTION_NOT_FOUND"));
    }

    @Test
    void shouldDetectDanglingFactionRelationshipReference() {
        Faction faction = new Faction(NamespacedId.of("storynpcs:townsfolk"), "Townsfolk", 1000, 500, 1500);
        faction.setRelationshipTo(NamespacedId.of("storynpcs:unknown_faction"),
                com.storynpcs.domain.faction.FactionStanding.HOSTILE);
        registry.registerFaction(faction);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("REF_FACTION_RELATIONSHIP_MISSING"));
    }

    @Test
    void shouldAcceptFactionRelationshipToAnotherRegisteredFaction() {
        Faction townsfolk = new Faction(NamespacedId.of("storynpcs:townsfolk"), "Townsfolk", 1000, 500, 1500);
        Faction bandits = new Faction(NamespacedId.of("storynpcs:bandits"), "Bandits", 1000, 500, 1500);
        townsfolk.setRelationshipTo(bandits.getId(), com.storynpcs.domain.faction.FactionStanding.HOSTILE);
        registry.registerFaction(townsfolk);
        registry.registerFaction(bandits);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.getErrors()).noneMatch(e -> e.code().equals("REF_FACTION_RELATIONSHIP_MISSING"));
    }

    @Test
    void shouldDetectDanglingTransporterDestinationReference() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:ferryman"), "Ferryman");
        com.storynpcs.domain.role.transporter.TransporterRole transporter =
                new com.storynpcs.domain.role.transporter.TransporterRole();
        transporter.addOfferedDestination(NamespacedId.of("storynpcs:unknown_destination"));
        npc.setTransporter(transporter);
        registry.registerNpc(npc);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("REF_TRANSPORTER_DESTINATION_MISSING"));
    }

    @Test
    void shouldAcceptTransporterOfferingARegisteredDestination() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:ferryman"), "Ferryman");
        com.storynpcs.domain.transport.TransportLocation destination = new com.storynpcs.domain.transport.TransportLocation(
                NamespacedId.of("storynpcs:harbor"), "Harbor", "minecraft:overworld", 100, 64, 200);
        registry.registerTransportLocation(destination);

        com.storynpcs.domain.role.transporter.TransporterRole transporter =
                new com.storynpcs.domain.role.transporter.TransporterRole();
        transporter.addOfferedDestination(destination.getId());
        npc.setTransporter(transporter);
        registry.registerNpc(npc);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.getErrors()).noneMatch(e -> e.code().equals("REF_TRANSPORTER_DESTINATION_MISSING"));
    }

    @Test
    void shouldIgnoreNpcsWithoutATransporterRole() {
        NpcDefinition npc = new NpcDefinition(NamespacedId.of("storynpcs:villager"), "Villager");
        registry.registerNpc(npc);

        ValidationResult result = CrossReferenceValidator.validate(registry);
        assertThat(result.getErrors()).noneMatch(e -> e.code().equals("REF_TRANSPORTER_DESTINATION_MISSING"));
    }
}
