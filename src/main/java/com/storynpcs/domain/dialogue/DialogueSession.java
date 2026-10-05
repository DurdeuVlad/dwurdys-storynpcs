package com.storynpcs.domain.dialogue;

import com.storynpcs.domain.common.NamespacedId;

import java.util.*;

/**
 * Live active dialogue session between a player and an NPC.
 */
public class DialogueSession {
    private final UUID sessionId = UUID.randomUUID();
    private final UUID playerUuid;
    private final NamespacedId dialogueId;
    /** Rebindable on definition reload so sessions never walk a superseded graph instance. */
    private DialogueGraph graph;
    private String currentNodeId;
    private final Set<String> visitedNodes = new HashSet<>();
    private final Set<String> selectedOptionKeys = new HashSet<>();
    private boolean active = true;

    private final UUID npcEntityUuid;
    private final String dimensionId;
    private final double originX;
    private final double originY;
    private final double originZ;
    /** Display name of the speaking NPC (resolved at session start), or null when unavailable. */
    private String npcDisplayName;
    /**
     * Opaque choice tokens issued with the last rendered view, aligned to that
     * view's option order. Server-side callers that still address choices by
     * index resolve the token here — the protocol itself has no index path.
     */
    private List<DialogueChoiceProtocol.ChoiceToken> issuedTokens = List.of();

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
    public UUID getSessionId() { return sessionId; }
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
    public String getNpcDisplayName() { return npcDisplayName; }
    public void setNpcDisplayName(String npcDisplayName) { this.npcDisplayName = npcDisplayName; }

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

    /**
     * Rebinds the session to a freshly loaded definition of the same dialogue.
     * Callers must verify the current node still exists in the new graph first.
     */
    public void rebindGraph(DialogueGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (!this.dialogueId.equals(graph.getId())) {
            throw new IllegalArgumentException(
                    "rebind target is a different dialogue: " + graph.getId());
        }
        this.graph = graph;
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

    public List<DialogueChoiceProtocol.ChoiceToken> getIssuedTokens() {
        return issuedTokens;
    }

    public void setIssuedTokens(List<DialogueChoiceProtocol.ChoiceToken> tokens) {
        this.issuedTokens = tokens != null ? List.copyOf(tokens) : List.of();
    }
}
