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

    private final UUID npcEntityUuid;
    private final String dimensionId;
    private final double originX;
    private final double originY;
    private final double originZ;

    public DialogueSession(UUID playerUuid, DialogueGraph graph) {
        this(playerUuid, graph, null, null, 0.0, 0.0, 0.0);
    }

    public DialogueSession(UUID playerUuid, DialogueGraph graph, UUID npcEntityUuid, String dimensionId, double originX, double originY, double originZ) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.dialogueId = graph.getId();
        this.currentNodeId = graph.getEntryNodeId();
        this.visitedNodes.add(currentNodeId);
        this.npcEntityUuid = npcEntityUuid;
        this.dimensionId = dimensionId;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public NamespacedId getDialogueId() { return dialogueId; }
    public DialogueGraph getGraph() { return graph; }
    public String getCurrentNodeId() { return currentNodeId; }
    public boolean isActive() { return active; }
    public UUID getNpcEntityUuid() { return npcEntityUuid; }
    public String getDimensionId() { return dimensionId; }
    public double getOriginX() { return originX; }
    public double getOriginY() { return originY; }
    public double getOriginZ() { return originZ; }
    public boolean hasLocation() { return dimensionId != null; }

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
