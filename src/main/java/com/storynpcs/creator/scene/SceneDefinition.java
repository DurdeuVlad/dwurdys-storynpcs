package com.storynpcs.creator.scene;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/**
 * A bounded scene (P8-5): entity count, duration, and effect budgets with
 * explicit cancellation recovery — a scene can never run unbounded.
 */
public class SceneDefinition {

    public static final int SCHEMA_VERSION = 1;

    @JsonProperty
    private int schemaVersion = SCHEMA_VERSION;

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private List<NamespacedId> participantTemplateIds = new java.util.ArrayList<>();

    /** Max entities the scene may control at once. */
    @JsonProperty
    private int maxEntities = 16;

    /** Max scene duration; the session is cancelled (with recovery) past it. */
    @JsonProperty
    private int maxDurationTicks = 6_000;

    /** Ordered stage markers — text/positions the scene plays through. */
    @JsonProperty
    private List<SceneStage> stages = new java.util.ArrayList<>();

    /** Recovery behavior when the scene is cancelled mid-play. */
    @JsonProperty
    private CancelRecovery cancelRecovery = CancelRecovery.RESTORE_POSITIONS;

    public enum CancelRecovery {
        RESTORE_POSITIONS, RESPAWN_PRISTINE, LEAVE_IN_PLACE;

        /** Authored values are case/underscore-insensitive (`restore_positions`). */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static CancelRecovery fromString(String value) {
            if (value == null) {
                return null;
            }
            return CancelRecovery.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    public record SceneStage(
            @JsonProperty String name,
            @JsonProperty int durationTicks,
            @JsonProperty String cueText) {
        public SceneStage {
            if (durationTicks < 0) {
                throw new IllegalArgumentException("stage duration must be >= 0");
            }
        }
    }

    public SceneDefinition() {}

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public List<NamespacedId> getParticipantTemplateIds() { return List.copyOf(participantTemplateIds); }
    public void setParticipantTemplateIds(List<NamespacedId> ids) {
        this.participantTemplateIds = ids == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(ids);
    }

    public int getMaxEntities() { return maxEntities; }
    public void setMaxEntities(int maxEntities) {
        if (maxEntities < 1 || maxEntities > 64) {
            throw new IllegalArgumentException("maxEntities must be in [1,64]");
        }
        this.maxEntities = maxEntities;
    }

    public int getMaxDurationTicks() { return maxDurationTicks; }
    public void setMaxDurationTicks(int maxDurationTicks) {
        if (maxDurationTicks < 20 || maxDurationTicks > 1_200_000) {
            throw new IllegalArgumentException("maxDurationTicks must be in [20,1200000]");
        }
        this.maxDurationTicks = maxDurationTicks;
    }

    public List<SceneStage> getStages() { return List.copyOf(stages); }
    public void setStages(List<SceneStage> stages) {
        this.stages = stages == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(stages);
    }

    public CancelRecovery getCancelRecovery() { return cancelRecovery; }
    public void setCancelRecovery(CancelRecovery cancelRecovery) {
        this.cancelRecovery = cancelRecovery == null ? CancelRecovery.RESTORE_POSITIONS : cancelRecovery;
    }

    /** Load-time diagnostics: participant count must fit the entity budget. */
    public com.storynpcs.domain.common.ValidationResult validate() {
        var result = new com.storynpcs.domain.common.ValidationResult();
        if (participantTemplateIds.size() > maxEntities) {
            result.addError("SCENE_OVER_BUDGET",
                    participantTemplateIds.size() + " participants exceed maxEntities " + maxEntities);
        }
        if (stages.isEmpty()) {
            result.addWarning("SCENE_EMPTY", "scene has no stages");
        }
        return result;
    }
}
