package com.storynpcs.yaml;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.Quest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe central registry for all loaded story definitions.
 */
public class DefinitionRegistry {
    private final Map<NamespacedId, NpcDefinition> npcs = new ConcurrentHashMap<>();
    private final Map<NamespacedId, DialogueGraph> dialogues = new ConcurrentHashMap<>();
    private final Map<NamespacedId, Faction> factions = new ConcurrentHashMap<>();
    private final Map<NamespacedId, Quest> quests = new ConcurrentHashMap<>();

    public void registerNpc(NpcDefinition npc) {
        npcs.put(npc.getId(), npc);
    }

    public Optional<NpcDefinition> getNpc(NamespacedId id) {
        return Optional.ofNullable(npcs.get(id));
    }

    public Collection<NpcDefinition> getAllNpcs() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    public void removeNpc(NamespacedId id) {
        npcs.remove(id);
    }

    public void registerDialogue(DialogueGraph dialogue) {
        dialogues.put(dialogue.getId(), dialogue);
    }

    public Optional<DialogueGraph> getDialogue(NamespacedId id) {
        return Optional.ofNullable(dialogues.get(id));
    }

    public Collection<DialogueGraph> getAllDialogues() {
        return Collections.unmodifiableCollection(dialogues.values());
    }

    public void registerFaction(Faction faction) {
        factions.put(faction.getId(), faction);
    }

    public Optional<Faction> getFaction(NamespacedId id) {
        return Optional.ofNullable(factions.get(id));
    }

    public Collection<Faction> getAllFactions() {
        return Collections.unmodifiableCollection(factions.values());
    }

    public void registerQuest(Quest quest) {
        quests.put(quest.getId(), quest);
    }

    public Optional<Quest> getQuest(NamespacedId id) {
        return Optional.ofNullable(quests.get(id));
    }

    public Collection<Quest> getAllQuests() {
        return Collections.unmodifiableCollection(quests.values());
    }

    public void clear() {
        npcs.clear();
        dialogues.clear();
        factions.clear();
        quests.clear();
    }
}
