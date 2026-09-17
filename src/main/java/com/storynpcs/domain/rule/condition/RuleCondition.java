package com.storynpcs.domain.rule.condition;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Composable condition building block evaluated against a RuleContext.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StrikeCountCondition.class, name = "STRIKE_COUNT"),
    @JsonSubTypes.Type(value = HealthPercentCondition.class, name = "HEALTH_PERCENT"),
    @JsonSubTypes.Type(value = ActorIsPlayerCondition.class, name = "ACTOR_IS_PLAYER"),
    @JsonSubTypes.Type(value = FactionStandingCondition.class, name = "FACTION_STANDING"),
    @JsonSubTypes.Type(value = CompositeCondition.class, name = "COMPOSITE")
})
public interface RuleCondition {
    boolean evaluate(RuleContext ctx);
}
