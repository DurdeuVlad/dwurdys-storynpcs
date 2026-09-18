package com.storynpcs.domain;

import com.storynpcs.domain.common.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationResultTest {

    @Test
    @DisplayName("formatReport() lists every diagnostic")
    void testUnboundedReport() {
        ValidationResult result = ValidationResult.valid();
        for (int i = 1; i <= 12; i++) {
            result.addError("file" + i + ".yaml", i, 1, "CODE_" + i, "problem " + i);
        }

        String report = result.formatReport();
        assertTrue(report.contains("12 error(s)"));
        assertTrue(report.contains("CODE_12"));
        assertFalse(report.contains("see server log"));
    }

    @Test
    @DisplayName("formatReport(max) bounds lines and points at the server log for the overflow")
    void testBoundedReport() {
        ValidationResult result = ValidationResult.valid();
        for (int i = 1; i <= 12; i++) {
            result.addError("file" + i + ".yaml", i, 1, "CODE_" + i, "problem " + i);
        }

        String report = result.formatReport(5);
        assertTrue(report.contains("12 error(s)"), "header still shows the true total");
        assertTrue(report.contains("CODE_5"));
        assertFalse(report.contains("CODE_6"), "diagnostics beyond the cap are hidden");
        assertTrue(report.contains("7 more"));
        assertTrue(report.contains("see server log"));
    }

    @Test
    @DisplayName("Clean result formats the same bounded or unbounded")
    void testCleanReport() {
        ValidationResult result = ValidationResult.valid();
        assertEquals("Validation PASSED (0 diagnostics).", result.formatReport());
        assertEquals(result.formatReport(), result.formatReport(3));
    }

    @Test
    @DisplayName("Diagnostics carry file:line:column for actionable in-game output")
    void testDiagnosticLocationFormat() {
        ValidationResult result = ValidationResult.valid();
        result.addError("quests/bounty.yaml", 12, 5, "QUEST_OBJ_EMPTY", "objectives list is empty");

        String report = result.formatReport(10);
        assertTrue(report.contains("quests/bounty.yaml:12:5"));
        assertTrue(report.contains("QUEST_OBJ_EMPTY"));
    }
}
