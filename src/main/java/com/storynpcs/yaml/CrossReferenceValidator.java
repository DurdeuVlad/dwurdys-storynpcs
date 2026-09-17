package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.*;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;

import java.util.HashSet;
import java.util.Set;

/**
 * Validates cross-references among all registered definitions:
 * - NPC dialogue and faction bindings.
 * - Dialogue internal node links, conditions, and actions.
 * - Quest prerequisites and cycle avoidance.
 */
public class CrossReferenceValidator {

    public static ValidationResult validate(DefinitionRegistry registry) {
        ValidationResult result = ValidationResult.valid();

        // 1. Validate NPC references
        for (NpcDefinition npc : registry.getAllNpcs()) {
            if (npc.getDialogueId() != null && registry.getDialogue(npc.getDialogueId()).isEmpty()) {
                result.addError("REF_NPC_DIALOGUE_MISSING",
                        String.format("NPC '%s' references unknown dialogue '%s'", npc.getId(), npc.getDialogueId()));
            }
            if (npc.getFactionId() != null && registry.getFaction(npc.getFactionId()).isEmpty()) {
                result.addError("REF_NPC_FACTION_MISSING",
                        String.format("NPC '%s' references unknown faction '%s'", npc.getId(), npc.getFactionId()));
            }
        }

        // 2. Validate Dialogue graphs
        for (DialogueGraph graph : registry.getAllDialogues()) {
            if (graph.getNodes().isEmpty()) {
                result.addError("GRAPH_EMPTY", String.format("Dialogue '%s' has no nodes", graph.getId()));
                continue;
            }
            if (graph.getEntryNodeId() == null || !graph.getNodes().containsKey(graph.getEntryNodeId())) {
                result.addError("GRAPH_ENTRY_MISSING",
                        String.format("Dialogue '%s' entry node '%s' does not exist in graph",
                                graph.getId(), graph.getEntryNodeId()));
            }

            for (DialogueNode node : graph.getNodes().values()) {
                if (node.getOptions() != null) {
                    for (DialogueEdge edge : node.getOptions()) {
                        String targetId = edge.getTargetNodeId();
                        if (targetId == null || !graph.getNodes().containsKey(targetId)) {
                            result.addError("GRAPH_DANGLING_EDGE",
                                    String.format("Dialogue '%s' node '%s' option points to non-existent node '%s'",
                                            graph.getId(), node.getId(), targetId));
                        }

                        // Validate conditions
                        if (edge.getConditions() != null) {
                            for (DialogueCondition cond : edge.getConditions()) {
                                validateCondition(graph.getId(), cond, registry, result);
                            }
                        }

                        // Validate actions
                        if (edge.getActions() != null) {
                            for (DialogueAction action : edge.getActions()) {
                                validateAction(graph.getId(), action, registry, result);
                            }
                        }
                    }
                }
            }
        }

        // 3. Validate Quest prerequisites & circular dependencies
        for (Quest quest : registry.getAllQuests()) {
            if (quest.getPrerequisites() != null) {
                for (NamespacedId prereqId : quest.getPrerequisites()) {
                    if (registry.getQuest(prereqId).isEmpty()) {
                        result.addError("REF_QUEST_PREREQ_MISSING",
                                String.format("Quest '%s' prerequisite '%s' not found", quest.getId(), prereqId));
                    }
                }
                // Check cycle
                Set<NamespacedId> visited = new HashSet<>();
                if (hasCircularPrerequisite(quest.getId(), quest.getId(), registry, visited)) {
                    result.addError("CYCLE_QUEST_PREREQUISITE",
                            String.format("Quest '%s' has circular prerequisite dependency", quest.getId()));
                }
            }
        }

        return result;
    }

    private static void validateCondition(NamespacedId graphId, DialogueCondition cond,
                                          DefinitionRegistry registry, ValidationResult result) {
        if (cond.getType() == DialogueCondition.Type.QUEST_STATUS) {
            try {
                NamespacedId qid = NamespacedId.of(cond.getTarget());
                if (registry.getQuest(qid).isEmpty()) {
                    result.addWarning("GRAPH_COND_QUEST_NOT_FOUND",
                            String.format("Dialogue '%s' condition targets unknown quest '%s'", graphId, qid));
                }
            } catch (Exception e) {
                result.addError("GRAPH_COND_INVALID_ID",
                        String.format("Dialogue '%s' condition has invalid target quest id '%s'", graphId, cond.getTarget()));
            }
        } else if (cond.getType() == DialogueCondition.Type.FACTION_STANDING || cond.getType() == DialogueCondition.Type.FACTION_POINTS) {
            try {
                NamespacedId fid = NamespacedId.of(cond.getTarget());
                if (registry.getFaction(fid).isEmpty()) {
                    result.addWarning("GRAPH_COND_FACTION_NOT_FOUND",
                            String.format("Dialogue '%s' condition targets unknown faction '%s'", graphId, fid));
                }
            } catch (Exception e) {
                result.addError("GRAPH_COND_INVALID_ID",
                        String.format("Dialogue '%s' condition has invalid target faction id '%s'", graphId, cond.getTarget()));
            }
        }
    }

    private static void validateAction(NamespacedId graphId, DialogueAction action,
                                       DefinitionRegistry registry, ValidationResult result) {
        if (action.getType() == DialogueAction.Type.START_QUEST ||
            action.getType() == DialogueAction.Type.ADVANCE_QUEST ||
            action.getType() == DialogueAction.Type.COMPLETE_QUEST) {
            try {
                NamespacedId qid = NamespacedId.of(action.getTarget());
                if (registry.getQuest(qid).isEmpty()) {
                    result.addWarning("GRAPH_ACTION_QUEST_NOT_FOUND",
                            String.format("Dialogue '%s' action targets unknown quest '%s'", graphId, qid));
                }
            } catch (Exception e) {
                result.addError("GRAPH_ACTION_INVALID_ID",
                        String.format("Dialogue '%s' action has invalid target quest id '%s'", graphId, action.getTarget()));
            }
        } else if (action.getType() == DialogueAction.Type.ADJUST_FACTION) {
            try {
                NamespacedId fid = NamespacedId.of(action.getTarget());
                if (registry.getFaction(fid).isEmpty()) {
                    result.addWarning("GRAPH_ACTION_FACTION_NOT_FOUND",
                            String.format("Dialogue '%s' action targets unknown faction '%s'", graphId, fid));
                }
            } catch (Exception e) {
                result.addError("GRAPH_ACTION_INVALID_ID",
                        String.format("Dialogue '%s' action has invalid target faction id '%s'", graphId, action.getTarget()));
            }
        }
    }

    private static boolean hasCircularPrerequisite(NamespacedId rootId, NamespacedId currentId,
                                                  DefinitionRegistry registry, Set<NamespacedId> visited) {
        if (!visited.add(currentId)) {
            return currentId.equals(rootId);
        }
        var questOpt = registry.getQuest(currentId);
        if (questOpt.isEmpty() || questOpt.get().getPrerequisites() == null) {
            return false;
        }
        for (NamespacedId prereq : questOpt.get().getPrerequisites()) {
            if (prereq.equals(rootId)) return true;
            if (hasCircularPrerequisite(rootId, prereq, registry, visited)) {
                return true;
            }
        }
        return false;
    }
}
