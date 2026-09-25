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

    @JsonProperty
    private List<com.storynpcs.domain.rule.BehaviorRule> rules = new ArrayList<>();

    @JsonProperty
    private com.storynpcs.domain.role.trader.TraderRole trader;

    @JsonProperty
    private com.storynpcs.domain.role.banker.BankerRole banker;

    @JsonProperty
    private com.storynpcs.domain.role.healer.HealerRole healer;

    @JsonProperty
    private com.storynpcs.domain.role.bard.BardRole bard;

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

    public List<com.storynpcs.domain.rule.BehaviorRule> getRules() { return rules; }
    public void setRules(List<com.storynpcs.domain.rule.BehaviorRule> rules) { this.rules = rules; }

    public com.storynpcs.domain.role.trader.TraderRole getTrader() { return trader; }
    public void setTrader(com.storynpcs.domain.role.trader.TraderRole trader) { this.trader = trader; }

    public com.storynpcs.domain.role.banker.BankerRole getBanker() { return banker; }
    public void setBanker(com.storynpcs.domain.role.banker.BankerRole banker) { this.banker = banker; }

    public com.storynpcs.domain.role.healer.HealerRole getHealer() { return healer; }
    public void setHealer(com.storynpcs.domain.role.healer.HealerRole healer) { this.healer = healer; }

    public com.storynpcs.domain.role.bard.BardRole getBard() { return bard; }
    public void setBard(com.storynpcs.domain.role.bard.BardRole bard) { this.bard = bard; }
}

