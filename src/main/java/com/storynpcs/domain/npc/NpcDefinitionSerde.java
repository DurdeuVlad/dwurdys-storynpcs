package com.storynpcs.domain.npc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * JSON serialization for transporting {@link NpcDefinition} between server and client editor screens.
 */
public final class NpcDefinitionSerde {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private NpcDefinitionSerde() {}

    public static String toJson(NpcDefinition definition) {
        try {
            return MAPPER.writeValueAsString(definition);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize NpcDefinition to JSON", e);
        }
    }

    public static Optional<NpcDefinition> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode source = MAPPER.readTree(json);
            NpcDefinition definition = MAPPER.treeToValue(source, NpcDefinition.class);
            restoreSkinSourceAfterDeserialization(definition, source);
            return Optional.ofNullable(definition);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Restores an explicitly authored skin source after bean setters have processed sibling fields. */
    public static void restoreSkinSourceAfterDeserialization(NpcDefinition definition, JsonNode source)
            throws java.io.IOException {
        if (definition == null || definition.getDisplay() == null || source == null) return;
        JsonNode display = source.get("display");
        if (display == null || !display.isObject()) return;
        if (display.has("skinSource")) {
            JsonNode authoredSource = display.get("skinSource");
            NpcDisplay.SkinSource skinSource = authoredSource == null || authoredSource.isNull()
                    ? null : MAPPER.treeToValue(authoredSource, NpcDisplay.SkinSource.class);
            definition.getDisplay().setSkinSource(skinSource);
            return;
        }

        // Legacy files omitted skinSource; preserve their setter-inferred selection deterministically.
        JsonNode skinUrl = display.get("skinUrl");
        JsonNode skinPlayer = display.get("skinPlayer");
        if (skinUrl != null && !skinUrl.asText().isBlank()) {
            definition.getDisplay().setSkinSource(NpcDisplay.SkinSource.URL);
        } else if (skinPlayer != null && !skinPlayer.asText().isBlank()) {
            definition.getDisplay().setSkinSource(NpcDisplay.SkinSource.PLAYER);
        }
    }
}
