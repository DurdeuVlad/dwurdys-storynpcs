package com.storynpcs.service;

/**
 * Result of a {@link PlayerProgressionActionRequest}-guarded operation (issue #54).
 * {@code applied} is only ever true when {@code decision.allowed()} is true — an
 * unauthorized request always yields {@code applied == false} with zero side
 * effects, and the caller can distinguish "denied" from "not found" by inspecting
 * {@code decision.code()} (e.g. {@code "PERMISSION_DENIED"} vs. an allowed decision
 * where the underlying resource simply didn't exist).
 */
public record AuthorizedActionResult(boolean applied, AuthorizationDecision decision) {

    public static AuthorizedActionResult denied(AuthorizationDecision decision) {
        return new AuthorizedActionResult(false, decision);
    }

    public static AuthorizedActionResult of(boolean applied) {
        return new AuthorizedActionResult(applied, AuthorizationDecision.allow());
    }
}
