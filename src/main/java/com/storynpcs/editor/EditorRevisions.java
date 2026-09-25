package com.storynpcs.editor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes the per-definition revision map carried by editor-open payloads.
 * The map binds each optimistic-concurrency token to its definition id so a
 * revision captured for one row can never be reused against another row.
 */
public final class EditorRevisions {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Long>> MAP_TYPE = new TypeReference<>() {};

    private EditorRevisions() {}

    /** Parses the wire form; malformed input yields an empty map (fail closed to revision 0). */
    public static Map<String, Long> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Long> parsed = MAPPER.readValue(json, MAP_TYPE);
            Map<String, Long> clean = new LinkedHashMap<>();
            parsed.forEach((id, revision) -> {
                if (id != null && !id.isBlank() && revision != null && revision >= 0) {
                    clean.put(id, revision);
                }
            });
            return Map.copyOf(clean);
        } catch (Exception invalid) {
            return Map.of();
        }
    }

    public static String toJson(Map<String, Long> revisions) {
        if (revisions == null || revisions.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(revisions);
        } catch (Exception invalid) {
            return "{}";
        }
    }
}
