package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.ArrayList;
import java.util.List;

/**
 * Authorable YAML-first NPC definition.
 */
public class NpcDefinition {
    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty
    private NpcDisplay display = new NpcDisplay();

    @JsonProperty
    private NpcStats stats = new NpcStats();

    @JsonProperty
    private NpcAi ai = new NpcAi();

    @JsonProperty
    private NamespacedId dialogueId;

    @JsonProperty
    private NamespacedId factionId;

    @JsonProperty
    private NpcMark mark;

    @JsonProperty
    private List<String> inventory = new ArrayList<>();

    public NpcDefinition() {}

    public NpcDefinition(NamespacedId id, String name) {
        this.id = id;
        this.display.setName(name);
    }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public NpcDisplay getDisplay() { return display; }
    public void setDisplay(NpcDisplay display) { this.display = display; }

    public NpcStats getStats() { return stats; }
    public void setStats(NpcStats stats) { this.stats = stats; }

    public NpcAi getAi() { return ai; }
    public void setAi(NpcAi ai) { this.ai = ai; }

    public NamespacedId getDialogueId() { return dialogueId; }
    public void setDialogueId(NamespacedId dialogueId) { this.dialogueId = dialogueId; }

    public NamespacedId getFactionId() { return factionId; }
    public void setFactionId(NamespacedId factionId) { this.factionId = factionId; }

    public NpcMark getMark() { return mark; }
    public void setMark(NpcMark mark) { this.mark = mark; }

    public List<String> getInventory() { return inventory; }
    public void setInventory(List<String> inventory) { this.inventory = inventory; }
}
