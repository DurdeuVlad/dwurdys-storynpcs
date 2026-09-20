package com.storynpcs.domain.faction;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * JSON serialization for transporting {@link Faction} definitions between server
 * and client editor screens. Mirrors {@link com.storynpcs.domain.quest.QuestSerde}.
 */
public final class FactionSerde {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FactionSerde() {}

    public static String toJson(Faction faction) {
        try {
            return MAPPER.writeValueAsString(faction);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Faction to JSON", e);
        }
    }

    public static String toJsonList(List<Faction> factions) {
        try {
            return MAPPER.writeValueAsString(factions);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize faction list to JSON", e);
        }
    }

    public static Optional<Faction> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readValue(json, Faction.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static List<Faction> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Faction.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
