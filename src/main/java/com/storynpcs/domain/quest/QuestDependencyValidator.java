package com.storynpcs.domain.quest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;

/**
 * Validates quest prerequisite graphs: every prerequisite must resolve to a
 * known quest, and the prerequisite relation must be acyclic — a cycle would
 * make every member permanently incomplete.
 */
public final class QuestDependencyValidator {

    public ValidationResult validate(List<Quest> quests) {
        ValidationResult result = new ValidationResult();
        Map<String, Quest> byId = new HashMap<>();
        for (Quest q : quests) {
            if (q.getId() != null) {
                byId.put(q.getId().toString(), q);
            }
        }

        // Missing prerequisite targets name both sides.
        for (Quest q : quests) {
            String qid = q.getId() == null ? "?" : q.getId().toString();
            for (NamespacedId prereq : q.getPrerequisites() == null ? List.<NamespacedId>of() : q.getPrerequisites()) {
                if (prereq == null || !byId.containsKey(prereq.toString())) {
                    result.addError("QUEST_MISSING_PREREQUISITE",
                            "quest '" + qid + "' requires missing quest '" + prereq + "'");
                }
            }
        }

        // Cycle detection — report each quest on a cycle.
        for (String cycleId : questsOnCycles(byId)) {
            result.addError("QUEST_DEPENDENCY_CYCLE",
                    "quest '" + cycleId + "' participates in a prerequisite cycle");
        }
        return result;
    }

    private static Set<String> questsOnCycles(Map<String, Quest> byId) {
        Set<String> cycleNodes = new HashSet<>();
        Set<String> white = new HashSet<>(byId.keySet());
        Set<String> grey = new HashSet<>();
        for (String start : new HashSet<>(white)) {
            if (!white.contains(start)) {
                continue;
            }
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                String current = stack.peek();
                if (white.remove(current)) {
                    grey.add(current);
                }
                boolean pushed = false;
                Quest q = byId.get(current);
                if (q != null && q.getPrerequisites() != null) {
                    for (NamespacedId prereq : q.getPrerequisites()) {
                        String next = prereq == null ? null : prereq.toString();
                        if (next == null || !byId.containsKey(next)) {
                            continue;
                        }
                        if (grey.contains(next)) {
                            cycleNodes.add(next);
                            cycleNodes.add(current);
                        } else if (white.contains(next)) {
                            stack.push(next);
                            pushed = true;
                        }
                    }
                }
                if (!pushed) {
                    stack.pop();
                    grey.remove(current);
                }
            }
        }
        return cycleNodes;
    }

    /** Deterministic topological order (prerequisites first), or empty when a cycle exists. */
    public List<NamespacedId> completionOrder(List<Quest> quests) {
        Map<String, Quest> byId = new HashMap<>();
        for (Quest q : quests) {
            if (q.getId() != null) {
                byId.put(q.getId().toString(), q);
            }
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Quest q : byId.values()) {
            indegree.putIfAbsent(q.getId().toString(), 0);
            for (NamespacedId prereq : q.getPrerequisites() == null ? List.<NamespacedId>of() : q.getPrerequisites()) {
                String p = prereq == null ? null : prereq.toString();
                if (p != null && byId.containsKey(p)) {
                    indegree.merge(q.getId().toString(), 1, Integer::sum);
                    dependents.computeIfAbsent(p, k -> new ArrayList<>()).add(q.getId().toString());
                }
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey).sorted()
                .forEach(ready::add);
        List<NamespacedId> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(NamespacedId.of(id));
            List<String> deps = dependents.getOrDefault(id, List.of());
            deps.stream().sorted().forEach(d -> {
                if (indegree.merge(d, -1, Integer::sum) == 0) {
                    ready.add(d);
                }
            });
        }
        return order.size() == byId.size() ? List.copyOf(order) : List.of();
    }
}
