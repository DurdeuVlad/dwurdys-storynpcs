package com.storynpcs.service;

/** Machine-readable authorization outcome for a canonical operation. */
public record AuthorizationDecision(boolean allowed, String code, String message) {
    public AuthorizationDecision {
        code = code == null || code.isBlank() ? "UNKNOWN" : code;
        message = message == null ? "" : message;
    }

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "ALLOWED", "");
    }

    public static AuthorizationDecision deny(String code, String message) {
        return new AuthorizationDecision(false, code, message);
    }
}
