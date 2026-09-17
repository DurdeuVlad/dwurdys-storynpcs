package com.storynpcs.editor;

import java.util.Objects;

public class VisualEdge {
    private String sourceNodeId;
    private String targetNodeId;
    private String text;
    private boolean cyclic;

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