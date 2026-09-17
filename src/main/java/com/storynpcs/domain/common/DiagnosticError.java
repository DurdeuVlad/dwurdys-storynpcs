package com.storynpcs.domain.common;

public record DiagnosticError(
        String file,
        int line,
        int column,
        Severity severity,
        String code,
        String message
) {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public static DiagnosticError error(String file, int line, int column, String code, String message) {
        return new DiagnosticError(file, line, column, Severity.ERROR, code, message);
    }

    public static DiagnosticError error(String code, String message) {
        return new DiagnosticError("<unknown>", -1, -1, Severity.ERROR, code, message);
    }

    public static DiagnosticError warning(String file, int line, int column, String code, String message) {
        return new DiagnosticError(file, line, column, Severity.WARNING, code, message);
    }

    public static DiagnosticError warning(String code, String message) {
        return new DiagnosticError("<unknown>", -1, -1, Severity.WARNING, code, message);
    }

    @Override
    public String toString() {
        if (line > 0 && column > 0) {
            return String.format("[%s] %s:%d:%d - %s (%s)", severity, file, line, column, message, code);
        } else if (file != null && !file.equals("<unknown>")) {
            return String.format("[%s] %s - %s (%s)", severity, file, message, code);
        }
        return String.format("[%s] %s (%s)", severity, message, code);
    }
}
