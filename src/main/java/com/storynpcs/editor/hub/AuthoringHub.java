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

    // ── panel routes (P10-1) ─────────────────────────────────────────────────

    /**
     * How a panel opens: a slash-command template, a wand gesture, or a
     * navigation hop from a parent screen. Commands are sent verbatim — the
     * server owns the open decision and replies with the matching payload.
     */
    public record Route(Kind kind, String openPath, String description) {
        public enum Kind { COMMAND, WAND, NAVIGATE, YAML }

        public static Route command(String path, String description) {
            return new Route(Kind.COMMAND, path, description);
        }
        public static Route wand(String description) {
            return new Route(Kind.WAND, "storynpcs:npc_wand", description);
        }
        public static Route navigate(String parent, String description) {
            return new Route(Kind.NAVIGATE, parent, description);
        }
        public static Route yaml(String family, String description) {
            return new Route(Kind.YAML, family, description);
        }
    }

    /**
     * The open path(s) for a panel. Panels backed by a dedicated editor screen
     * route to the command that asks the server to push it; data-driven
     * families route to their YAML surface and management commands. Every
     * panel has at least one route — an empty list here is a coverage bug.
     */
    public static List<Route> routesFor(Panel panel) {
        return switch (panel) {
            case NPC_IDENTITY -> List.of(
                    Route.wand("Shift+right-click an NPC with the wand"),
                    Route.command("storynpcs npc create <id>", "create + edit identity fields"));
            case DISPLAY -> List.of(
                    Route.navigate("NpcEditorScreen", "Display button in the NPC editor"),
                    Route.wand("Shift+right-click an NPC, then Display"));
            case AI -> List.of(
                    Route.navigate("NpcEditorScreen", "movement/stance controls in the NPC editor"),
                    Route.navigate("NpcRulesScreen", "Rules button — AI behaviour rules"));
            case COMBAT -> List.of(
                    Route.navigate("NpcEditorScreen", "health/damage/range + stats fields"),
                    Route.command("storynpcs npc set health|damage|range <id> <value>",
                            "scalar stat commands"),
                    Route.yaml("npcs/", "resistances/immunities/creatureType fields"));
            case EQUIPMENT -> List.of(
                    Route.navigate("NpcEditorScreen", "inventory/equipment section"),
                    Route.yaml("npcs/", "held items, armor, and drop-table fields"));
            case INVENTORY -> List.of(
                    Route.navigate("NpcEditorScreen", "inventory section"),
                    Route.yaml("npcs/", "drops/equipment definition fields"));
            case DIALOGUE -> List.of(
                    Route.command("storynpcs dialogue create <id>", "scaffold + open graph editor"),
                    Route.command("storynpcs dialogue edit <id>", "open the graph editor"));
            case QUEST -> List.of(
                    Route.command("storynpcs quest gui [id]", "quest editor screen"),
                    Route.command("storynpcs quest create <id>", "create then open"));
            case FACTION -> List.of(
                    Route.command("storynpcs faction gui [id]", "faction editor screen"),
                    Route.command("storynpcs faction create <id>", "create then open"));
            case ROLE -> List.of(
                    Route.navigate("NpcEditorScreen", "title/role field in the NPC editor"),
                    Route.yaml("roles/", "role definitions (healer/bard/follower/...)"));
            case JOB -> List.of(
                    Route.yaml("jobs/", "job definitions (farmer/puppet/guard schedules)"),
                    Route.yaml("npcs/", "job field attaches a job to an NPC"));
            case TRADE -> List.of(
                    Route.navigate("TraderBankerAdminScreen", "Trade admin button in the NPC editor"),
                    Route.command("storynpcs trade", "trade listing commands"));
            case BANK -> List.of(
                    Route.navigate("TraderBankerAdminScreen", "Bank admin button in the NPC editor"),
                    Route.command("storynpcs bank", "bank/vault admin commands"));
            case TRANSPORT -> List.of(
                    Route.yaml("transports/", "transport route/category definitions"),
                    Route.command("storynpcs transport", "transport management commands"));
            case TEMPLATE -> List.of(
                    Route.yaml("templates|spawners|scenes|naturalspawns|transforms/",
                            "M8 creator-world YAML families"),
                    Route.command("storynpcs template|spawner|scene|link|naturalspawn|transform",
                            "canonical management commands"));
            case TOOL -> List.of(
                    Route.command("storynpcs worldtool", "world-tool sessions (P8-2/P8-3)"),
                    Route.command("storynpcs remote|playerdata", "remote/admin ops (P9-4)"),
                    Route.command("storynpcs npc set marks <id>", "playerdata marks management"));
            case SCRIPT -> List.of(
                    Route.yaml("scripts/", "bounded Rhino script definitions (P9-2)"),
                    Route.command("storynpcs script", "list/info/reload/trigger/unquarantine"));
        };
    }

    /** Short preview of what a panel covers — rendered in the hub detail pane. */
    public static String preview(Panel panel) {
        return switch (panel) {
            case NPC_IDENTITY -> "id, name, title, role, faction assignment";
            case DISPLAY -> "skin, variants, parts, model presets, scale";
            case AI -> "movement type, stance, behaviour rules, pathing";
            case COMBAT -> "health/damage/speed/range, resistances, immunities, projectiles";
            case EQUIPMENT -> "held items and armor";
            case INVENTORY -> "drop table and carried inventory";
            case DIALOGUE -> "graph nodes, options, conditions, actions, availability";
            case QUEST -> "objectives, dependencies, rewards, repeat modes";
            case FACTION -> "faction defs, standings, points";
            case ROLE -> "role definitions: healer, bard, follower, ...";
            case JOB -> "job defs and per-NPC job assignment";
            case TRADE -> "trader listing and trade admin";
            case BANK -> "banker vault admin";
            case TRANSPORT -> "transport routes and categories";
            case TEMPLATE -> "templates, spawners, scenes, links, natural spawns, transforms";
            case TOOL -> "world-tool sessions, remote admin, playerdata, marks";
            case SCRIPT -> "bounded Rhino scripts, hooks, capabilities, budgets";
        };
    }

    /**
     * The canonical single-NPC authoring workflow: the ordered panels a creator
     * walks to build one NPC. The spine — identity, display, AI, combat
     * scalars, dialogue, quest, faction — is fully GUI/command-driven with no
     * raw YAML. Equipment, inventory, job, script, and template enrichments
     * route to their YAML families plus canonical commands; the hub names the
     * family path rather than fabricating an editor that does not exist.
     */
    public static List<String> npcWorkflow() {
        return List.of(
                "NPC_IDENTITY — create the NPC and set id/name/role",
                "DISPLAY — skin, variants, parts, model preset, scale",
                "AI — movement type, stance, rules, pathing",
                "COMBAT — stats, resistances, immunities, projectiles",
                "EQUIPMENT — held items, armor, drop table",
                "DIALOGUE — graph nodes/options/conditions/actions",
                "QUEST — objectives, rewards, repeat modes",
                "FACTION — standing effects and faction-scoped behaviour",
                "SCRIPT — optional scripted hooks within sandbox budgets",
                "TEMPLATE — spawn/natural-spawn/scene integration");
    }

    /** Keyboard navigation over the filtered panel list (wraps at both ends). */
    public Panel cyclePanel(List<Panel> filtered, boolean forward) {
        if (filtered.isEmpty()) return state.panel();
        int idx = filtered.indexOf(state.panel());
        int next = idx < 0 ? (forward ? 0 : filtered.size() - 1)
                : (idx + (forward ? 1 : -1) + filtered.size()) % filtered.size();
        return filtered.get(next);
    }
}
