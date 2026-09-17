package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Adjusts player/actor standing with a designated faction upon rule triggering.
 */
public class AdjustFactionAction implements RuleAction {

    @JsonProperty
    private NamespacedId factionId;

    @JsonProperty
    private int delta = 0;

    public AdjustFactionAction() {}

    public AdjustFactionAction(NamespacedId factionId, int delta) {
        this.factionId = factionId;
        this.delta = delta;
    }

    public NamespacedId getFactionId() { return factionId; }
    public void setFactionId(NamespacedId factionId) { this.factionId = factionId; }

    public int getDelta() { return delta; }
    public void setDelta(int delta) { this.delta = delta; }

    @Override
    public boolean execute(RuleContext ctx) {
        if (ctx == null || factionId == null) return false;
        ctx.adjustFaction(factionId, delta);
        return true;
    }
}
