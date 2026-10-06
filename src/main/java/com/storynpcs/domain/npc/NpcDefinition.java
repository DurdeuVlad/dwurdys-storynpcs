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

    /** All authored marks; {@link #mark} remains the primary legacy mark. */
    @JsonProperty
    private List<NpcMark> marks = new ArrayList<>();

    @JsonProperty
    private NpcInventory inventory = new NpcInventory();

    @JsonProperty
    private List<com.storynpcs.domain.rule.BehaviorRule> rules = new ArrayList<>();

    private List<com.storynpcs.domain.ability.NpcAbility> abilities = new ArrayList<>();

    @JsonProperty
    private com.storynpcs.domain.role.trader.TraderRole trader;

    @JsonProperty
    private com.storynpcs.domain.role.banker.BankerRole banker;

    /** NPC-attached job schedule/config (P6-4); null when the NPC has no job. */
    @JsonProperty
    private com.storynpcs.domain.job.JobConfig job;

    /** Hireable-companion contract (P6-5): wages, stages, talents, unload policy. */
    @JsonProperty
    private com.storynpcs.domain.companion.CompanionProfile companion;

    @JsonProperty
    private com.storynpcs.domain.role.social.BardRole bard;

    @JsonProperty
    private com.storynpcs.domain.role.social.HealerRole healer;

    @JsonProperty
    private com.storynpcs.domain.role.social.PostmanRole postman;

    /** Transporter role: fast-travel destinations offered by this NPC (#72). */
    @JsonProperty
    private com.storynpcs.domain.role.transporter.TransporterRole transporter;

    /**
     * Scripts bound to this NPC (P9-2): entity hooks — init, tick, interact,
     * damaged, killed, target — dispatch only to these script ids. Referenced
     * scripts must resolve to registered {@code ScriptDefinition}s.
     */
    @JsonProperty
    private List<NamespacedId> scripts = new ArrayList<>();

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

    public List<NpcMark> getMarks() { return marks; }
    public void setMarks(List<NpcMark> marks) {
        this.marks = new ArrayList<>();
        if (marks == null) return;
        if (marks.size() > 8) throw new IllegalArgumentException("marks cannot exceed 8 entries");
        for (NpcMark m : marks) {
            if (m == null) throw new IllegalArgumentException("marks cannot contain null");
            this.marks.add(m);
        }
    }

    public NpcInventory getInventory() { return inventory; }
    public void setInventory(NpcInventory inventory) {
        if (inventory == null) throw new IllegalArgumentException("inventory cannot be null");
        this.inventory = inventory;
    }

    public List<NamespacedId> getScripts() { return scripts; }
    public void setScripts(List<NamespacedId> scripts) {
        this.scripts = new ArrayList<>();
        if (scripts == null) return;
        if (scripts.size() > 8) throw new IllegalArgumentException("scripts cannot exceed 8 entries");
        for (NamespacedId script : scripts) {
            if (script == null) throw new IllegalArgumentException("scripts cannot contain null");
            this.scripts.add(script);
        }
    }

    public List<com.storynpcs.domain.rule.BehaviorRule> getRules() { return rules; }
    public void setRules(List<com.storynpcs.domain.rule.BehaviorRule> rules) { this.rules = rules; }

    public List<com.storynpcs.domain.ability.NpcAbility> getAbilities() { return abilities; }
    public void setAbilities(List<com.storynpcs.domain.ability.NpcAbility> abilities) {
        this.abilities = abilities != null ? abilities : new ArrayList<>();
    }

    public com.storynpcs.domain.role.trader.TraderRole getTrader() { return trader; }
    public void setTrader(com.storynpcs.domain.role.trader.TraderRole trader) { this.trader = trader; }

    public com.storynpcs.domain.role.banker.BankerRole getBanker() { return banker; }
    public void setBanker(com.storynpcs.domain.role.banker.BankerRole banker) { this.banker = banker; }

    public com.storynpcs.domain.job.JobConfig getJob() { return job; }
    public void setJob(com.storynpcs.domain.job.JobConfig job) { this.job = job; }

    public com.storynpcs.domain.companion.CompanionProfile getCompanion() { return companion; }
    public void setCompanion(com.storynpcs.domain.companion.CompanionProfile companion) { this.companion = companion; }

    public com.storynpcs.domain.role.social.BardRole getBard() { return bard; }
    public void setBard(com.storynpcs.domain.role.social.BardRole bard) { this.bard = bard; }

    public com.storynpcs.domain.role.social.HealerRole getHealer() { return healer; }
    public void setHealer(com.storynpcs.domain.role.social.HealerRole healer) { this.healer = healer; }

    public com.storynpcs.domain.role.social.PostmanRole getPostman() { return postman; }
    public void setPostman(com.storynpcs.domain.role.social.PostmanRole postman) { this.postman = postman; }

    public com.storynpcs.domain.role.transporter.TransporterRole getTransporter() { return transporter; }
    public void setTransporter(com.storynpcs.domain.role.transporter.TransporterRole transporter) { this.transporter = transporter; }
}

