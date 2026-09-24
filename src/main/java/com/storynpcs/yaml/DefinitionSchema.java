package com.storynpcs.yaml;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storynpcs.domain.common.ValidationResult;

import java.io.IOException;

/**
 * Version envelope and migration boundary for authoring YAML definitions.
 *
 * <p>Definition objects intentionally do not carry persistence metadata. The
 * envelope is normalized before Jackson binds the remaining fields to a
 * domain object, so every definition family gets the same forward-compatibility
 * and legacy-version behavior.</p>
 */
public final class DefinitionSchema {
    public static final int LEGACY_VERSION = 0;
    public static final int CURRENT_VERSION = 1;
    public static final String VERSION_FIELD = "schemaVersion";

    private DefinitionSchema() {}

    /**
     * Parses and migrates a YAML definition document to the current envelope.
     * The returned object has the envelope field removed before domain binding;
     * callers must not bind a rejected document.
     */
    public static JsonNode normalize(ObjectMapper mapper, String yamlContent,
                                     String sourceName, ValidationResult result) throws IOException {
        JsonNode root;
        try (JsonParser parser = mapper.getFactory().createParser(yamlContent)) {
            root = mapper.readTree(parser);
            if (parser.nextToken() != null) {
                int line = parser.getCurrentLocation() != null ? parser.getCurrentLocation().getLineNr() : 1;
                int column = parser.getCurrentLocation() != null ? parser.getCurrentLocation().getColumnNr() : 1;
                result.addError(sourceName, line, column, "SCHEMA_MULTIPLE_DOCUMENTS",
                        "A definition file must contain exactly one YAML document");
                return null;
            }
        }
        if (root == null) {
            result.addError(sourceName, 1, 1, "SCHEMA_EMPTY_DOCUMENT",
                    "Definition document is empty");
            return null;
        }
        if (!root.isObject()) {
            result.addError(sourceName, 1, 1, "SCHEMA_ROOT_OBJECT_REQUIRED",
                    "Definition document root must be a YAML mapping/object");
            return null;
        }

        ObjectNode document = (ObjectNode) root;
        JsonNode versionNode = document.get(VERSION_FIELD);
        int version = LEGACY_VERSION;
        if (versionNode != null) {
            int line = lineOfField(yamlContent, VERSION_FIELD);
            int column = columnOfField(yamlContent, VERSION_FIELD);
            if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
                result.addError(sourceName, line, column, "SCHEMA_VERSION_INVALID",
                        "'" + VERSION_FIELD + "' must be a 32-bit integer");
                return null;
            }
            version = versionNode.intValue();
            if (version < LEGACY_VERSION) {
                result.addError(sourceName, line, column, "SCHEMA_VERSION_INVALID",
                        "'" + VERSION_FIELD + "' cannot be negative");
                return null;
            }
            if (version > CURRENT_VERSION) {
                result.addError(sourceName, line, column, "SCHEMA_VERSION_UNSUPPORTED",
                        "Definition uses future schema version " + version
                                + "; highest supported version is " + CURRENT_VERSION);
                return null;
            }
        }

        migrate(document, version);
        document.remove(VERSION_FIELD);
        return document;
    }

    private static void migrate(ObjectNode document, int version) {
        // Version 0 is the pre-envelope format. Its field names are already
        // the version-1 domain shape, so migration only records the boundary.
        if (version == LEGACY_VERSION) {
            document.put(VERSION_FIELD, CURRENT_VERSION);
        }
    }

    public static int lineOfField(String yamlContent, String fieldName) {
        String[] lines = yamlContent.split("\\R", -1);
        String prefix = fieldName + ":";
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith(prefix)) {
                return index + 1;
            }
        }
        return 1;
    }

    public static int columnOfField(String yamlContent, String fieldName) {
        String[] lines = yamlContent.split("\\R", -1);
        String prefix = fieldName + ":";
        for (String line : lines) {
            int start = line.indexOf(prefix);
            if (start >= 0 && line.substring(0, start).trim().isEmpty()) {
                return start + 1;
            }
        }
        return 1;
    }
}
