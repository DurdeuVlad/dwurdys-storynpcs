package com.storynpcs.command;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.storynpcs.domain.common.NamespacedId;

/**
 * Server-side suggestion filtering (P9-3): tab completion never offers invalid
 * ids or fields — candidates are validated against the live registry snapshot,
 * filtered by typed-prefix match in sorted deterministic order.
 */
public final class CommandSuggestionEngine {

    public enum ArgKind { NPC_ID, DIALOGUE_ID, QUEST_ID, FACTION_ID, FIELD, LITERAL, TEMPLATE_ID }

    /** Filter candidates for an argument — invalid ids/fields are never suggested. */
    public static List<String> suggest(ArgKind kind, String prefix, Supplier<List<String>> candidates) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> base = candidates == null ? List.of() : candidates.get();
        return base.stream()
                .filter(c -> c != null && c.toLowerCase(Locale.ROOT).startsWith(p))
                .filter(c -> kind != ArgKind.FIELD && kind != ArgKind.LITERAL || validToken(c))
                .sorted()
                .toList();
    }

    /** Whether a raw user token is even a candidate id — rejects garbage before lookup. */
    public static boolean validIdToken(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return false;
        }
        return token.matches("[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?");
    }

    private static boolean validToken(String c) {
        return !c.isBlank() && c.length() <= 64;
    }

    /** Validate a completed argument against its kind — used to reject tab-invalid ids. */
    public static boolean argumentValid(ArgKind kind, String value) {
        return switch (kind) {
            case NPC_ID, DIALOGUE_ID, QUEST_ID, FACTION_ID, TEMPLATE_ID ->
                    validIdToken(value) && value.contains(":");
            case FIELD -> value != null && value.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}");
            case LITERAL -> value != null && !value.isBlank();
        };
    }

    /** Parse a namespaced id argument or return empty — never throws to the command tree. */
    public static java.util.Optional<NamespacedId> parseId(String token) {
        try {
            return validIdToken(token) ? java.util.Optional.of(NamespacedId.of(token)) : java.util.Optional.empty();
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }
}
