package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

import java.util.UUID;

/**
 * Adds threat against the offending actor or specified entity.
 */
public class AddThreatAction implements RuleAction {

    @JsonProperty
    private double threat = 100.0;

    @JsonProperty
    private boolean targetActor = true;

    public AddThreatAction() {}

    public AddThreatAction(double threat) {
        this(threat, true);
    }

    public AddThreatAction(double threat, boolean targetActor) {
        this.threat = threat;
        this.targetActor = targetActor;
    }

    public double getThreat() { return threat; }
    public void setThreat(double threat) { this.threat = threat; }

    public boolean isTargetActor() { return targetActor; }
    public void setTargetActor(boolean targetActor) { this.targetActor = targetActor; }

    @Override
    public boolean execute(RuleContext ctx) {
        if (ctx == null) return false;

        if (targetActor) {
            return ctx.getActorUuid().map(uuid -> {
                ctx.addThreat(uuid, threat);
                return true;
            }).orElse(false);
        }
        return false;
    }
}
