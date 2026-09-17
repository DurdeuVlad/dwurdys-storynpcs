package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueEdge;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueNode;

import java.util.*;

public class DialogueGraphLayout {

    private String entryNodeId;
    private final Map<String, VisualNode> nodes = new LinkedHashMap<>();
    private final List<VisualEdge> edges = new ArrayList<>();

    public DialogueGraphLayout() {}

    public String getEntryNodeId() { return entryNodeId; }
    public void setEntryNodeId(String entryNodeId) {
        this.entryNodeId = entryNodeId;
        updateEntryFlags();
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
            layout.addNode(vNode);

            for (DialogueEdge domainEdge : domainNode.getOptions()) {
                VisualEdge vEdge = new VisualEdge(domainNode.getId(), domainEdge.getTargetNodeId(), domainEdge.getText());
                layout.addEdge(vEdge);
            }
        }

        layout.recalculateCycles();
        return layout;
    }

    public DialogueGraph toDialogueGraph(NamespacedId graphId, String title) {
        DialogueGraph graph = new DialogueGraph(graphId, title, entryNodeId);

        for (VisualNode vNode : nodes.values()) {
            DialogueNode domainNode = new DialogueNode(vNode.getId(), vNode.getText());
            graph.addNode(domainNode);
        }

        for (VisualEdge vEdge : edges) {
            graph.getNode(vEdge.getSourceNodeId()).ifPresent(sourceNode -> {
                sourceNode.addOption(new DialogueEdge(vEdge.getText(), vEdge.getTargetNodeId()));
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