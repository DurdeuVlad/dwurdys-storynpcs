package com.storynpcs.domain.template;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * JSON serialization for transporting {@link NpcTemplate} records between server and
 * client editor screens (trusted, in-process transport only). Mirrors
 * {@link com.storynpcs.domain.faction.FactionSerde}. For untrusted import data, use
 * {@link NpcTemplateImportValidator} first — this class does not reject unknown fields.
 */
public final class NpcTemplateSerde {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private NpcTemplateSerde() {}

    public static String toJson(NpcTemplate template) {
        try {
            return MAPPER.writeValueAsString(template);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize NpcTemplate to JSON", e);
        }
    }

    public static String toJsonList(List<NpcTemplate> templates) {
        try {
            return MAPPER.writeValueAsString(templates);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize NpcTemplate list to JSON", e);
        }
    }

    public static Optional<NpcTemplate> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readValue(json, NpcTemplate.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static List<NpcTemplate> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, NpcTemplate.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
