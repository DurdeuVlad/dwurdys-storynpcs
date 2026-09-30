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
}
