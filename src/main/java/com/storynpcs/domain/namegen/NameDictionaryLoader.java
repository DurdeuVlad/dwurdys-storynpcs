package com.storynpcs.domain.namegen;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Strict YAML boundary for {@link NameDictionary} content (issue #123):
 * duplicate keys and unknown fields reject, future {@code schemaVersion}
 * refuses to load rather than silently misreading, and bound violations in
 * the dictionary model propagate as load failures.
 */
public final class NameDictionaryLoader {

    private NameDictionaryLoader() {}

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /**
     * Parses one dictionary document. Throws {@link IllegalArgumentException}
     * describing the failure; callers decide whether a bad document is fatal.
     */
    public static NameDictionary parse(String source, String yamlContent) {
        NameDictionary dict;
        try {
            dict = MAPPER.readValue(yamlContent, NameDictionary.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "name dictionary " + source + " failed to parse: " + e.getMessage(), e);
        }
        if (dict == null) {
            throw new IllegalArgumentException("name dictionary " + source + " is empty");
        }
        if (dict.getSchemaVersion() != NameDictionary.SCHEMA_VERSION) {
            throw new IllegalArgumentException("name dictionary " + source
                    + " declares schemaVersion " + dict.getSchemaVersion()
                    + " — expected " + NameDictionary.SCHEMA_VERSION
                    + " (future versions refuse to load)");
        }
        if (dict.getId() == null || dict.getId().isBlank()) {
            throw new IllegalArgumentException("name dictionary " + source + " lacks an id");
        }
        if (dict.getNames().size() < 4) {
            throw new IllegalArgumentException("name dictionary " + source
                    + " needs at least 4 seed names for a useful chain");
        }
        return dict;
    }
}
