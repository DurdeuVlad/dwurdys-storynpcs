package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.transport.TransportLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe central registry for all loaded story definitions.
 */
public class DefinitionRegistry {
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<NamespacedId, NpcDefinition> npcs = new ConcurrentHashMap<>();
    private final Map<NamespacedId, DialogueGraph> dialogues = new ConcurrentHashMap<>();
    private final Map<NamespacedId, Faction> factions = new ConcurrentHashMap<>();
    private final Map<NamespacedId, Quest> quests = new ConcurrentHashMap<>();
    private final Map<NamespacedId, TransportLocation> transportLocations = new ConcurrentHashMap<>();
    private final com.storynpcs.creator.template.TemplateLibrary templates =
            new com.storynpcs.creator.template.TemplateLibrary();
    private final Map<NamespacedId, com.storynpcs.creator.recipe.CarpentryRecipe> recipes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<NamespacedId, com.storynpcs.creator.world.WorldToolDefinition> worldTools =
            new LinkedHashMap<>();
    private final Map<NamespacedId, com.storynpcs.creator.template.SpawnerRule> spawners =
            new ConcurrentHashMap<>();
    /**
     * Monotonic registry revision — bumped on every register/remove. Authoring
     * patch plans capture it as their baseRevision so staleness is detectable.
     */
    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong();

    public long revision() {
        return revision.get();
    }

    public void registerNpc(NpcDefinition npc) {
        rwLock.writeLock().lock();
        try {
            npcs.put(npc.getId(), npc);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<NpcDefinition> getNpc(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(npcs.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<NpcDefinition> getAllNpcs() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(npcs.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void removeNpc(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            npcs.remove(id);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerDialogue(DialogueGraph dialogue) {
        rwLock.writeLock().lock();
        try {
            dialogues.put(dialogue.getId(), dialogue);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<DialogueGraph> getDialogue(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(dialogues.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<DialogueGraph> getAllDialogues() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(dialogues.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void removeDialogue(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            dialogues.remove(id);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerFaction(Faction faction) {
        rwLock.writeLock().lock();
        try {
            factions.put(faction.getId(), faction);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<Faction> getFaction(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(factions.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<Faction> getAllFactions() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(factions.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void removeFaction(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            factions.remove(id);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerTransportLocation(TransportLocation location) {
        rwLock.writeLock().lock();
        try {
            transportLocations.put(location.getId(), location);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<TransportLocation> getTransportLocation(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(transportLocations.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<TransportLocation> getAllTransportLocations() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(transportLocations.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void removeTransportLocation(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            transportLocations.remove(id);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerQuest(Quest quest) {
        rwLock.writeLock().lock();
        try {
            quests.put(quest.getId(), quest);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<Quest> getQuest(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(quests.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<Quest> getAllQuests() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(quests.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void removeQuest(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            quests.remove(id);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerTemplate(com.storynpcs.creator.template.NpcTemplate template) {
        rwLock.writeLock().lock();
        try {
            templates.put(template);
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<com.storynpcs.creator.template.NpcTemplate> getTemplate(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return templates.get(id);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<com.storynpcs.creator.template.NpcTemplate> getAllTemplates() {
        rwLock.readLock().lock();
        try {
            return templates.all();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Register a spawner as dependent on a template — surfaced on template delete. */
    public void registerTemplateSpawnerDependent(NamespacedId templateId, NamespacedId spawnerId) {
        rwLock.writeLock().lock();
        try {
            templates.registerSpawner(templateId, spawnerId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public java.util.List<NamespacedId> templateSpawnerDependents(NamespacedId templateId) {
        rwLock.readLock().lock();
        try {
            return templates.dependentSpawners(templateId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Register a spawner rule (P8-1). The rule's {@code templateId} is recorded
     * as a dependent on that template — a template delete reports its spawners.
     * A rule retargeted to a different template drops its stale dependency.
     */
    public void registerSpawnerRule(com.storynpcs.creator.template.SpawnerRule rule) {
        rwLock.writeLock().lock();
        try {
            var previous = spawners.put(rule.getId(), rule);
            if (previous != null && !previous.getTemplateId().equals(rule.getTemplateId())) {
                templates.unregisterSpawner(previous.getTemplateId(), rule.getId());
            }
            templates.registerSpawner(rule.getTemplateId(), rule.getId());
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<com.storynpcs.creator.template.SpawnerRule> getSpawnerRule(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(spawners.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public java.util.List<com.storynpcs.creator.template.SpawnerRule> getAllSpawnerRules() {
        rwLock.readLock().lock();
        try {
            return spawners.values().stream()
                    .sorted(Comparator.comparing(r -> r.getId().toString()))
                    .toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Removes a spawner rule and its template dependency record. */
    public boolean removeSpawnerRule(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            var removed = spawners.remove(id);
            if (removed == null) {
                return false;
            }
            templates.unregisterSpawner(removed.getTemplateId(), id);
            revision.incrementAndGet();
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Register a world-tool definition (P8-3). */
    public void registerWorldTool(com.storynpcs.creator.world.WorldToolDefinition tool) {
        if (tool != null && tool.getId() != null) {
            rwLock.writeLock().lock();
            try {
                var previous = worldTools.put(tool.getId(), tool);
                if (previous != tool) {
                    revision.incrementAndGet();
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }

    public Optional<com.storynpcs.creator.world.WorldToolDefinition> getWorldTool(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(worldTools.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public java.util.List<com.storynpcs.creator.world.WorldToolDefinition> getAllWorldTools() {
        rwLock.readLock().lock();
        try {
            return worldTools.values().stream()
                    .sorted(java.util.Comparator.comparing(t -> t.getId().toString()))
                    .toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean removeWorldTool(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            if (worldTools.remove(id) != null) {
                revision.incrementAndGet();
                return true;
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Register a carpentry recipe (P8-4). Same-id replace bumps the revision. */
    public void registerRecipe(com.storynpcs.creator.recipe.CarpentryRecipe recipe) {
        if (recipe != null && recipe.getId() != null) {
            rwLock.writeLock().lock();
            try {
                if (recipes.put(recipe.getId(), recipe) != recipe) {
                    revision.incrementAndGet();
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }

    public Optional<com.storynpcs.creator.recipe.CarpentryRecipe> getRecipe(NamespacedId id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(recipes.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public java.util.List<com.storynpcs.creator.recipe.CarpentryRecipe> getAllRecipes() {
        rwLock.readLock().lock();
        try {
            return recipes.values().stream()
                    .sorted(java.util.Comparator.comparing(r -> r.getId().toString()))
                    .toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Recipes in a group, sorted deterministically by id. */
    public java.util.List<com.storynpcs.creator.recipe.CarpentryRecipe> getRecipesInGroup(NamespacedId groupId) {
        rwLock.readLock().lock();
        try {
            return recipes.values().stream()
                    .filter(r -> groupId.equals(r.getGroupId()))
                    .sorted(java.util.Comparator.comparing(r -> r.getId().toString()))
                    .toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean removeRecipe(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            if (recipes.remove(id) != null) {
                revision.incrementAndGet();
                return true;
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Deterministic template search — delegated to the library's matcher. */
    public java.util.List<NamespacedId> searchTemplates(String query) {
        rwLock.readLock().lock();
        try {
            return templates.search(query);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Delete a template. Returns the library's outcome — dependent spawners
     * are surfaced to the caller rather than silently orphaned.
     */
    public com.storynpcs.creator.template.TemplateLibrary.DeleteOutcome removeTemplate(NamespacedId id) {
        rwLock.writeLock().lock();
        try {
            var outcome = templates.delete(id);
            if (outcome.removed()) {
                revision.incrementAndGet();
            }
            return outcome;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void copyFrom(DefinitionRegistry other) {
        rwLock.writeLock().lock();
        other.rwLock.readLock().lock();
        try {
            npcs.clear();
            npcs.putAll(other.npcs);
            dialogues.clear();
            dialogues.putAll(other.dialogues);
            factions.clear();
            factions.putAll(other.factions);
            quests.clear();
            quests.putAll(other.quests);
            transportLocations.clear();
            transportLocations.putAll(other.transportLocations);
            templates.clear();
            for (var template : other.templates.all()) {
                templates.put(template);
            }
            spawners.clear();
            spawners.putAll(other.spawners);
            worldTools.clear();
            worldTools.putAll(other.worldTools);
            recipes.clear();
            recipes.putAll(other.recipes);
            for (var template : other.templates.all()) {
                for (var dependent : other.templates.dependentSpawners(template.getId())) {
                    templates.registerSpawner(template.getId(), dependent);
                }
            }
            revision.incrementAndGet();
        } finally {
            other.rwLock.readLock().unlock();
            rwLock.writeLock().unlock();
        }
    }

    public void clear() {
        rwLock.writeLock().lock();
        try {
            npcs.clear();
            dialogues.clear();
            factions.clear();
            quests.clear();
            transportLocations.clear();
            templates.clear();
            spawners.clear();
            worldTools.clear();
            recipes.clear();
            revision.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
