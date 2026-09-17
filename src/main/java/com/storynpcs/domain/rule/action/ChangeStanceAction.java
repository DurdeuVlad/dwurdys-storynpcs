package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Dynamically shifts the NPC's tactical combat stance (e.g. from GUARD to AGGRESSIVE or PASSIVE).
 */
public class ChangeStanceAction implements RuleAction {

    @JsonProperty
    private TacticalStance stance = TacticalStance.GUARD;

    public ChangeStanceAction() {}

    public ChangeStanceAction(TacticalStance stance) {
        this.stance = stance != null ? stance : TacticalStance.GUARD;
    }

    public TacticalStance getStance() { return stance; }
    public void setStance(TacticalStance stance) { this.stance = stance; }

    @Override
    public boolean execute(RuleContext ctx) {
        if (ctx == null || stance == null) return false;
        ctx.changeStance(stance);
        return true;
    }
}
