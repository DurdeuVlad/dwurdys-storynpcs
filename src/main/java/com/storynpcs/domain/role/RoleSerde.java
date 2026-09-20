package com.storynpcs.domain.role;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TraderRole;

import java.util.Map;
import java.util.Optional;

/**
 * JSON serialization for transporting trader/banker role state between server and client
 * trade/bank screens (see {@link com.storynpcs.domain.npc.NpcDefinitionSerde}).
 */
public final class RoleSerde {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RoleSerde() {}

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize role payload", e);
        }
    }

    public static Optional<TraderRole> traderFromJson(String json) {
        return read(json, TraderRole.class);
    }

    public static Optional<BankerRole> bankerFromJson(String json) {
        return read(json, BankerRole.class);
    }

    public static Optional<BankVault> vaultFromJson(String json) {
        return read(json, BankVault.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Integer> scoresFromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static <T> Optional<T> read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readValue(json, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
