package com.storynpcs.domain.dialogue;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * A directed edge / choice leading from one dialogue node to another.
 */
public class DialogueEdge {
    @JsonProperty(required = true)
    private String text;

    @JsonProperty(required = true)
    private String targetNodeId;

    /** Optional localization key for the option label — blank means the literal {@link #text} is authoritative. */
    @JsonProperty
    private String textKey = "";

    @JsonProperty
    private boolean onceOnly = false;

    @JsonProperty
    private List<DialogueCondition> conditions = new ArrayList<>();

    @JsonProperty
    private List<DialogueAction> actions = new ArrayList<>();

    public DialogueEdge() {}

    public DialogueEdge(String text, String targetNodeId) {
        this.text = text;
        this.targetNodeId = targetNodeId;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }

    public String getTextKey() { return textKey; }
    public void setTextKey(String textKey) { this.textKey = textKey; }

    public boolean isOnceOnly() { return onceOnly; }
    public void setOnceOnly(boolean onceOnly) { this.onceOnly = onceOnly; }

    public List<DialogueCondition> getConditions() { return conditions; }
    public void setConditions(List<DialogueCondition> conditions) { this.conditions = conditions; }

    public List<DialogueAction> getActions() { return actions; }
    public void setActions(List<DialogueAction> actions) { this.actions = actions; }
}
