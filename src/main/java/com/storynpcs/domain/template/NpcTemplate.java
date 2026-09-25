package com.storynpcs.domain.template;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;

import java.util.Objects;
import java.util.Optional;

/**
 * A persistent, named, versioned capture of an {@link NpcDefinition} (issue #77 — P8-1
 * persistent templates foundation).
 *
 * <p><b>Clone isolation:</b> the captured definition is stored as an independent JSON
 * snapshot ({@link #getEmbeddedDefinitionJson()}), not a live reference to the source
 * {@link NpcDefinition}. Every {@link #capture} and {@link #instantiate} round-trips
 * through {@link NpcDefinitionSerde}, so a template and the definitions produced from
 * it never share a mutable list, map, or nested object — mutating one can never affect
 * another. This mirrors how {@code FactionSerde}/{@code QuestSerde} are already used
 * to transport definitions between server and client without aliasing state.
 *
 * <p><b>Scope boundary:</b> this is the domain model only — capture, versioned
 * re-capture, and clone-isolated instantiation. It intentionally does NOT implement a
 * persistent store/registry, spawner capability, quotas/placement/cleanup, creator
 * UI/commands, or export/import file I/O — see {@link NpcTemplateImportValidator} for
 * the one piece of import-safety validation this slice does cover, and the PR body for
 * the full list of what's still open on #77.
 */
public final class NpcTemplate {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    @JsonProperty
    private int revision = 1;

    @JsonProperty
    private NamespacedId sourceNpcId;

    @JsonProperty(required = true)
    private String embeddedDefinitionJson;

    @JsonProperty
    private long capturedAtEpochMillis;

    @JsonProperty
    private long updatedAtEpochMillis;

    public NpcTemplate() {}

    private NpcTemplate(NamespacedId id, NamespacedId sourceNpcId, String embeddedDefinitionJson) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceNpcId = sourceNpcId;
        this.embeddedDefinitionJson = Objects.requireNonNull(embeddedDefinitionJson, "embeddedDefinitionJson");
        long now = System.currentTimeMillis();
        this.capturedAtEpochMillis = now;
        this.updatedAtEpochMillis = now;
    }

    /** Captures a fresh, isolated snapshot of {@code source} as a new template. */
    public static NpcTemplate capture(NamespacedId templateId, NamespacedId sourceNpcId, NpcDefinition source) {
        Objects.requireNonNull(source, "source");
        String json = NpcDefinitionSerde.toJson(source);
        return new NpcTemplate(templateId, sourceNpcId, json);
    }

    /**
     * Replaces this template's snapshot with a fresh capture of {@code source} and
     * bumps {@link #getRevision()}. The previous snapshot is discarded — callers that
     * need history must persist prior revisions themselves (out of scope here).
     */
    public void recapture(NpcDefinition source) {
        Objects.requireNonNull(source, "source");
        this.embeddedDefinitionJson = NpcDefinitionSerde.toJson(source);
        this.revision++;
        this.updatedAtEpochMillis = System.currentTimeMillis();
    }

    /**
     * Produces a brand-new, fully isolated {@link NpcDefinition} from this template's
     * snapshot, assigned {@code newDefinitionId}. Empty if the stored snapshot cannot
     * be deserialized (e.g. corrupted persistence). Two calls to this method never
     * return definitions that share any mutable field.
     */
    public Optional<NpcDefinition> instantiate(NamespacedId newDefinitionId) {
        Objects.requireNonNull(newDefinitionId, "newDefinitionId");
        Optional<NpcDefinition> copy = NpcDefinitionSerde.fromJson(embeddedDefinitionJson);
        copy.ifPresent(def -> def.setId(newDefinitionId));
        return copy;
    }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public NamespacedId getSourceNpcId() { return sourceNpcId; }
    public void setSourceNpcId(NamespacedId sourceNpcId) { this.sourceNpcId = sourceNpcId; }

    public String getEmbeddedDefinitionJson() { return embeddedDefinitionJson; }
    public void setEmbeddedDefinitionJson(String embeddedDefinitionJson) { this.embeddedDefinitionJson = embeddedDefinitionJson; }

    public long getCapturedAtEpochMillis() { return capturedAtEpochMillis; }
    public void setCapturedAtEpochMillis(long capturedAtEpochMillis) { this.capturedAtEpochMillis = capturedAtEpochMillis; }

    public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }
    public void setUpdatedAtEpochMillis(long updatedAtEpochMillis) { this.updatedAtEpochMillis = updatedAtEpochMillis; }
}
