package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;

import java.util.*;

public class DialogueGraphLayout {

    private String entryNodeId;
    // Preserved graph-level domain fields (same VULN-44 rule as edge fields):
    // a GUI save must not erase authored titleKey/availability.
    private String titleKey = "";
    private List<com.storynpcs.domain.dialogue.DialogueCondition> availability = new ArrayList<>();
    private final Map<String, VisualNode> nodes = new LinkedHashMap<>();
    private final List<VisualEdge> edges = new ArrayList<>();

    public DialogueGraphLayout() {}

    public String getEntryNodeId() { return entryNodeId; }
    public void setEntryNodeId(String entryNodeId) {
        this.entryNodeId = entryNodeId;
        updateEntryFlags();
    }

    public String getTitleKey() { return titleKey; }
    public void setTitleKey(String titleKey) { this.titleKey = titleKey != null ? titleKey : ""; }

    public List<com.storynpcs.domain.dialogue.DialogueCondition> getAvailability() { return availability; }
    public void setAvailability(List<com.storynpcs.domain.dialogue.DialogueCondition> availability) {
        this.availability = availability != null ? availability : new ArrayList<>();
    }

    public Map<String, VisualNode> getNodes() { return Collections.unmodifiableMap(nodes); }
    public List<VisualEdge> getEdges() { return Collections.unmodifiableList(edges); }

    public void addNode(VisualNode node) {
        if (node != null && node.getId() != null) {
            nodes.put(node.getId(), node);
            updateEntryFlags();
        }
    }

    public void removeNode(String nodeId) {
        nodes.remove(nodeId);
        edges.removeIf(e -> e.getSourceNodeId().equals(nodeId) || e.getTargetNodeId().equals(nodeId));
        if (Objects.equals(entryNodeId, nodeId)) {
            entryNodeId = nodes.keySet().stream().findFirst().orElse(null);
        }
        updateEntryFlags();
    }

    public void addEdge(VisualEdge edge) {
        if (edge != null) {
            edges.add(edge);
            recalculateCycles();
        }
    }

    public void removeEdge(VisualEdge edge) {
        edges.remove(edge);
        recalculateCycles();
    }

    private void updateEntryFlags() {
        for (VisualNode node : nodes.values()) {
            node.setEntryNode(Objects.equals(node.getId(), entryNodeId));
        }
    }

    public static DialogueGraphLayout fromDialogueGraph(DialogueGraph graph) {
        DialogueGraphLayout layout = new DialogueGraphLayout();
        if (graph == null) return layout;

        layout.entryNodeId = graph.getEntryNodeId();
        layout.titleKey = graph.getTitleKey() != null ? graph.getTitleKey() : "";
        layout.availability = graph.getAvailability() != null
                ? new ArrayList<>(graph.getAvailability()) : new ArrayList<>();

        // Hierarchical autolayout BFS to determine column/row levels
        Map<String, Integer> levels = new HashMap<>();
        Map<Integer, Integer> levelCounts = new HashMap<>();

        if (graph.getEntryNodeId() != null && graph.getNode(graph.getEntryNodeId()).isPresent()) {
            Queue<String> queue = new ArrayDeque<>();
            queue.add(graph.getEntryNodeId());
            levels.put(graph.getEntryNodeId(), 0);

            while (!queue.isEmpty()) {
                String currentId = queue.poll();
                int currentLevel = levels.get(currentId);

                graph.getNode(currentId).ifPresent(node -> {
                    if (node.getOptions() == null) {
                        return; // null options are legal YAML (terminal node)
                    }
                    for (DialogueEdge edge : node.getOptions()) {
                        String target = edge.getTargetNodeId();
                        if (target != null && !levels.containsKey(target)) {
                            levels.put(target, currentLevel + 1);
                            queue.add(target);
                        }
                    }
                });
            }
        }

        // Place all nodes
        for (DialogueNode domainNode : graph.getNodes().values()) {
            int level = levels.getOrDefault(domainNode.getId(), 0);
            int row = levelCounts.getOrDefault(level, 0);
            levelCounts.put(level, row + 1);

            double x = 50 + (level * 240);
            double y = 50 + (row * 130);

            VisualNode vNode = new VisualNode(domainNode.getId(), domainNode.getText(), x, y);
            // VULN-44: preserve node sound
            vNode.setSound(domainNode.getSound() != null ? domainNode.getSound() : "");
            vNode.setSpeaker(domainNode.getSpeaker() != null ? domainNode.getSpeaker() : "");
            vNode.setTextKey(domainNode.getTextKey());
            layout.addNode(vNode);

            if (domainNode.getOptions() == null) {
                continue;
            }
            for (DialogueEdge domainEdge : domainNode.getOptions()) {
                VisualEdge vEdge = new VisualEdge(domainNode.getId(), domainEdge.getTargetNodeId(), domainEdge.getText());
                // VULN-44: preserve all edge semantic fields
                vEdge.setOnceOnly(domainEdge.isOnceOnly());
                vEdge.setTextKey(domainEdge.getTextKey());
                vEdge.setConditions(domainEdge.getConditions() != null
                        ? new java.util.ArrayList<>(domainEdge.getConditions())
                        : new java.util.ArrayList<>());
                vEdge.setActions(domainEdge.getActions() != null
                        ? new java.util.ArrayList<>(domainEdge.getActions())
                        : new java.util.ArrayList<>());
                layout.addEdge(vEdge);
            }
        }

        layout.recalculateCycles();
        return layout;
    }

    public DialogueGraph toDialogueGraph(NamespacedId graphId, String title) {
        DialogueGraph graph = new DialogueGraph(graphId, title, entryNodeId);
        graph.setTitleKey(titleKey);
        graph.setAvailability(new ArrayList<>(availability));

        for (VisualNode vNode : nodes.values()) {
            DialogueNode domainNode = new DialogueNode(vNode.getId(), vNode.getText());
            // VULN-44: restore node sound
            domainNode.setSound(vNode.getSound() != null ? vNode.getSound() : "");
            domainNode.setSpeaker(vNode.getSpeaker() != null ? vNode.getSpeaker() : "");
            domainNode.setTextKey(vNode.getTextKey());
            graph.addNode(domainNode);
        }

        for (VisualEdge vEdge : edges) {
            graph.getNode(vEdge.getSourceNodeId()).ifPresent(sourceNode -> {
                DialogueEdge edge = new DialogueEdge(vEdge.getText(), vEdge.getTargetNodeId());
                // VULN-44: restore all edge semantic fields
                edge.setOnceOnly(vEdge.isOnceOnly());
                edge.setTextKey(vEdge.getTextKey());
                edge.setConditions(vEdge.getConditions() != null
                        ? new java.util.ArrayList<>(vEdge.getConditions())
                        : new java.util.ArrayList<>());
                edge.setActions(vEdge.getActions() != null
                        ? new java.util.ArrayList<>(vEdge.getActions())
                        : new java.util.ArrayList<>());
                sourceNode.addOption(edge);
            });
        }

        return graph;
    }

    /**
     * DFS Cycle Detection that detects intentional narrative loops.
     * Marks edges that participate in back-cycles as isCyclic = true.
     */
    public void recalculateCycles() {
        for (VisualEdge edge : edges) {
            edge.setCyclic(false);
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String startNode : nodes.keySet()) {
            if (!visited.contains(startNode)) {
                dfsCycle(startNode, visited, recursionStack);
            }
        }
    }

    private void dfsCycle(String current, Set<String> visited, Set<String> stack) {
        visited.add(current);
        stack.add(current);

        for (VisualEdge edge : edges) {
            if (edge.getSourceNodeId().equals(current)) {
                String target = edge.getTargetNodeId();
                if (stack.contains(target)) {
                    edge.setCyclic(true);
                } else if (!visited.contains(target)) {
                    dfsCycle(target, visited, stack);
                }
            }
        }

        stack.remove(current);
    }

    public boolean hasCycles() {
        return edges.stream().anyMatch(VisualEdge::isCyclic);
    }

    // Coordinate transformation helpers
    public static double screenToCanvasX(double screenX, double panX, double zoom) {
        return (screenX - panX) / zoom;
    }

    public static double screenToCanvasY(double screenY, double panY, double zoom) {
        return (screenY - panY) / zoom;
    }

    public static double canvasToScreenX(double canvasX, double panX, double zoom) {
        return (canvasX * zoom) + panX;
    }

    public static double canvasToScreenY(double canvasY, double panY, double zoom) {
        return (canvasY * zoom) + panY;
    }
}