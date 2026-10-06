package com.storynpcs.migration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P11-1: asset/script extraction for import reports. Every document is walked
 * for known resource-bearing keys so the report surfaces which external
 * resources an imported definition references — scripts, textures, player
 * skins — instead of silently absorbing them. Entries are {@code kind:value}
 * strings in deterministic document order.
 */
final class ReferencedResources {

    /** key name → resource kind label in the report. */
    private static final Map<String, String> RESOURCE_KEYS = Map.of(
            "scripts", "script",
            "skinTexture", "texture",
            "skinUrl", "skin-url",
            "skinPlayer", "player-skin",
            "textureRef", "texture",
            "modelPreset", "model-preset");

    private ReferencedResources() {}

    /** Extract all referenced-resource rows from a parsed document. */
    static List<String> extract(JsonNode root) {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return List.copyOf(out);
    }

    private static void walk(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String kind = RESOURCE_KEYS.get(e.getKey());
                if (kind != null) {
                    collectValues(kind, e.getValue(), out);
                }
                walk(e.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, out));
        }
    }

    /** A resource field may hold a scalar or a list of scalars/id objects. */
    private static void collectValues(String kind, JsonNode value, List<String> out) {
        if (value.isTextual()) {
            String v = value.asText().strip();
            if (!v.isEmpty()) {
                out.add(kind + ":" + v);
            }
        } else if (value.isArray()) {
            value.forEach(v -> collectValues(kind, v, out));
        } else if (value.isObject()) {
            JsonNode id = value.get("id");
            if (id != null && id.isTextual() && !id.asText().isBlank()) {
                out.add(kind + ":" + id.asText().strip());
            }
        }
    }
}
