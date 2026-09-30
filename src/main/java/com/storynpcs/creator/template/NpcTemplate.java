package com.storynpcs.creator.template;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;

/**
 * A named, versioned NPC template (P8-1). Templates persist with schema and
 * revision; cloning deep-copies the definition so clones never share mutable
 * state with the template or each other.
 */
public class NpcTemplate {

    public static final int SCHEMA_VERSION = 1;

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty
    private long revision;

    @JsonProperty
    private String description = "";

    @JsonProperty
    private java.util.List<String> tags = new java.util.ArrayList<>();

    @JsonProperty
    private NpcDefinition definition;

    public NpcTemplate() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }

    public java.util.List<String> getTags() { return java.util.List.copyOf(tags); }
    public void setTags(java.util.List<String> tags) {
        this.tags = tags == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(tags);
    }

    public NpcDefinition getDefinition() { return definition; }
    public void setDefinition(NpcDefinition definition) { this.definition = definition; }

    /** Deep-copy the definition for a clone — callers must mutate the copy, never the template. */
    public NpcDefinition instantiate(NamespacedId newId) {
        NpcDefinition copy = com.storynpcs.domain.npc.NpcDefinitionSerde
                .fromJson(com.storynpcs.domain.npc.NpcDefinitionSerde.toJson(definition))
                .orElseThrow(() -> new IllegalStateException("template definition failed to round-trip"));
        copy.setId(newId);
        return copy;
    }
}
