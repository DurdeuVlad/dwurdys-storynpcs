package com.storynpcs.domain.dialogue;

import com.storynpcs.domain.common.NamespacedId;

import java.util.*;

/**
 * Live active dialogue session between a player and an NPC.
 */
public class DialogueSession {
    private final UUID playerUuid;
    private final NamespacedId dialogueId;
    private final DialogueGraph graph;
    private String currentNodeId;
    private final Set<String> visitedNodes = new HashSet<>();
    private final Set<String> selectedOptionKeys = new HashSet<>();
    private boolean active = true;

    public DialogueSession(UUID playerUuid, DialogueGraph graph) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.dialogueId = graph.getId();
        this.currentNodeId = graph.getEntryNodeId();
        this.visitedNodes.add(currentNodeId);
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public NamespacedId getDialogueId() { return dialogueId; }
    public DialogueGraph getGraph() { return graph; }
    public String getCurrentNodeId() { return currentNodeId; }
    public boolean isActive() { return active; }

    public DialogueNode getCurrentNode() {
        return graph.getNode(currentNodeId).orElse(null);
    }

    public void advanceTo(String targetNodeId) {
        this.currentNodeId = targetNodeId;
        this.visitedNodes.add(targetNodeId);
        DialogueNode node = getCurrentNode();
        if (node == null || node.isTerminal()) {
            this.active = false;
        }
    }

    public void recordOptionSelection(String optionKey) {
        this.selectedOptionKeys.add(optionKey);
    }

    public boolean hasSelectedOption(String optionKey) {
        return this.selectedOptionKeys.contains(optionKey);
    }

    public void close() {
        this.active = false;
    }
}
