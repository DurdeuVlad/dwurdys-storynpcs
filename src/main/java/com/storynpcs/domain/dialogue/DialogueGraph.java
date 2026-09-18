package com.storynpcs.domain.dialogue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.*;

/**
 * Represents a complete directed-graph dialogue structure.
 * Supports cycles, arbitrary branching, multiple entry conditions, and terminal nodes.
 */
public class DialogueGraph {
    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private String title;

    @JsonProperty(required = true)
    private String entryNodeId;

    @JsonProperty(required = true)
    private Map<String, DialogueNode> nodes = new LinkedHashMap<>();

    public DialogueGraph() {}

    public DialogueGraph(NamespacedId id, String title, String entryNodeId) {
        this.id = id;
        this.title = title;
        this.entryNodeId = entryNodeId;
    }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getEntryNodeId() { return entryNodeId; }
    public void setEntryNodeId(String entryNodeId) { this.entryNodeId = entryNodeId; }

    public Map<String, DialogueNode> getNodes() { return nodes; }
    public void setNodes(Map<String, DialogueNode> nodes) { this.nodes = nodes; }

    public void addNode(DialogueNode node) {
        this.nodes.put(node.getId(), node);
    }

    public Optional<DialogueNode> getNode(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    @JsonIgnore // derived convenience accessor — not serialized state; also avoids Optional serialization
    public Optional<DialogueNode> getEntryNode() {
        return getNode(entryNodeId);
    }

    /**
     * Checks whether a directed path exists between startNodeId and targetNodeId.
     * Can detect reachability and intentional cycles.
     */
    public boolean canReach(String startNodeId, String targetNodeId) {
        if (!nodes.containsKey(startNodeId) || !nodes.containsKey(targetNodeId)) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(targetNodeId) && !visited.isEmpty()) {
                return true; // Reached target or detected cycle back to start
            }
            if (visited.add(current)) {
                DialogueNode node = nodes.get(current);
                if (node != null && node.getOptions() != null) {
                    for (DialogueEdge edge : node.getOptions()) {
                        queue.add(edge.getTargetNodeId());
                    }
                }
            }
        }
        return false;
    }
}
