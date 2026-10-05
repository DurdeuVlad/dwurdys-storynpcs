package com.storynpcs.migration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P11-1: field-level mapping registry. Every incoming top-level field is
 * classified so the report can show exactly what happened to it — nothing is
 * silently dropped.
 */
public final class FieldMappingRegistry {

    public enum Action {
        /** Field binds to the current schema unchanged. */
        DIRECT,
        /** Legacy/alias form accepted and migrated by the loader or importer. */
        MIGRATED,
        /** Recognized target field with no StoryNPCs equivalent — reported, never silently dropped. */
        UNSUPPORTED,
        /** Field not in the registry — the document fails closed. */
        UNKNOWN,
        /** Envelope metadata consumed by the schema layer, not a content field. */
        ENVELOPE
    }

    /** Per-field outcome recorded in the import report. */
    public record FieldMapping(String fieldPath, Action action, String note, String evidenceRef) {
        public FieldMapping {
            if (fieldPath == null || fieldPath.isBlank()) throw new IllegalArgumentException("fieldPath required");
            if (action == null) throw new IllegalArgumentException("action required");
            note = note == null ? "" : note;
            evidenceRef = evidenceRef == null ? "" : evidenceRef;
        }
    }

    /** Fields that are recognized-but-unsupported across every family. */
    private static final Set<String> UNSUPPORTED_KEYS = Set.of(
            "scripts",        // P9-2 accepts typed hook bindings, not arbitrary source scripts
            "linkedData",     // CustomNPCs LinkedData class — transform rules cover the use case
            "scriptData"      // binary script payload
    );

    private static final Map<String, Set<String>> KNOWN_KEYS = Map.of(
            "npc", Set.of("id", "display", "stats", "ai", "dialogueId", "factionId",
                    "mark", "marks", "inventory", "rules", "trader", "banker",
                    // NPC-attached role sections loadable since P6/P8 slices —
                    // importer must not quarantine valid NPC docs carrying them.
                    "job", "companion", "bard", "healer", "postman", "transporter"),
            "dialogue", Set.of("id", "title", "titleKey", "availability", "entryNodeId", "nodes"),
            "quest", Set.of("id", "title", "description", "category", "repeatType",
                    "prerequisites", "objectives", "rewards"),
            "faction", Set.of("id", "name", "defaultPoints", "hostileThreshold",
                    "friendlyThreshold", "color", "passive", "relationships"),
            "template", Set.of("id", "schemaVersion", "revision", "description", "tags", "definition")
    );

    /** Value-level legacy forms the loader migrates (field → note). */
    private static final Map<String, String> VALUE_MIGRATIONS = Map.of(
            "inventory", "legacy string-array form migrates onto visible drop slots at 100% chance",
            "repeatType", "value ONCE loads as NORMAL"
    );

    private FieldMappingRegistry() {}

    /**
     * Classify every top-level key of a normalized document. Entries are
     * returned in deterministic document order.
     */
    public static List<FieldMapping> classify(String family, Iterable<String> topLevelFields) {
        var out = new java.util.ArrayList<FieldMapping>();
        for (String field : topLevelFields) {
            if ("schemaVersion".equals(field)) {
                out.add(new FieldMapping(field, Action.ENVELOPE, "version envelope consumed before binding", "P0-1/data"));
            } else if (UNSUPPORTED_KEYS.contains(field)) {
                out.add(new FieldMapping(field, Action.UNSUPPORTED,
                        "recognized but has no StoryNPCs equivalent — reported, not dropped silently", "P0-1/" + family));
            } else if (KNOWN_KEYS.getOrDefault(family, Set.of()).contains(field)) {
                String note = VALUE_MIGRATIONS.getOrDefault(field, "");
                out.add(new FieldMapping(field, note.isEmpty() ? Action.DIRECT : Action.MIGRATED,
                        note, "P0-1/" + family));
            } else {
                out.add(new FieldMapping(field, Action.UNKNOWN,
                        "field not in the mapping registry — document fails closed", "P0-1/" + family));
            }
        }
        return List.copyOf(out);
    }

    public static boolean hasFatal(List<FieldMapping> mappings) {
        return mappings.stream().anyMatch(m -> m.action() == Action.UNKNOWN);
    }

    public static Set<String> knownKeys(String family) {
        return KNOWN_KEYS.getOrDefault(family, Set.of());
    }
}
