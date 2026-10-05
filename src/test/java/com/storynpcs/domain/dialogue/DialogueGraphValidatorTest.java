package com.storynpcs.domain.dialogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.DiagnosticError;
import com.storynpcs.domain.common.NamespacedId;

class DialogueGraphValidatorTest {

    private final DialogueGraphValidator validator = new DialogueGraphValidator();

    private static DialogueGraph linear() {
        DialogueGraph g = new DialogueGraph(NamespacedId.of("storynpcs", "test"), "Test", "start");
        DialogueNode start = new DialogueNode("start", "Hello");
        start.getOptions().add(new DialogueEdge("Bye", "end"));
        g.addNode(start);
        g.addNode(new DialogueNode("end", "Goodbye"));
        return g;
    }

    @Test
    void cleanGraphValidates() {
        assertThat(validator.validate(linear()).isValid()).isTrue();
    }

    @Test
    void missingEntryAndEmptyGraphDiagnosed() {
        DialogueGraph noEntry = linear();
        noEntry.setEntryNodeId("nope");
        assertThat(validator.validate(noEntry).getErrors())
                .extracting(DiagnosticError::code).contains("DIALOGUE_MISSING_ENTRY");

        DialogueGraph empty = new DialogueGraph(NamespacedId.of("storynpcs", "e"), "E", "x");
        assertThat(validator.validate(empty).getErrors())
                .extracting(DiagnosticError::code).contains("DIALOGUE_EMPTY_GRAPH");
    }

    @Test
    void unreachableNodeAndDanglingEdgeDiagnosed() {
        DialogueGraph g = linear();
        g.addNode(new DialogueNode("orphan", "nobody reaches me"));
        DialogueNode start = g.getNode("start").get();
        start.getOptions().add(new DialogueEdge("Void", "missing-target"));
        var errors = validator.validate(g).getErrors();
        assertThat(errors).extracting(DiagnosticError::code)
                .contains("DIALOGUE_UNREACHABLE_NODE", "DIALOGUE_DANGLING_EDGE");
    }

    @Test
    void cyclesDiagnosedAsWarningsNotErrors() {
        DialogueGraph g = linear();
        g.getNode("end").get().getOptions().add(new DialogueEdge("Start over", "start"));
        var result = validator.validate(g);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getDiagnostics()).extracting(DiagnosticError::code)
                .contains("DIALOGUE_CYCLE");
    }

    @Test
    void wellFormedEdgePayloadsValidate() {
        DialogueGraph g = linear();
        DialogueEdge rich = new DialogueEdge("Quest me", "end");
        rich.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.QUEST_STATUS, "storynpcs:intro", "==", "COMPLETED"));
        rich.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_POINTS, "storynpcs:f", ">=", "100"));
        rich.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.HAS_ITEM, "minecraft:diamond", "==", "3"));
        rich.getActions().add(new DialogueAction(
                DialogueAction.Type.START_QUEST, "storynpcs:intro", ""));
        rich.getActions().add(new DialogueAction(
                DialogueAction.Type.GIVE_ITEM, "minecraft:apple", "2"));
        rich.getActions().add(new DialogueAction(
                DialogueAction.Type.EXECUTE_COMMAND, "say hello %player%", ""));
        g.getNode("start").get().getOptions().add(rich);
        assertThat(validator.validate(g).isValid()).isTrue();
    }

    @Test
    void malformedEdgeConditionsDiagnosed() {
        DialogueGraph g = linear();
        DialogueEdge edge = new DialogueEdge("Check", "end");
        edge.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.QUEST_STATUS, "storynpcs:q", "==", "BOGUS"));
        edge.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_POINTS, "storynpcs:f", "~=", "10"));
        edge.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_POINTS, "storynpcs:f", ">=", "abc"));
        edge.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.HAS_ITEM, "not-an-id", "==", "1"));
        edge.getConditions().add(new DialogueCondition(
                DialogueCondition.Type.FACTION_STANDING, "storynpcs:f", "==", "PAL"));
        edge.getConditions().add(new DialogueCondition(
                null, "storynpcs:f", "==", "x"));
        g.getNode("start").get().getOptions().add(edge);
        var errors = validator.validate(g).getErrors();
        assertThat(errors).extracting(DiagnosticError::code)
                .contains("DIALOGUE_BAD_EDGE_CONDITION");
        // 6 malformed conditions: bogus status, bad operator, non-numeric,
        // bad item id, bad standing, missing type.
        assertThat(errors).filteredOn(e -> e.code().equals("DIALOGUE_BAD_EDGE_CONDITION"))
                .hasSize(6);
    }

    @Test
    void malformedEdgeActionsDiagnosed() {
        DialogueGraph g = linear();
        DialogueEdge edge = new DialogueEdge("Do", "end");
        edge.getActions().add(new DialogueAction(
                DialogueAction.Type.START_QUEST, "not-an-id", ""));
        edge.getActions().add(new DialogueAction(
                DialogueAction.Type.ADJUST_FACTION, "storynpcs:f", "lots"));
        edge.getActions().add(new DialogueAction(
                DialogueAction.Type.GIVE_ITEM, "minecraft:stone", "-2"));
        edge.getActions().add(new DialogueAction(
                DialogueAction.Type.EXECUTE_COMMAND, "", ""));
        edge.getActions().add(new DialogueAction(null, "x", ""));
        g.getNode("start").get().getOptions().add(edge);
        var errors = validator.validate(g).getErrors();
        assertThat(errors).filteredOn(e -> e.code().equals("DIALOGUE_BAD_EDGE_ACTION"))
                .hasSize(5);
    }

    @Test
    void availabilityConditionDeepValidationPreservesCode() {
        DialogueGraph g = linear();
        g.getAvailability().add(new DialogueCondition(
                DialogueCondition.Type.QUEST_STATUS, "storynpcs:q", "==", "NOPE"));
        assertThat(validator.validate(g).getErrors())
                .extracting(DiagnosticError::code)
                .contains("DIALOGUE_BAD_AVAILABILITY_CONDITION");
    }
}
