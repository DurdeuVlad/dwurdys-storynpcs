package com.storynpcs.domain.rule.condition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Checks whether the triggering actor is a player.
 */
public class ActorIsPlayerCondition implements RuleCondition {

    @JsonProperty
    private boolean required = true;

    public ActorIsPlayerCondition() {}

    public ActorIsPlayerCondition(boolean required) {
        this.required = required;
    }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    @Override
    public boolean evaluate(RuleContext ctx) {
        if (ctx == null) return false;
        return ctx.isPlayerActor() == required;
    }
}
