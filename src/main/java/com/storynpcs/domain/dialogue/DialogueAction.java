package com.storynpcs.domain.dialogue;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DialogueAction {
    public enum Type {
        START_QUEST,
        ADVANCE_QUEST,
        COMPLETE_QUEST,
        ADJUST_FACTION,
        GIVE_ITEM,
        EXECUTE_COMMAND,
        CLOSE_DIALOGUE
    }

    @JsonProperty(required = true)
    private Type type;

    @JsonProperty
    private String target = "";

    @JsonProperty
    private String value = "";

    public DialogueAction() {}

    public DialogueAction(Type type, String target, String value) {
        this.type = type;
        this.target = target;
        this.value = value;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
