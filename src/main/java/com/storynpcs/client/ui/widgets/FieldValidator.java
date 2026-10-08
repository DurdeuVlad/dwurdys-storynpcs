package com.storynpcs.client.ui.widgets;

/**
 * Pure validation contract for {@link FormRow} (issue #199): returns null (or
 * throws nothing) when the value is acceptable, otherwise a short error
 * string rendered inline under the field.
 */
@FunctionalInterface
public interface FieldValidator {

    /** @return null when valid, an error message otherwise. */
    String validate(String value);

    /** Composes validators; the first failure wins. */
    static FieldValidator all(FieldValidator... validators) {
        return value -> {
            for (FieldValidator v : validators) {
                String error = v.validate(value);
                if (error != null) {
                    return error;
                }
            }
            return null;
        };
    }

    static FieldValidator required(String label) {
        return value -> value == null || value.isBlank() ? label + " is required" : null;
    }

    static FieldValidator maxLength(int max) {
        return value -> value != null && value.length() > max
                ? "Must be at most " + max + " characters" : null;
    }

    /** Namespaced-id shape ([a-z0-9_.-]+:[a-z0-9_/.-]+) — the repo's id convention. */
    static FieldValidator namespacedId() {
        return value -> value == null || value.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                ? null : "Expected namespace:id, e.g. storynpcs:my_quest";
    }
}
