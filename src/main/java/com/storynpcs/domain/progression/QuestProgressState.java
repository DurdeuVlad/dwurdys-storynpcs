package com.storynpcs.domain.progression;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.HashMap;
import java.util.Map;

public class QuestProgressState {
    public static final int MAX_OBJECTIVE_COUNT = 100_000;

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

    @JsonProperty
    private long stateRevision;

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

    public long getStateRevision() { return stateRevision; }
    public void setStateRevision(long stateRevision) {
        if (stateRevision < 0) throw new IllegalArgumentException("stateRevision must be non-negative");
        this.stateRevision = stateRevision;
    }

    public void advanceStateRevision() {
        stateRevision = Math.addExact(stateRevision, 1L);
    }

    public int getCount(String objectiveId) {
        return objectiveCounts.getOrDefault(objectiveId, 0);
    }

    public void incrementCount(String objectiveId, int delta) {
        // VULN-56: clamp to [0, 100_000] to prevent integer overflow causing permanent quest softlock
        long newCount = (long) getCount(objectiveId) + (long) delta;
        int clamped = (int) Math.max(0L, Math.min((long) MAX_OBJECTIVE_COUNT, newCount));
        objectiveCounts.put(objectiveId, clamped);
    }

    public QuestProgressState copy() {
        QuestProgressState copy = new QuestProgressState(questId);
        copy.status = status;
        copy.objectiveCounts = new HashMap<>(objectiveCounts);
        copy.stateRevision = stateRevision;
        return copy;
    }
}
