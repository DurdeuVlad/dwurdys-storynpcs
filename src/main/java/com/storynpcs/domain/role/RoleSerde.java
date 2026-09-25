package com.storynpcs.domain.role;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.domain.role.banker.BankVault;
import com.storynpcs.domain.role.banker.BankerRole;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.persistence.BankOperationIntent;
import com.storynpcs.persistence.TradeOperationIntent;

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

    /** Makes a detached role projection for client views; runtime overlays must not touch definitions. */
    public static TraderRole copyTrader(TraderRole trader) {
        if (trader == null) throw new IllegalArgumentException("trader is required");
        return traderFromJson(toJson(trader))
                .orElseThrow(() -> new IllegalStateException("Failed to copy trader role for a client view"));
    }

    public static Optional<BankerRole> bankerFromJson(String json) {
        return read(json, BankerRole.class);
    }

    public static Optional<BankVault> vaultFromJson(String json) {
        return read(json, BankVault.class);
    }

    public static Optional<BankOperationIntent> bankOperationIntentFromJson(String json) {
        return read(json, BankOperationIntent.class);
    }

    public static Optional<TradeOperationIntent> tradeOperationIntentFromJson(String json) {
        return read(json, TradeOperationIntent.class);
    }

    /**
     * Parses a {@code {"id": points}} score map without unchecked casts. Malformed
     * JSON and non-object roots yield an empty map; entries whose value is not an
     * int-range integer (strings, floats, nulls, oversized numbers) are dropped so
     * one corrupt entry cannot poison the rest of the map.
     */
    public static Map<String, Integer> scoresFromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            var tree = MAPPER.readTree(json);
            if (tree == null || !tree.isObject()) return Map.of();
            var scores = new java.util.LinkedHashMap<String, Integer>();
            var fields = tree.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var node = entry.getValue();
                if (node != null && node.isInt()) {
                    scores.put(entry.getKey(), node.intValue());
                }
            }
            return java.util.Collections.unmodifiableMap(scores);
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
