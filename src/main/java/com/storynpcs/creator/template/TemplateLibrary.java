package com.storynpcs.creator.template;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;

/**
 * In-memory template registry (P8-1): namespaced storage, deterministic
 * tag/name search, safe import with explicit rejection of unsafe/unknown
 * payloads, and dependent-spawner reporting on delete.
 */
public final class TemplateLibrary {

    private final Map<String, NpcTemplate> templates = new LinkedHashMap<>();
    private final Map<String, java.util.List<NamespacedId>> spawnerDependents = new LinkedHashMap<>();

    public void put(NpcTemplate template) {
        if (template.getId() == null) {
            throw new IllegalArgumentException("template id required");
        }
        templates.put(template.getId().toString(), template);
    }

    public Optional<NpcTemplate> get(NamespacedId id) {
        return Optional.ofNullable(templates.get(id.toString()));
    }

    /** All registered templates in deterministic insertion order. */
    public List<NpcTemplate> all() {
        return List.copyOf(templates.values());
    }

    /** Wipe both the template map and dependent-spawner bookkeeping. */
    public void clear() {
        templates.clear();
        spawnerDependents.clear();
    }

    /** Deterministic case-insensitive substring search over id, description, and tags. */
    public List<NamespacedId> search(String query) {
        String q = query == null ? "" : query.toLowerCase();
        return templates.values().stream()
                .filter(t -> t.getId().toString().toLowerCase().contains(q)
                        || t.getDescription().toLowerCase().contains(q)
                        || t.getTags().stream().anyMatch(tag -> tag.toLowerCase().contains(q)))
                .map(NpcTemplate::getId)
                .sorted(Comparator.comparing(NamespacedId::toString))
                .toList();
    }

    /** Register a spawner as dependent on a template — surfaced on delete. */
    public void registerSpawner(NamespacedId templateId, NamespacedId spawnerId) {
        spawnerDependents.computeIfAbsent(templateId.toString(), k -> new java.util.ArrayList<>()).add(spawnerId);
    }

    public List<NamespacedId> dependentSpawners(NamespacedId templateId) {
        return List.copyOf(spawnerDependents.getOrDefault(templateId.toString(), List.of()));
    }

    /**
     * Delete a template. Reports dependent spawners in the result — callers
     * decide whether to proceed; dependents are never silently orphaned by the
     * library itself.
     */
    public DeleteOutcome delete(NamespacedId templateId) {
        List<NamespacedId> deps = dependentSpawners(templateId);
        boolean removed = templates.remove(templateId.toString()) != null;
        spawnerDependents.remove(templateId.toString());
        return new DeleteOutcome(removed, deps);
    }

    public record DeleteOutcome(boolean removed, List<NamespacedId> dependentSpawners) {
        public DeleteOutcome { dependentSpawners = dependentSpawners == null ? List.of() : List.copyOf(dependentSpawners); }
    }

    /**
     * Safe import: schema version must be supported and every JSON field must
     * map to a known property — unknown fields are rejected, not ignored.
     */
    public ValidationResult importValidation(com.fasterxml.jackson.databind.JsonNode node) {
        ValidationResult result = new ValidationResult();
        int version = node.has("schemaVersion") ? node.get("schemaVersion").asInt(-1) : -1;
        if (version != NpcTemplate.SCHEMA_VERSION) {
            result.addError("TEMPLATE_SCHEMA_VERSION",
                    "unsupported schemaVersion " + version + " (expected " + NpcTemplate.SCHEMA_VERSION + ")");
        }
        if (!node.hasNonNull("id")) {
            result.addError("TEMPLATE_MISSING_ID", "template requires an id");
        }
        if (!node.hasNonNull("definition")) {
            result.addError("TEMPLATE_MISSING_DEFINITION", "template requires a definition");
        }
        java.util.Set<String> known = java.util.Set.of(
                "id", "schemaVersion", "revision", "description", "tags", "definition");
        node.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                result.addError("TEMPLATE_UNKNOWN_FIELD", "unsupported template field: " + name);
            }
        });
        return result;
    }

    public int size() { return templates.size(); }
}
