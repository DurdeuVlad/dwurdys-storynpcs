package com.storynpcs.domain.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.npc.NpcDefinitionSerde;

import java.util.Iterator;
import java.util.Set;

/**
 * Validates untrusted template import payloads before they are accepted (issue #77
 * acceptance criterion: "import rejects unsafe/unknown data and reports unsupported
 * fields"). Unlike {@link NpcTemplateSerde}, which is used only for trusted,
 * in-process server↔client transport, this validator treats its input as adversarial:
 * it never silently drops an unrecognized field, and it never throws on malformed
 * input — every failure is reported as a diagnostic on the returned
 * {@link ValidationResult}.
 *
 * <p><b>Scope boundary:</b> this validates the {@code NpcTemplate} envelope's own
 * shape and that its embedded definition snapshot deserializes cleanly. It does not
 * validate cross-references inside the embedded definition (dialogue/faction IDs
 * etc.) — that is {@code CrossReferenceValidator}'s job, run separately after a
 * successful import, as it already is for directly-authored YAML.
 */
public final class NpcTemplateImportValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SUPPORTED_FIELDS = Set.of(
            "id", "schemaVersion", "revision", "sourceNpcId",
            "embeddedDefinitionJson", "capturedAtEpochMillis", "updatedAtEpochMillis");

    private NpcTemplateImportValidator() {}

    public static ValidationResult validate(String importJson) {
        ValidationResult result = ValidationResult.valid();

        if (importJson == null || importJson.isBlank()) {
            result.addError("TEMPLATE_IMPORT_EMPTY", "Template import payload is empty");
            return result;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(importJson);
        } catch (Exception e) {
            result.addError("TEMPLATE_IMPORT_MALFORMED_JSON", "Template import payload is not valid JSON: " + e.getMessage());
            return result;
        }

        if (root == null || !root.isObject()) {
            result.addError("TEMPLATE_IMPORT_NOT_AN_OBJECT", "Template import payload must be a JSON object");
            return result;
        }

        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!SUPPORTED_FIELDS.contains(field)) {
                result.addError("TEMPLATE_IMPORT_UNSUPPORTED_FIELD",
                        "Unsupported field in template import payload: '" + field + "'");
            }
        }

        JsonNode idNode = root.get("id");
        if (idNode == null || idNode.isNull() || idNode.asText().isBlank()) {
            result.addError("TEMPLATE_IMPORT_MISSING_ID", "Template import payload is missing required field 'id'");
        }

        JsonNode schemaVersionNode = root.get("schemaVersion");
        if (schemaVersionNode != null && !schemaVersionNode.isNull()) {
            if (!schemaVersionNode.canConvertToInt() || schemaVersionNode.asInt() > NpcTemplate.CURRENT_SCHEMA_VERSION) {
                result.addError("TEMPLATE_IMPORT_UNSUPPORTED_SCHEMA_VERSION",
                        "Template import declares schemaVersion " + schemaVersionNode.asText()
                                + " which is newer than the supported version " + NpcTemplate.CURRENT_SCHEMA_VERSION);
            } else if (schemaVersionNode.asInt() < 1) {
                result.addError("TEMPLATE_IMPORT_INVALID_SCHEMA_VERSION",
                        "Template import declares a non-positive schemaVersion: " + schemaVersionNode.asText());
            }
        }

        JsonNode embeddedNode = root.get("embeddedDefinitionJson");
        if (embeddedNode == null || embeddedNode.isNull() || embeddedNode.asText().isBlank()) {
            result.addError("TEMPLATE_IMPORT_MISSING_DEFINITION",
                    "Template import payload is missing required field 'embeddedDefinitionJson'");
        } else if (NpcDefinitionSerde.fromJson(embeddedNode.asText()).isEmpty()) {
            result.addError("TEMPLATE_IMPORT_UNPARSEABLE_DEFINITION",
                    "Template import payload's embedded NPC definition could not be parsed");
        }

        return result;
    }
}
