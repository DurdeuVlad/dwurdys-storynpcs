package com.storynpcs.editor.hub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Unified authoring hub model (P10-1). Every parity domain has a panel; panel
 * search and pagination are deterministic; screen state is revisioned; errors
 * carry the exact schema path plus a repair hint.
 */
public final class AuthoringHub {

    /** The 17 authoring panels covering the parity domains. */
    public enum Panel {
        NPC_IDENTITY, DISPLAY, AI, COMBAT, EQUIPMENT, INVENTORY,
        DIALOGUE, QUEST, FACTION, ROLE, JOB,
        TRADE, BANK, TRANSPORT, TEMPLATE, TOOL, SCRIPT
    }

    /** An error surfaced to a creator: exact schema path + repair action. */
    public record FieldError(String schemaPath, String message, String repairHint) {}

    /** Revisioned screen state — stale saves are rejected by the caller. */
    public record ScreenState(Panel panel, String selectedId, long revision) {}

    private ScreenState state = new ScreenState(Panel.NPC_IDENTITY, null, 0);
    private final List<FieldError> errors = new ArrayList<>();

    public ScreenState state() { return state; }

    public void open(Panel panel, String selectedId) {
        state = new ScreenState(panel, selectedId, state.revision() + 1);
    }

    public void touch() {
        state = new ScreenState(state.panel(), state.selectedId(), state.revision() + 1);
    }

    public void report(FieldError error) {
        errors.add(error);
    }

    public List<FieldError> errors() { return List.copyOf(errors); }
    public void clearErrors() { errors.clear(); }

    /** Case-insensitive deterministic panel search. */
    public static List<Panel> searchPanels(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(Panel.values())
                .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    /**
     * Deterministic pagination — used at the certified minimum viewport
     * (427x240 logical) where only {@code pageSize} rows fit without clipping.
     */
    public static <T> List<T> page(List<T> items, int page, int pageSize) {
        if (pageSize <= 0 || page < 0) {
            return List.of();
        }
        int from = Math.min(items.size(), page * pageSize);
        int to = Math.min(items.size(), from + pageSize);
        return items.subList(from, to);
    }

    public static int pageCount(int itemCount, int pageSize) {
        return pageSize <= 0 ? 0 : (itemCount + pageSize - 1) / pageSize;
    }

    /** Map a diagnostic onto a creator-facing field error. */
    public static FieldError fieldError(String schemaPath, String message) {
        String hint = switch (schemaPath == null ? "" : schemaPath) {
            case "id" -> "use a namespaced id like storynpcs:my_npc";
            case "stats.health" -> "health must be in [1,1024]";
            case "display.modelSize" -> "modelSize must be in [0.1,10.0]";
            case "inventory.drops" -> "drop index must be 0-20 with chance 0-100";
            default -> "check the YAML schema docs for this field";
        };
        return new FieldError(schemaPath == null ? "?" : schemaPath,
                message == null ? "" : message, hint);
    }

    /**
     * The parity domains the hub must cover — every entry requires its own
     * panel. Kept as an explicit manifest so a renamed, swapped, or dropped
     * panel fails the coverage check instead of merely shifting a count.
     */
    private static final java.util.Set<String> REQUIRED_DOMAINS = java.util.Set.of(
            "NPC_IDENTITY", "DISPLAY", "AI", "COMBAT", "EQUIPMENT", "INVENTORY",
            "DIALOGUE", "QUEST", "FACTION", "ROLE", "JOB",
            "TRADE", "BANK", "TRANSPORT", "TEMPLATE", "TOOL", "SCRIPT");

    /**
     * Whether a distinct panel exists for every required parity domain.
     * Set equality on names — a count check alone would pass if one domain
     * were dropped while another was duplicated or renamed.
     */
    public static boolean coversAllDomains() {
        return java.util.Arrays.stream(Panel.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                .equals(REQUIRED_DOMAINS);
    }
}
