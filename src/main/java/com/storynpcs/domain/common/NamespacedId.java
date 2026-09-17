package com.storynpcs.domain.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable namespaced identifier representing "namespace:path".
 * Validates identifier syntax strictly (lowercase letters, digits, underscores, hyphens, and slashes).
 */
public final class NamespacedId implements Comparable<NamespacedId> {
    public static final String DEFAULT_NAMESPACE = "storynpcs";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z0-9_.-]+$");
    private static final Pattern PATH_PATTERN = Pattern.compile("^[a-z0-9/_.-]+$");

    private final String namespace;
    private final String path;

    public NamespacedId(String namespace, String path) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Namespace cannot be null or blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path cannot be null or blank");
        }
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace: '" + namespace + "'. Must match " + NAMESPACE_PATTERN);
        }
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid path: '" + path + "'. Must match " + PATH_PATTERN);
        }
        this.namespace = namespace;
        this.path = path;
    }

    @JsonCreator
    public static NamespacedId of(String stringRepresentation) {
        if (stringRepresentation == null || stringRepresentation.isBlank()) {
            throw new IllegalArgumentException("NamespacedId string cannot be null or blank");
        }
        int colonIndex = stringRepresentation.indexOf(':');
        if (colonIndex == -1) {
            return new NamespacedId(DEFAULT_NAMESPACE, stringRepresentation);
        }
        String namespace = stringRepresentation.substring(0, colonIndex);
        String path = stringRepresentation.substring(colonIndex + 1);
        return new NamespacedId(namespace, path);
    }

    public static NamespacedId of(String namespace, String path) {
        return new NamespacedId(namespace, path);
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override
    @JsonValue
    public String toString() {
        return namespace + ":" + path;
    }

    public String asString() {
        return toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NamespacedId that = (NamespacedId) o;
        return Objects.equals(namespace, that.namespace) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    @Override
    public int compareTo(NamespacedId o) {
        int cmp = this.namespace.compareTo(o.namespace);
        if (cmp != 0) return cmp;
        return this.path.compareTo(o.path);
    }
}
