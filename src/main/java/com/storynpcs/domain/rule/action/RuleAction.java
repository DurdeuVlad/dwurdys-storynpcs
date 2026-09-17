package com.storynpcs.domain.rule.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Composable action building block executed when rule conditions are satisfied.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SendMessageAction.class, name = "SEND_MESSAGE"),
    @JsonSubTypes.Type(value = AddThreatAction.class, name = "ADD_THREAT"),
    @JsonSubTypes.Type(value = ShoutAlertAction.class, name = "SHOUT_ALERT"),
    @JsonSubTypes.Type(value = YieldCombatAction.class, name = "YIELD_COMBAT"),
    @JsonSubTypes.Type(value = ChangeStanceAction.class, name = "CHANGE_STANCE"),
    @JsonSubTypes.Type(value = AdjustFactionAction.class, name = "ADJUST_FACTION")
})
public interface RuleAction {
    boolean execute(RuleContext ctx);
}
