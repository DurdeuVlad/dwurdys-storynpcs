package com.storynpcs.domain.npc;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
            return Optional.ofNullable(MAPPER.readValue(json, NpcDefinition.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
