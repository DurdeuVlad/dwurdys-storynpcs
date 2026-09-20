package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;

import java.util.UUID;
import java.util.function.Consumer;

public class DialogueEditorScreenModel {

    private final NamespacedId dialogueId;
    private final String title;
    private final GraphEditorState editorState;
    private final Consumer<DialogueGraph> onSaveCallback;

    private String statusMessage = "";
    private boolean unsavedChanges = false;

    public DialogueEditorScreenModel(DialogueGraph graph, Consumer<DialogueGraph> onSaveCallback) {
        this.dialogueId = graph != null ? graph.getId() : NamespacedId.of("storynpcs:new_dialogue");
        this.title = graph != null ? graph.getTitle() : "New Dialogue";
        this.onSaveCallback = onSaveCallback;

        DialogueGraphLayout layout = graph != null
                ? DialogueGraphLayout.fromDialogueGraph(graph)
                : new DialogueGraphLayout();
        this.editorState = new GraphEditorState(layout);
    }

    public NamespacedId getDialogueId() { return dialogueId; }
    public String getTitle() { return title; }
    public GraphEditorState getEditorState() { return editorState; }
    public DialogueGraphLayout getLayout() { return editorState.getLayout(); }
    public String getStatusMessage() { return statusMessage; }
    public boolean hasUnsavedChanges() { return unsavedChanges; }

    public VisualNode addNode(String id, String text, double canvasX, double canvasY) {
        if (id == null || id.trim().isEmpty()) {
            id = "node_" + UUID.randomUUID().toString().substring(0, 6);
        }
        VisualNode node = new VisualNode(id, text, canvasX, canvasY);
        getLayout().addNode(node);
        if (getLayout().getEntryNodeId() == null) {
            getLayout().setEntryNodeId(id);
        }
        editorState.setSelectedNodeId(id);
        unsavedChanges = true;
        statusMessage = "Added node: " + id;
        return node;
    }

    /**
     * Removes the selected node and every edge touching it. Refuses to delete the
     * entry node — silently reassigning it would surprise the author, so the caller
     * must move the entry flag first. Returns false (with a status message) in that case.
     */
    public boolean removeSelectedNode() {
        String selectedId = editorState.getSelectedNodeId();
        if (selectedId == null) {
            return false;
        }
        VisualNode node = getLayout().getNodes().get(selectedId);
        if (node == null) {
            return false;
        }
        if (node.isEntryNode()) {
            statusMessage = "Cannot delete the entry node — set another node as entry first";
            return false;
        }
        getLayout().removeNode(selectedId);
        editorState.setSelectedNodeId(null);
        unsavedChanges = true;
        statusMessage = "Removed node: " + selectedId;
        return true;
    }

    /** Removes the selected edge only — both endpoint nodes are untouched. */
    public boolean removeSelectedEdge() {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null) {
            return false;
        }
        getLayout().removeEdge(edge);
        editorState.setSelectedEdge(null);
        unsavedChanges = true;
        statusMessage = "Removed edge " + edge.getSourceNodeId() + " -> " + edge.getTargetNodeId();
        return true;
    }

    public void updateSelectedNodeText(String newText) {
        String selectedId = editorState.getSelectedNodeId();
        if (selectedId != null) {
            VisualNode node = getLayout().getNodes().get(selectedId);
            if (node != null) {
                node.setText(newText != null ? newText : "");
                unsavedChanges = true;
            }
        }
    }

    public void setAsEntryNode(String nodeId) {
        if (getLayout().getNodes().containsKey(nodeId)) {
            getLayout().setEntryNodeId(nodeId);
            unsavedChanges = true;
            statusMessage = "Set entry node to: " + nodeId;
        }
    }

    public void startConnectingEdge(String sourceNodeId) {
        editorState.setConnectingSourceNodeId(sourceNodeId);
        statusMessage = "Click target node to connect edge...";
    }

    public void completeConnectingEdge(String targetNodeId, String choiceText) {
        if (editorState.getConnectingSourceNodeId() != null) {
            String src = editorState.getConnectingSourceNodeId();
            editorState.connectEdge(targetNodeId, choiceText != null && !choiceText.isEmpty() ? choiceText : "Continue");
            unsavedChanges = true;
            statusMessage = String.format("Connected %s -> %s", src, targetNodeId);
        }
    }

    public void cancelConnectingEdge() {
        editorState.setConnectingSourceNodeId(null);
        statusMessage = "Edge connection canceled";
    }

    public DialogueGraph exportGraph() {
        return getLayout().toDialogueGraph(dialogueId, title);
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage != null ? statusMessage : "";
    }

    /**
     * Sends the exported graph to the save callback (the server, in production).
     * The status reflects that a save was *requested* — the real outcome arrives
     * via {@link #onSaveResult} once the server validates and persists.
     */
    public void save() {
        DialogueGraph graph = exportGraph();
        if (onSaveCallback != null) {
            onSaveCallback.accept(graph);
            statusMessage = "Save sent — awaiting server confirmation...";
        } else {
            statusMessage = "Cannot save: no server connection.";
        }
    }

    /**
     * Applies the server's verdict on a save request. Only a successful save
     * clears the unsaved-changes flag; a rejection keeps it so nothing is lost.
     */
    public void onSaveResult(boolean success, String message) {
        statusMessage = message != null ? message : (success ? "Saved." : "Save rejected.");
        if (success) {
            unsavedChanges = false;
        }
    }
}