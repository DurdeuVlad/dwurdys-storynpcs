package com.storynpcs.domain.faction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;

/**
 * Plans faction deletion: either every reference is repaired (NPCs re-pointed
 * to a fallback faction, matrix entries removed) or the deletion fails with
 * diagnostics naming each blocker. Never silently orphans references.
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
     * Enumerate references to {@code factionId} across NPC definitions and the
     * relationship matrices of other factions.
     */
    public List<Reference> findReferences(NamespacedId factionId,
                                          List<NpcDefinition> npcs,
                                          List<Faction> factions) {
        List<Reference> refs = new ArrayList<>();
        String target = factionId.toString();
        for (NpcDefinition npc : npcs) {
            if (npc.getFactionId() != null && npc.getFactionId().toString().equals(target)) {
                refs.add(new Reference("npc-faction",
                        npc.getId() == null ? "?" : npc.getId().toString(), "primary faction"));
            }
        }
        for (Faction f : factions) {
            if (f.getId() != null && !f.getId().equals(factionId)
                    && f.getRelationships().containsKey(target)) {
                refs.add(new Reference("matrix-entry", f.getId().toString(),
                        "relationship " + f.getRelationships().get(target)));
            }
        }
        return List.copyOf(refs);
    }

    /**
     * Build a deletion plan. Viable when either no references exist or a
     * fallback faction is supplied for the NPC references (matrix entries are
     * always removable). Without a fallback, NPC references block deletion.
     */
    public DeletionPlan plan(NamespacedId factionId,
                             List<NpcDefinition> npcs,
                             List<Faction> factions,
                             NamespacedId fallbackFactionId) {
        List<Reference> refs = findReferences(factionId, npcs, factions);
        List<String> diagnostics = new ArrayList<>();
        long npcRefs = refs.stream().filter(r -> r.kind().equals("npc-faction")).count();
        if (npcRefs > 0 && fallbackFactionId == null) {
            String blockers = refs.stream()
                    .filter(r -> r.kind().equals("npc-faction"))
                    .map(Reference::holderId).sorted().toList().toString();
            diagnostics.add(npcRefs + " NPC definition(s) reference faction '" + factionId
                    + "' " + blockers + " — provide a fallback faction or re-point them first");
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

    /** Execute a viable plan: strip matrix entries and re-point NPC factions. Returns applied repairs. */
    public List<Reference> apply(DeletionPlan plan, List<NpcDefinition> npcs, List<Faction> factions) {
        if (!plan.viable()) {
            throw new IllegalStateException("cannot apply non-viable deletion plan: " + plan.diagnostics());
        }
        String target = plan.factionId().toString();
        List<Reference> repaired = new ArrayList<>();
        for (Faction f : factions) {
            if (f.getId() != null && f.getRelationships().containsKey(target)) {
                f.removeRelationship(plan.factionId());
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
