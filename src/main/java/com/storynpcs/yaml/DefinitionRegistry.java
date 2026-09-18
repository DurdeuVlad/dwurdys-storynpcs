package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;

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

    public void registerNpc(NpcDefinition npc) {
        rwLock.writeLock().lock();
        try {
            npcs.put(npc.getId(), npc);
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
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerDialogue(DialogueGraph dialogue) {
        rwLock.writeLock().lock();
        try {
            dialogues.put(dialogue.getId(), dialogue);
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
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerFaction(Faction faction) {
        rwLock.writeLock().lock();
        try {
            factions.put(faction.getId(), faction);
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
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void registerQuest(Quest quest) {
        rwLock.writeLock().lock();
        try {
            quests.put(quest.getId(), quest);
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
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
