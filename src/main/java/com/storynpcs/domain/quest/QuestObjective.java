package com.storynpcs.domain.quest;

import com.fasterxml.jackson.annotation.JsonProperty;

public class QuestObjective {
    public enum Type {
        KILL_ENTITY,
        COLLECT_ITEM,
        VISIT_LOCATION,
        TALK_TO_NPC,
        CUSTOM
    }

    @JsonProperty(required = true)
    private String id;

    @JsonProperty(required = true)
    private Type type;

    @JsonProperty(required = true)
    private String target;

    @JsonProperty
    private int requiredCount = 1;

    public QuestObjective() {}

    public QuestObjective(String id, Type type, String target, int requiredCount) {
        this.id = id;
        this.type = type;
        this.target = target;
        this.requiredCount = requiredCount;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public int getRequiredCount() { return requiredCount; }
    public void setRequiredCount(int requiredCount) { this.requiredCount = requiredCount; }
}
