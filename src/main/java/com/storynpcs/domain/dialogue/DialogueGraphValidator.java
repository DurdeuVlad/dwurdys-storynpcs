package com.storynpcs.domain.dialogue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.storynpcs.domain.common.ValidationResult;

/**
 * Static diagnostics for dialogue graphs: entry existence, node reachability,
 * dangling edge targets, and cycle detection. Cycles are diagnosed per node —
 * they are legal in directed-graph dialogue but must be intentional and visible
 * to authors rather than silent.
 */
public final class DialogueGraphValidator {

    public ValidationResult validate(DialogueGraph graph) {
        ValidationResult result = new ValidationResult();
        if (graph == null) {
            result.addError("DIALOGUE_GRAPH_NULL", "dialogue graph is null");
            return result;
        }
        if (graph.getNodes() == null || graph.getNodes().isEmpty()) {
            result.addError("DIALOGUE_EMPTY_GRAPH", "dialogue graph " + graph.getId() + " has no nodes");
            return result;
        }

        String entryId = graph.getEntryNodeId();
        if (entryId == null || entryId.isBlank()) {
            result.addError("DIALOGUE_MISSING_ENTRY", "dialogue graph " + graph.getId() + " has no entry node id");
        } else if (!graph.getNodes().containsKey(entryId)) {
            result.addError("DIALOGUE_MISSING_ENTRY",
                    "dialogue graph " + graph.getId() + " entry node '" + entryId + "' does not exist");
        }

        // Dangling edge targets — every edge must name a node that exists.
        for (DialogueNode node : graph.getNodes().values()) {
            if (node.getOptions() == null) {
                continue;
            }
            for (DialogueEdge edge : node.getOptions()) {
                String target = edge.getTargetNodeId();
                if (target == null || target.isBlank() || !graph.getNodes().containsKey(target)) {
                    result.addError("DIALOGUE_DANGLING_EDGE",
                            "node '" + node.getId() + "' has option targeting missing node '" + target + "'");
                }
            }
        }

        // Reachability from entry — every authored node must be reachable.
        if (entryId != null && graph.getNodes().containsKey(entryId)) {
            Set<String> reachable = reachableFrom(graph, entryId);
            for (String nodeId : graph.getNodes().keySet()) {
                if (!reachable.contains(nodeId)) {
                    result.addError("DIALOGUE_UNREACHABLE_NODE",
                            "node '" + nodeId + "' is not reachable from entry '" + entryId + "'");
                }
            }
            // Cycle detection via DFS coloring — legal in directed graphs but
            // diagnosed so unintentional loops are visible to authors.
            for (String nodeId : nodesOnCycles(graph, entryId)) {
                result.addWarning("DIALOGUE_CYCLE",
                        "node '" + nodeId + "' participates in a cycle in graph " + graph.getId());
            }
        }
        return result;
    }

    private static Set<String> reachableFrom(DialogueGraph graph, String start) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (visited.add(id)) {
                DialogueNode node = graph.getNodes().get(id);
                if (node != null && node.getOptions() != null) {
                    for (DialogueEdge edge : node.getOptions()) {
                        if (edge.getTargetNodeId() != null && graph.getNodes().containsKey(edge.getTargetNodeId())) {
                            queue.add(edge.getTargetNodeId());
                        }
                    }
                }
            }
        }
        return visited;
    }

    /** Three-color DFS from entry; nodes that lie on a back-edge cycle are collected. */
    private static List<String> nodesOnCycles(DialogueGraph graph, String entryId) {
        Set<String> white = new HashSet<>(graph.getNodes().keySet());
        Set<String> grey = new HashSet<>();
        Set<String> cycleNodes = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(entryId);
        while (!stack.isEmpty()) {
            String current = stack.peek();
            if (white.remove(current)) {
                grey.add(current);
            }
            boolean pushedChild = false;
            DialogueNode node = graph.getNodes().get(current);
            if (node != null && node.getOptions() != null) {
                for (DialogueEdge edge : node.getOptions()) {
                    String next = edge.getTargetNodeId();
                    if (next == null || !graph.getNodes().containsKey(next)) {
                        continue;
                    }
                    if (grey.contains(next)) {
                        cycleNodes.add(next);
                        cycleNodes.add(current);
                    } else if (white.contains(next)) {
                        stack.push(next);
                        pushedChild = true;
                    }
                }
            }
            if (!pushedChild) {
                stack.pop();
                grey.remove(current);
            }
        }
        return new ArrayList<>(cycleNodes);
    }
}
