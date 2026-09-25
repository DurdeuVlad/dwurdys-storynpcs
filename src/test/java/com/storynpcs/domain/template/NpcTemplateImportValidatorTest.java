package com.storynpcs.domain.template;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.npc.NpcDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NpcTemplateImportValidatorTest {

    private static String validPayload() {
        NpcDefinition def = new NpcDefinition(NamespacedId.of("storynpcs:import_npc"), "Import NPC");
        NpcTemplate template = NpcTemplate.capture(
                NamespacedId.of("storynpcs:import_template"), def.getId(), def);
        return NpcTemplateSerde.toJson(template);
    }

    @Test
    void acceptsAWellFormedPayload() {
        ValidationResult result = NpcTemplateImportValidator.validate(validPayload());
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsNullOrBlankPayload() {
        assertThat(NpcTemplateImportValidator.validate(null).isValid()).isFalse();
        assertThat(NpcTemplateImportValidator.validate("").isValid()).isFalse();
        assertThat(NpcTemplateImportValidator.validate("   ").isValid()).isFalse();
    }

    @Test
    void rejectsMalformedJson() {
        ValidationResult result = NpcTemplateImportValidator.validate("{ this is not json");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_MALFORMED_JSON"));
    }

    @Test
    void rejectsANonObjectPayload() {
        ValidationResult result = NpcTemplateImportValidator.validate("[1, 2, 3]");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_NOT_AN_OBJECT"));
    }

    @Test
    void reportsEachUnsupportedFieldByName() {
        String payload = "{"
                + "\"id\":\"storynpcs:import_template\","
                + "\"embeddedDefinitionJson\":\"{}\","
                + "\"executeArbitraryCode\":true,"
                + "\"legacyBinaryBlob\":\"deadbeef\""
                + "}";
        ValidationResult result = NpcTemplateImportValidator.validate(payload);
        assertThat(result.isValid()).isFalse();
        String report = result.formatReport();
        assertThat(report).contains("executeArbitraryCode");
        assertThat(report).contains("legacyBinaryBlob");
        assertThat(result.getErrors()).filteredOn(e -> e.toString().contains("TEMPLATE_IMPORT_UNSUPPORTED_FIELD")).hasSize(2);
    }

    @Test
    void rejectsAMissingId() {
        String payload = "{\"embeddedDefinitionJson\":\"{}\"}";
        ValidationResult result = NpcTemplateImportValidator.validate(payload);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_MISSING_ID"));
    }

    @Test
    void rejectsAMissingEmbeddedDefinition() {
        String payload = "{\"id\":\"storynpcs:import_template\"}";
        ValidationResult result = NpcTemplateImportValidator.validate(payload);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_MISSING_DEFINITION"));
    }

    @Test
    void rejectsAnUnparseableEmbeddedDefinition() {
        String payload = "{\"id\":\"storynpcs:import_template\",\"embeddedDefinitionJson\":\"not json at all\"}";
        ValidationResult result = NpcTemplateImportValidator.validate(payload);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_UNPARSEABLE_DEFINITION"));
    }

    @Test
    void rejectsASchemaVersionNewerThanSupported() {
        String payload = "{"
                + "\"id\":\"storynpcs:import_template\","
                + "\"schemaVersion\":99,"
                + "\"embeddedDefinitionJson\":\"{}\""
                + "}";
        ValidationResult result = NpcTemplateImportValidator.validate(payload);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_UNSUPPORTED_SCHEMA_VERSION"));
    }

    @Test
    void rejectsANonPositiveSchemaVersion() {
        String payload = "{"
                + "\"id\":\"storynpcs:import_template\","
                + "\"schemaVersion\":0,"
                + "\"embeddedDefinitionJson\":\"{}\""
                + "}";
        ValidationResult result = NpcTemplateImportValidator.validate(payload);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.toString().contains("TEMPLATE_IMPORT_INVALID_SCHEMA_VERSION"));
    }
}
