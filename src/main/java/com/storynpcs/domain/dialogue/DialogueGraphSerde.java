package com.storynpcs.domain.dialogue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * JSON serialization for transporting {@link DialogueGraph} between server and client.
 * Used by the dialogue editor open/save packets — the domain classes are already
 * Jackson-annotated for YAML loading, so a plain JSON mapper round-trips them.
 */
public final class DialogueGraphSerde {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DialogueGraphSerde() {}

    public static String toJson(DialogueGraph graph) {
        try {
            return MAPPER.writeValueAsString(graph);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize DialogueGraph to JSON", e);
        }
    }

    public static Optional<DialogueGraph> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readValue(json, DialogueGraph.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
