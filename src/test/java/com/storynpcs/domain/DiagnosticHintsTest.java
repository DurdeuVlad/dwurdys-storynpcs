package com.storynpcs.domain;

import com.storynpcs.domain.common.DiagnosticError;
import com.storynpcs.domain.common.DiagnosticHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticHintsTest {

    @Test
    @DisplayName("Every emitted code has a specific hint")
    void testKnownCodes() {
        assertTrue(DiagnosticHints.hintFor(DiagnosticError.error("NPC_ID_MISSING", "x"))
                .contains("'id'"));
        assertTrue(DiagnosticHints.hintFor(DiagnosticError.error("GRAPH_DANGLING_EDGE", "x"))
                .contains("does not exist"));
        assertTrue(DiagnosticHints.hintFor(DiagnosticError.error("FACTION_THRESHOLDS_INCONSISTENT", "x"))
                .contains("hostile"));
        assertTrue(DiagnosticHints.hintFor(DiagnosticError.error("SCHEMA_EMPTY_FILE", "x"))
                .contains("empty"));
        assertTrue(DiagnosticHints.hintFor(DiagnosticError.error("YAML_PARSE_ERROR", "x"))
                .contains("YAML"));
        assertTrue(DiagnosticHints.hintFor(DiagnosticError.error("YAML_MAPPING_ERROR", "x"))
                .contains("field"));
    }

    @Test
    @DisplayName("Unknown codes fall back to a generic hint naming the line")
    void testFallbackWithLine() {
        String hint = DiagnosticHints.hintFor(DiagnosticError.error("f.yaml", 9, 1, "NOPE", "x"));
        assertTrue(hint.contains("line 9"));
        assertTrue(hint.contains("server log"));
    }

    @Test
    @DisplayName("Unknown codes without a line still get a plain-language fallback")
    void testFallbackWithoutLine() {
        String hint = DiagnosticHints.hintFor(DiagnosticError.error("NOPE", "x"));
        assertFalse(hint.isBlank());
        assertTrue(hint.contains("server log"));
    }

    @Test
    @DisplayName("Null code and null diagnostic never throw")
    void testNullSafety() {
        assertDoesNotThrow(() -> DiagnosticHints.hintFor(null));
        assertFalse(DiagnosticHints.hintFor(
                new DiagnosticError("f", -1, -1, DiagnosticError.Severity.ERROR, null, "x")).isBlank());
    }
}
