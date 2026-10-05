package com.storynpcs.domain.namegen;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import java.util.ArrayList;
import java.util.List;

/**
 * A versioned, authorable name dictionary feeding the Markov name generator
 * (issue #123). Dictionaries are YAML content — bundled read-only assets at
 * {@code data/storynpcs/namegen/} plus creator overrides at
 * {@code config/storynpcs/namegen/} — never Java-hardcoded.
 */
public class NameDictionary {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NAMES = 512;
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MIN_ORDER = 2;
    public static final int MAX_ORDER = 4;

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty
    private String id;

    @JsonProperty
    private String culture = "";

    /** Markov chain context length, bounded [2,4]. */
    @JsonProperty
    private int order = 2;

    @JsonProperty
    private List<String> names = new ArrayList<>();

    public NameDictionary() {}

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getId() { return id; }
    public void setId(String id) {
        // Validates namespaced shape; the catalog keys on this.
        NamespacedId.of(id != null ? id : "");
        this.id = id;
    }

    public NamespacedId namespacedId() {
        return NamespacedId.of(id != null ? id : "storynpcs:unknown");
    }

    public String getCulture() { return culture; }
    public void setCulture(String culture) {
        String normalized = culture != null ? culture.trim() : "";
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("culture exceeds 64 characters");
        }
        this.culture = normalized;
    }

    public int getOrder() { return order; }
    public void setOrder(int order) {
        if (order < MIN_ORDER || order > MAX_ORDER) {
            throw new IllegalArgumentException(
                    "order must be between " + MIN_ORDER + " and " + MAX_ORDER);
        }
        this.order = order;
    }

    public List<String> getNames() {
        return java.util.Collections.unmodifiableList(names);
    }

    public void setNames(List<String> values) {
        this.names = new ArrayList<>();
        if (values == null) {
            return;
        }
        if (values.size() > MAX_NAMES) {
            throw new IllegalArgumentException(
                    "names cannot exceed " + MAX_NAMES + " entries");
        }
        for (String name : values) {
            if (name == null) {
                throw new IllegalArgumentException("names cannot contain null");
            }
            String trimmed = name.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "name entries must be 1-" + MAX_NAME_LENGTH + " characters");
            }
            if (trimmed.chars().anyMatch(c -> c < 0x20)) {
                throw new IllegalArgumentException(
                        "name entries cannot contain control characters");
            }
            this.names.add(trimmed);
        }
    }
}
