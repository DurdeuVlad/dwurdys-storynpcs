package com.storynpcs.authoring.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The versioned schema/reference bundle an AI consumes (P10-2): every supported
 * definition family and operation, so generated content stays inside the
 * contract — unsupported fields are explicit, not silently dropped.
 */
public record SchemaBundle(
        int bundleVersion,
        Map<String, FamilySchema> families) {

    public record FamilySchema(
            String family,
            int schemaVersion,
            Set<String> supportedFields,
            Set<String> unsupportedFields,
            Set<String> operations) {}

    public static final int CURRENT_VERSION = 1;

    /** The supported contract — kept honest by a test asserting the code families exist. */
    public static SchemaBundle current() {
        return new SchemaBundle(CURRENT_VERSION, Map.ofEntries(
                Map.entry("npc", new FamilySchema("npc", 1,
                        Set.of("id", "name", "factionId", "dialogueId", "display", "stats", "ai", "inventory", "marks"),
                        Set.of("scripts", "linkedData"),
                        Set.of("create", "update", "delete", "spawn", "clone"))),
                Map.entry("dialogue", new FamilySchema("dialogue", 1,
                        Set.of("id", "title", "entryNodeId", "nodes"),
                        Set.of("availability"),
                        Set.of("create", "update", "delete"))),
                Map.entry("quest", new FamilySchema("quest", 1,
                        Set.of("id", "title", "description", "category", "repeatType",
                                "prerequisites", "objectives", "rewards"),
                        Set.of("completer"),
                        Set.of("create", "update", "delete", "assign", "complete"))),
                Map.entry("faction", new FamilySchema("faction", 1,
                        Set.of("id", "name", "defaultPoints", "hostileThreshold", "friendlyThreshold",
                                "color", "passive", "relationships"),
                        Set.of(),
                        Set.of("create", "update", "delete"))),
                Map.entry("template", new FamilySchema("template", 1,
                        Set.of("id", "schemaVersion", "revision", "description", "tags", "definition"),
                        Set.of(),
                        Set.of("create", "update", "delete", "instantiate"))),
                Map.entry("recipe", new FamilySchema("recipe", 1,
                        Set.of("id", "groupId", "grid", "outputItemId", "outputCount", "shapeless"),
                        Set.of(),
                        Set.of("create", "update", "delete"))),
                Map.entry("transport", new FamilySchema("transport", 1,
                        Set.of("id", "name", "dimensionId", "x", "y", "z", "yaw",
                                "unlockConditions", "fee", "visibleWhenLocked"),
                        Set.of(),
                        Set.of("create", "update", "delete", "unlock", "transfer"))),
                Map.entry("worldTool", new FamilySchema("worldTool", 1,
                        Set.of("id", "family", "dimensionId", "maxBlocksPerActivation", "hooks"),
                        Set.of("scriptSource"),
                        Set.of("create", "update", "delete", "activate")))
        ));
    }

    public boolean supports(String family, String field) {
        FamilySchema schema = families.get(family);
        return schema != null && schema.supportedFields().contains(field);
    }

    public List<String> unsupported(String family, String field) {
        FamilySchema schema = families.get(family);
        if (schema == null) {
            return List.of("unknown family: " + family);
        }
        if (schema.unsupportedFields().contains(field)) {
            return List.of("field '" + field + "' is explicitly unsupported in family '" + family + "'");
        }
        if (!schema.supportedFields().contains(field)) {
            return List.of("field '" + field + "' is not in the supported schema for '" + family + "'");
        }
        return List.of();
    }
}
