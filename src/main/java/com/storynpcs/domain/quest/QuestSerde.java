package com.storynpcs.domain.quest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * JSON serialization for transporting {@link Quest} definitions between server
 * and client editor screens. Mirrors {@link com.storynpcs.domain.npc.NpcDefinitionSerde}.
 */
public final class QuestSerde {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private QuestSerde() {}

    public static String toJson(Quest quest) {
        try {
            return MAPPER.writeValueAsString(quest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Quest to JSON", e);
        }
    }

    public static String toJsonList(List<Quest> quests) {
        try {
            return MAPPER.writeValueAsString(quests);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize quest list to JSON", e);
        }
    }

    public static Optional<Quest> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readValue(json, Quest.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static List<Quest> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Quest.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
