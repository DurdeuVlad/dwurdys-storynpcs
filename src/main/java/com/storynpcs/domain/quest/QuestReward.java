package com.storynpcs.domain.quest;

import com.fasterxml.jackson.annotation.JsonProperty;

public class QuestReward {
    public enum Type {
        EXPERIENCE,
        ITEM,
        FACTION_POINTS,
        COMMAND
    }

    @JsonProperty(required = true)
    private Type type;

    @JsonProperty(required = true)
    private String target;

    @JsonProperty
    private int amount = 1;

    public QuestReward() {}

    public QuestReward(Type type, String target, int amount) {
        this.type = type;
        this.target = target;
        this.amount = amount;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
}
