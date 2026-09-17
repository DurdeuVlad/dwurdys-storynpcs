package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Halts combat, yields/concedes defeat (e.g. Dojo sparring, duel tournament), and heals to a safe fraction.
 */
public class YieldCombatAction implements RuleAction {

    @JsonProperty
    private double resetHealthFraction = 0.50;

    @JsonProperty
    private String yieldDialogue = "I yield! Well fought.";

    public YieldCombatAction() {}

    public YieldCombatAction(double resetHealthFraction, String yieldDialogue) {
        this.resetHealthFraction = resetHealthFraction;
        this.yieldDialogue = yieldDialogue != null ? yieldDialogue : "";
    }

    public double getResetHealthFraction() { return resetHealthFraction; }
    public void setResetHealthFraction(double resetHealthFraction) { this.resetHealthFraction = resetHealthFraction; }

    public String getYieldDialogue() { return yieldDialogue; }
    public void setYieldDialogue(String yieldDialogue) { this.yieldDialogue = yieldDialogue; }

    @Override
    public boolean execute(RuleContext ctx) {
        if (ctx == null) return false;
        ctx.yieldCombat(resetHealthFraction, yieldDialogue);
        return true;
    }
}
