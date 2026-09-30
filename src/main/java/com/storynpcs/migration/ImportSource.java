package com.storynpcs.migration;

/**
 * P11-1: declared import sources. Only explicitly supported formats are
 * accepted; everything else fails closed with a named reason instead of a
 * silent guess. The supplied CustomNPCs JAR is an engine artifact — creator
 * world NBT/binary formats stay UNSUPPORTED until real samples exist.
 */
public record ImportSource(Kind kind, String sourceId, int formatVersion, String origin) {

    public enum Kind {
        /** Directory of StoryNPCs YAML definition documents (schemaVersion envelope). */
        STORYNPCS_YAML_PACKAGE,
        /** StoryNPCs JSON serde documents (NpcDefinitionSerde/QuestSerde form). */
        STORYNPCS_JSON_PACKAGE,
        /** Exported {@code creator/template} packages (template JSON + embedded definition). */
        TEMPLATE_PACKAGE,
        /** CustomNPCs world NBT / binary saves — no verified format evidence exists. */
        TARGET_WORLD_NBT,
        /** CustomNPCs text export — no verified format evidence exists. */
        CUSTOMNPCS_TEXT_EXPORT
    }

    public enum Support {
        SUPPORTED,
        UNSUPPORTED_NEEDS_EVIDENCE
    }

    public static final int CURRENT_FORMAT_VERSION = 1;

    public ImportSource {
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId required");
        if (formatVersion < 0) throw new IllegalArgumentException("formatVersion cannot be negative");
        origin = origin == null ? "" : origin;
    }

    public static Support support(Kind kind) {
        return switch (kind) {
            case STORYNPCS_YAML_PACKAGE, STORYNPCS_JSON_PACKAGE, TEMPLATE_PACKAGE -> Support.SUPPORTED;
            case TARGET_WORLD_NBT, CUSTOMNPCS_TEXT_EXPORT -> Support.UNSUPPORTED_NEEDS_EVIDENCE;
        };
    }

    public static String unsupportedReason(Kind kind) {
        return switch (kind) {
            case TARGET_WORLD_NBT -> "Target world NBT format is unverified — register records the source as needs-input until an export sample exists";
            case CUSTOMNPCS_TEXT_EXPORT -> "CustomNPCs text export format is unverified — no sample corpus is available to define a mapping";
            default -> "supported";
        };
    }
}
