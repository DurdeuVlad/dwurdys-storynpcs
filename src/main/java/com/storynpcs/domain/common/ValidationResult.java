package com.storynpcs.domain.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    private final List<DiagnosticError> diagnostics = new ArrayList<>();

    public static ValidationResult valid() {
        return new ValidationResult();
    }

    public void addError(String file, int line, int column, String code, String message) {
        diagnostics.add(DiagnosticError.error(file, line, column, code, message));
    }

    public void addError(String code, String message) {
        diagnostics.add(DiagnosticError.error(code, message));
    }

    public void addWarning(String file, int line, int column, String code, String message) {
        diagnostics.add(DiagnosticError.warning(file, line, column, code, message));
    }

    public void addWarning(String code, String message) {
        diagnostics.add(DiagnosticError.warning(code, message));
    }

    public void merge(ValidationResult other) {
        if (other != null) {
            this.diagnostics.addAll(other.diagnostics);
        }
    }

    public boolean isValid() {
        return diagnostics.stream().noneMatch(d -> d.severity() == DiagnosticError.Severity.ERROR);
    }

    public boolean hasErrors() {
        return !isValid();
    }

    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticError.Severity.WARNING);
    }

    public List<DiagnosticError> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public List<DiagnosticError> getErrors() {
        return diagnostics.stream()
                .filter(d -> d.severity() == DiagnosticError.Severity.ERROR)
                .toList();
    }

    public String formatReport() {
        return formatReport(Integer.MAX_VALUE);
    }

    /**
     * Bounded report for chat surfaces: shows at most {@code maxDiagnostics} lines,
     * then an overflow count pointing at the server log for the remainder.
     */
    public String formatReport(int maxDiagnostics) {
        if (isValid() && !hasWarnings()) {
            return "Validation PASSED (0 diagnostics).";
        }
        int max = Math.max(0, maxDiagnostics);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Validation result: %d error(s), %d warning(s):\n",
                getErrors().size(), diagnostics.size() - getErrors().size()));
        diagnostics.stream().limit(max).forEach(d -> sb.append("  - ").append(d).append("\n")
                .append("      hint: ").append(DiagnosticHints.hintFor(d)).append("\n"));
        int hidden = diagnostics.size() - Math.min(diagnostics.size(), max);
        if (hidden > 0) {
            sb.append("  …and ").append(hidden).append(" more — see server log for the full report\n");
        }
        return sb.toString();
    }
}
