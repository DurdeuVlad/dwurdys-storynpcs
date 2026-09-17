package com.storynpcs.domain.dialogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

public class DialogueCondition {
    public enum Type {
        QUEST_STATUS,     // target: questId, value: NOT_STARTED, IN_PROGRESS, COMPLETED
        FACTION_STANDING, // target: factionId, value: HOSTILE, NEUTRAL, FRIENDLY
        FACTION_POINTS,   // target: factionId, operator: >=, <=, ==, value: points
        HAS_ITEM,         // target: itemId, value: count
        HAS_PERMISSION    // target: permission node
    }

    @JsonProperty(required = true)
    private Type type;

    @JsonProperty(required = true)
    private String target;

    @JsonProperty
    private String operator = "==";

    @JsonProperty
    private String value = "";

    public DialogueCondition() {}

    public DialogueCondition(Type type, String target, String operator, String value) {
        this.type = type;
        this.target = target;
        this.operator = operator;
        this.value = value;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
