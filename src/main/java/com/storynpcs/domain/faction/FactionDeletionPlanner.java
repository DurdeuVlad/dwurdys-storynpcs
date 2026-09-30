package com.storynpcs.domain.faction;

import java.util.ArrayList;
import java.util.List;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;

/**
 * Plans faction deletion by repairing primary NPC bindings and relationship
 * matrices, while blocking on references that have no safe automatic remap.
 * Never silently orphans references.
 */
public final class FactionDeletionPlanner {

    public record Reference(String kind, String holderId, String detail) {}

    public record DeletionPlan(
            NamespacedId factionId,
            List<Reference> references,
            NamespacedId fallbackFactionId,
            boolean viable,
            List<String> diagnostics) {

        public DeletionPlan {
            references = references == null ? List.of() : List.copyOf(references);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public boolean hasReferences() {
            return !references.isEmpty();
        }
    }

    /**
     * Enumerate definition references to {@code factionId} that deletion must
     * either repair or block.
     */
    public List<Reference> findReferences(NamespacedId factionId,
                                          List<NpcDefinition> npcs,
                                          List<com.storynpcs.creator.template.NpcTemplate> templates,
                                          List<Faction> factions,
                                          List<com.storynpcs.domain.dialogue.DialogueGraph> dialogues,
                                          List<com.storynpcs.domain.quest.Quest> quests,
                                          List<com.storynpcs.domain.transport.TransportLocation> transports) {
        List<Reference> refs = new ArrayList<>();
        String target = factionId.toString();
        for (NpcDefinition npc : npcs) {
            String npcId = npc.getId() == null ? "?" : npc.getId().toString();
            addNpcReferences(refs, factionId, npc, false, npcId);
        }
        for (var template : templates) {
            if (template.getDefinition() == null) continue;
            String templateId = template.getId() == null ? "?" : template.getId().toString();
            addNpcReferences(refs, factionId, template.getDefinition(), true, templateId);
        }
        for (Faction f : factions) {
            if (f.getId() != null && !f.getId().equals(factionId)
                    && f.getRelationships().containsKey(factionId)) {
                refs.add(new Reference("matrix-entry", f.getId().toString(),
                        "relationship " + f.getRelationships().get(factionId)));
            }
        }
        for (var dialogue : dialogues) {
            if (dialogue.getNodes() == null) continue;
            for (var node : dialogue.getNodes().values()) {
                if (node == null || node.getOptions() == null) continue;
                for (var edge : node.getOptions()) {
                    if (edge == null) continue;
                    if (edge.getConditions() != null) {
                        for (var condition : edge.getConditions()) {
                            if (condition != null
                                    && (condition.getType() == com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_STANDING
                                    || condition.getType() == com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_POINTS)
                                    && referencesFaction(condition.getTarget(), factionId)) {
                                refs.add(new Reference("dialogue-faction", dialogue.getId().toString(),
                                        "node " + node.getId() + " condition"));
                            }
                        }
                    }
                    if (edge.getActions() != null) {
                        for (var action : edge.getActions()) {
                            if (action != null
                                    && action.getType() == com.storynpcs.domain.dialogue.DialogueAction.Type.ADJUST_FACTION
                                    && referencesFaction(action.getTarget(), factionId)) {
                                refs.add(new Reference("dialogue-faction", dialogue.getId().toString(),
                                        "node " + node.getId() + " action"));
                            }
                        }
                    }
                }
            }
        }
        for (var quest : quests) {
            if (quest.getRewards() == null) continue;
            for (var reward : quest.getRewards()) {
                if (reward != null && reward.getType() == com.storynpcs.domain.quest.QuestReward.Type.FACTION_POINTS
                        && referencesFaction(reward.getTarget(), factionId)) {
                    refs.add(new Reference("quest-faction", quest.getId().toString(), "faction-points reward"));
                }
            }
        }
        for (var transport : transports) {
            for (var condition : transport.getUnlockConditions()) {
                if (condition != null
                        && (condition.getType() == com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_STANDING
                        || condition.getType() == com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_POINTS)
                        && referencesFaction(condition.getTarget(), factionId)) {
                    refs.add(new Reference("transport-faction", transport.getId().toString(), "unlock condition"));
                }
            }
        }
        return List.copyOf(refs);
    }

    /**
     * Build a deletion plan. NPC bindings require a valid fallback, relationship
     * matrix entries are removable, and all other references block deletion.
     */
    public DeletionPlan plan(NamespacedId factionId,
                             List<NpcDefinition> npcs,
                             List<com.storynpcs.creator.template.NpcTemplate> templates,
                             List<Faction> factions,
                             List<com.storynpcs.domain.dialogue.DialogueGraph> dialogues,
                             List<com.storynpcs.domain.quest.Quest> quests,
                             List<com.storynpcs.domain.transport.TransportLocation> transports,
                             NamespacedId fallbackFactionId) {
        List<Reference> refs = findReferences(factionId, npcs, templates, factions, dialogues, quests, transports);
        List<String> diagnostics = new ArrayList<>();
        long npcRefs = refs.stream().filter(r -> r.kind().equals("npc-faction")).count();
        if (npcRefs > 0 && fallbackFactionId == null) {
            String blockers = refs.stream()
                    .filter(r -> r.kind().equals("npc-faction"))
                    .map(Reference::holderId).sorted().toList().toString();
            diagnostics.add(npcRefs + " NPC definition(s) reference faction '" + factionId
                    + "' " + blockers + " — provide a fallback faction or re-point them first");
        }
        List<Reference> nonRepairable = refs.stream()
                .filter(reference -> !reference.kind().equals("npc-faction")
                        && !reference.kind().equals("matrix-entry"))
                .toList();
        for (Reference reference : nonRepairable) {
            diagnostics.add("Cannot delete faction '" + factionId + "': " + reference.kind()
                    + " reference in '" + reference.holderId() + "' requires manual repair ("
                    + reference.detail() + ")");
        }
        if (fallbackFactionId != null && fallbackFactionId.equals(factionId)) {
            diagnostics.add("fallback faction cannot be the faction being deleted");
        }
        boolean fallbackExists = fallbackFactionId == null
                || factions.stream().anyMatch(f -> fallbackFactionId.equals(f.getId()));
        if (fallbackFactionId != null && !fallbackExists) {
            diagnostics.add("fallback faction '" + fallbackFactionId + "' does not exist");
        }
        boolean viable = diagnostics.isEmpty();
        return new DeletionPlan(factionId, refs,
                npcRefs > 0 ? fallbackFactionId : null, viable, List.copyOf(diagnostics));
    }

    private static void addNpcReferences(List<Reference> references, NamespacedId factionId,
                                         NpcDefinition npc, boolean embedded, String holderId) {
        String npcId = npc.getId() == null ? "?" : npc.getId().toString();
        String kindPrefix = embedded ? "template" : "npc";
        String detailPrefix = embedded ? "embedded NPC '" + npcId + "' " : "";
        if (factionId.equals(npc.getFactionId())) {
            references.add(new Reference(kindPrefix + "-faction", holderId,
                    detailPrefix + "primary faction"));
        }
        var ai = npc.getAi();
        if (ai != null && ai.getTargetFactionIds().contains(factionId)) {
            references.add(new Reference(kindPrefix + "-ai-target-faction", holderId,
                    detailPrefix + "AI target faction"));
        }
        var trader = npc.getTrader();
        if (trader != null) {
            for (int index = 0; index < trader.getListings().size(); index++) {
                var listing = trader.getListings().get(index);
                if (listing != null && factionId.equals(listing.getRequiredFaction())) {
                    references.add(new Reference(kindPrefix + "-trade-faction", holderId,
                            detailPrefix + "trader listing " + index));
                }
            }
        }
        if (npc.getRules() != null) {
            for (int ruleIndex = 0; ruleIndex < npc.getRules().size(); ruleIndex++) {
                var rule = npc.getRules().get(ruleIndex);
                if (rule == null) continue;
                if (rule.getConditions() != null) {
                    for (var condition : rule.getConditions()) {
                        if (referencesFactionCondition(condition, factionId)) {
                            references.add(new Reference(kindPrefix + "-rule-faction", holderId,
                                    detailPrefix + "rule " + ruleIndex + " condition"));
                        }
                    }
                }
                if (rule.getActions() != null) {
                    for (var action : rule.getActions()) {
                        if (action instanceof com.storynpcs.domain.rule.action.AdjustFactionAction adjust
                                && factionId.equals(adjust.getFactionId())) {
                            references.add(new Reference(kindPrefix + "-rule-faction", holderId,
                                    detailPrefix + "rule " + ruleIndex + " action"));
                        }
                    }
                }
            }
        }
    }

    private static boolean referencesFactionCondition(
            com.storynpcs.domain.rule.condition.RuleCondition condition, NamespacedId factionId) {
        return referencesFactionCondition(condition, factionId,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static boolean referencesFactionCondition(
            com.storynpcs.domain.rule.condition.RuleCondition condition,
            NamespacedId factionId,
            java.util.Set<com.storynpcs.domain.rule.condition.RuleCondition> visited) {
        if (condition == null || !visited.add(condition)) return false;
        if (condition instanceof com.storynpcs.domain.rule.condition.FactionStandingCondition standing) {
            return factionId.equals(standing.getFactionId());
        }
        if (condition instanceof com.storynpcs.domain.rule.condition.CompositeCondition composite
                && composite.getConditions() != null) {
            return composite.getConditions().stream()
                    .anyMatch(child -> referencesFactionCondition(child, factionId, visited));
        }
        return false;
    }

    private static boolean referencesFaction(String value, NamespacedId factionId) {
        if (value == null || value.isBlank()) return false;
        try {
            return factionId.equals(NamespacedId.of(value));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Execute a viable plan: strip matrix entries and re-point NPC factions. Returns applied repairs. */
    public List<Reference> apply(DeletionPlan plan, List<NpcDefinition> npcs, List<Faction> factions) {
        if (!plan.viable()) {
            throw new IllegalStateException("cannot apply non-viable deletion plan: " + plan.diagnostics());
        }
        String target = plan.factionId().toString();
        List<Reference> repaired = new ArrayList<>();
        for (Faction f : factions) {
            if (f.getId() != null && f.getRelationships().containsKey(plan.factionId())) {
                f.removeRelationshipTo(plan.factionId());
                repaired.add(new Reference("matrix-entry", f.getId().toString(), "removed"));
            }
        }
        if (plan.fallbackFactionId() != null) {
            for (NpcDefinition npc : npcs) {
                if (npc.getFactionId() != null && npc.getFactionId().toString().equals(target)) {
                    npc.setFactionId(plan.fallbackFactionId());
                    repaired.add(new Reference("npc-faction", npc.getId().toString(),
                            "re-pointed to " + plan.fallbackFactionId()));
                }
            }
        }
        return List.copyOf(repaired);
    }
}
