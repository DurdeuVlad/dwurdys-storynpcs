package com.storynpcs.domain.progression;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.HashMap;
import java.util.Map;

public class QuestProgressState {
    public enum Status {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @JsonProperty(required = true)
    private NamespacedId questId;

    @JsonProperty
    private Status status = Status.NOT_STARTED;

    @JsonProperty
    private Map<String, Integer> objectiveCounts = new HashMap<>();

    public QuestProgressState() {}

    public QuestProgressState(NamespacedId questId) {
        this.questId = questId;
    }

    public NamespacedId getQuestId() { return questId; }
    public void setQuestId(NamespacedId questId) { this.questId = questId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Map<String, Integer> getObjectiveCounts() { return objectiveCounts; }
    public void setObjectiveCounts(Map<String, Integer> objectiveCounts) { this.objectiveCounts = objectiveCounts; }

    public int getCount(String objectiveId) {
        return objectiveCounts.getOrDefault(objectiveId, 0);
    }

    public void incrementCount(String objectiveId, int delta) {
        objectiveCounts.put(objectiveId, getCount(objectiveId) + delta);
    }
}
