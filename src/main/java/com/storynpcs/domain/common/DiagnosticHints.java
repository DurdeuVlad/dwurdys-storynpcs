package com.storynpcs.domain.common;

import java.util.Map;

/**
 * Plain-language hints for validation diagnostics (issue #22).
 *
 * Maps each diagnostic code emitted by the loaders/validators to a one-line
 * hint written for non-technical admins — what the problem is and what to do,
 * without assuming YAML or error-code knowledge. The technical
 * {@code file:line:col - message (CODE)} line is unchanged; the hint is shown
 * alongside it by {@link ValidationResult#formatReport(int)}.
 *
 * Any code not listed falls back to a generic plain-language message, so new
 * codes never surface as a blank or crash a report.
 */
public final class DiagnosticHints {

    private DiagnosticHints() {}

    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("SCHEMA_EMPTY_FILE",
                    "The file is empty or not valid YAML — add a definition to it or delete the file."),
            Map.entry("SCHEMA_MISSING_ID",
                    "The definition is missing its 'id' line — add 'id: storynpcs:your_name' near the top."),
            Map.entry("DUPLICATE_DEFINITION_ID",
                    "Two definitions use the same id — rename one so every id is unique."),
            Map.entry("LOAD_ERROR",
                    "The YAML could not be read — check indentation and quotes near the reported line."),
            Map.entry("IO_ERROR",
                    "The file could not be read — check it is not locked or corrupted, then see the server log."),
            Map.entry("DIR_NOT_FOUND",
                    "This definitions folder does not exist yet — that is fine if you have not added this content type."),
            Map.entry("NPC_ID_MISSING",
                    "This NPC has no 'id' — add 'id: storynpcs:your_npc' to its file."),
            Map.entry("GRAPH_ID_MISMATCH",
                    "The dialogue's 'id' does not match its file name — make both identical."),
            Map.entry("GRAPH_EMPTY",
                    "This dialogue has no nodes — add at least one node under 'nodes:'."),
            Map.entry("GRAPH_ENTRY_MISSING",
                    "This dialogue has no starting point — set 'entry' to the first node's id."),
            Map.entry("GRAPH_EDGE_TEXT_MISSING",
                    "A dialogue option is missing its text — give every option a 'text:' line."),
            Map.entry("GRAPH_DANGLING_EDGE",
                    "A dialogue option points to a node that does not exist — check the option's 'next' id for typos."),
            Map.entry("GRAPH_COND_INVALID_ID",
                    "A dialogue condition references an unknown or badly-formed id — check the condition's arguments."),
            Map.entry("GRAPH_COND_QUEST_NOT_FOUND",
                    "A dialogue condition checks a quest id that does not exist — fix the id or create that quest."),
            Map.entry("GRAPH_COND_FACTION_NOT_FOUND",
                    "A dialogue condition checks a faction id that does not exist — fix the id or create that faction."),
            Map.entry("GRAPH_COND_FACTION_POINTS_MISSING",
                    "A faction-standing condition is missing its point value — add the 'points' argument."),
            Map.entry("GRAPH_COND_FACTION_POINTS_INVALID",
                    "A faction-standing condition's point value is not a number — use a whole number like 100."),
            Map.entry("GRAPH_ACTION_INVALID_ID",
                    "A dialogue action references an unknown or badly-formed id — check the action's arguments."),
            Map.entry("GRAPH_ACTION_QUEST_NOT_FOUND",
                    "A dialogue action starts a quest id that does not exist — fix the id or create that quest."),
            Map.entry("GRAPH_ACTION_FACTION_NOT_FOUND",
                    "A dialogue action changes standing with a faction id that does not exist — fix the id or create that faction."),
            Map.entry("GRAPH_ACTION_FACTION_VALUE_MISSING",
                    "A faction-standing action is missing its amount — add the 'value' argument."),
            Map.entry("GRAPH_ACTION_FACTION_VALUE_INVALID",
                    "A faction-standing action's amount is not a number — use a whole number like 50."),
            Map.entry("QUEST_ID_MISSING",
                    "This quest has no 'id' — add 'id: storynpcs:your_quest'."),
            Map.entry("QUEST_ALREADY_EXISTS",
                    "A quest with this id already exists — pick another id or edit the existing quest."),
            Map.entry("QUEST_OBJ_EMPTY",
                    "This quest has no objectives — add at least one under 'objectives:'."),
            Map.entry("QUEST_OBJ_ID_MISSING",
                    "A quest objective is missing its 'id' — give each objective a unique id."),
            Map.entry("QUEST_OBJ_TARGET_MISSING",
                    "A quest objective is missing its 'target' — e.g. 'minecraft:zombie' for a kill objective."),
            Map.entry("QUEST_OBJ_COUNT_INVALID",
                    "An objective's 'requiredCount' must be a positive number — use a whole number like 1 or 5."),
            Map.entry("QUEST_REWARD_TARGET_MISSING",
                    "A quest reward is missing its 'target' — e.g. an item id like 'minecraft:diamond'."),
            Map.entry("QUEST_REWARD_FACTION_NOT_FOUND",
                    "A quest reward changes standing with a faction id that does not exist — fix the id or create that faction."),
            Map.entry("QUEST_REWARD_INVALID_ID",
                    "A quest reward's target is not a valid id — expected something like 'minecraft:diamond'."),
            Map.entry("REF_QUEST_PREREQ_MISSING",
                    "This quest requires another quest id that does not exist — fix the prerequisite or create that quest."),
            Map.entry("CYCLE_QUEST_PREREQUISITE",
                    "Quest prerequisites form a loop (A needs B, B needs A) — remove one link to break the cycle."),
            Map.entry("REF_NPC_DIALOGUE_MISSING",
                    "This NPC points to a dialogue id that does not exist — fix the 'dialogue' field or create that dialogue."),
            Map.entry("REF_NPC_FACTION_MISSING",
                    "This NPC belongs to a faction id that does not exist — fix the 'faction' field or create that faction."),
            Map.entry("FACTION_ID_MISSING",
                    "This faction has no 'id' — add 'id: storynpcs:your_faction'."),
            Map.entry("FACTION_ALREADY_EXISTS",
                    "A faction with this id already exists — pick another id or edit the existing faction."),
            Map.entry("FACTION_THRESHOLDS_INCONSISTENT",
                    "The faction's thresholds are out of order — the hostile cutoff must be lower than the friendly cutoff."),
            Map.entry("DIALOGUE_ALREADY_EXISTS",
                    "A dialogue with this id already exists — pick another id or edit the existing dialogue."),
            Map.entry("PERSIST_WRITE_FAILED",
                    "The data was valid but the file could not be written — check disk space and permissions, then see the server log.")
    );

    /**
     * One-line plain-English hint for a diagnostic. Unknown codes get a generic
     * message mentioning the line when known — never null, never throws.
     */
    public static String hintFor(DiagnosticError d) {
        String hint = d != null && d.code() != null ? HINTS.get(d.code()) : null;
        if (hint != null) {
            return hint;
        }
        if (d != null && d.line() > 0) {
            return "This file has a formatting problem near line " + d.line()
                    + " — see the server log for full details.";
        }
        return "This definition has a problem — see the server log for full details.";
    }
}
