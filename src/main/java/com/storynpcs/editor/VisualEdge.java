package com.storynpcs.editor;

import com.storynpcs.domain.dialogue.DialogueAction;
import com.storynpcs.domain.dialogue.DialogueCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Visual representation of a dialogue edge in the GUI editor.
 * Stores all domain fields so Save does not erase conditions, actions, or onceOnly flags (VULN-44 fix).
 */
public class VisualEdge {
    private String sourceNodeId;
    private String targetNodeId;
    private String text;
    private boolean cyclic;

    // Preserved domain fields — VULN-44: previously stripped on every GUI save
    private boolean onceOnly = false;
    private List<DialogueCondition> conditions = new ArrayList<>();
    private List<DialogueAction> actions = new ArrayList<>();

    public VisualEdge() {}

    public VisualEdge(String sourceNodeId, String targetNodeId, String text) {
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.text = text != null ? text : "";
    }

    public String getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }

    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isCyclic() { return cyclic; }
    public void setCyclic(boolean cyclic) { this.cyclic = cyclic; }

    public boolean isOnceOnly() { return onceOnly; }
    public void setOnceOnly(boolean onceOnly) { this.onceOnly = onceOnly; }

    public List<DialogueCondition> getConditions() { return conditions; }
    public void setConditions(List<DialogueCondition> conditions) {
        this.conditions = conditions != null ? conditions : new ArrayList<>();
    }

    public List<DialogueAction> getActions() { return actions; }
    public void setActions(List<DialogueAction> actions) {
        this.actions = actions != null ? actions : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VisualEdge that = (VisualEdge) o;
        return Objects.equals(sourceNodeId, that.sourceNodeId) &&
               Objects.equals(targetNodeId, that.targetNodeId) &&
               Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNodeId, targetNodeId, text);
    }
}