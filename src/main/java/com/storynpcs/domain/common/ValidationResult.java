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
        if (isValid() && !hasWarnings()) {
            return "Validation PASSED (0 diagnostics).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Validation result: %d error(s), %d warning(s):\n",
                getErrors().size(), diagnostics.size() - getErrors().size()));
        for (DiagnosticError d : diagnostics) {
            sb.append("  - ").append(d.toString()).append("\n");
        }
        return sb.toString();
    }
}
