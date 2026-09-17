package com.storynpcs.domain.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.action.RuleAction;
import com.storynpcs.domain.rule.condition.RuleCondition;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level behavioral building block connecting a trigger, condition pipeline, and actions.
 */
public class BehaviorRule {

    @JsonProperty
    private String id;

    @JsonProperty
    private TriggerType trigger = TriggerType.ON_DAMAGED;

    @JsonProperty
    private List<RuleCondition> conditions = new ArrayList<>();

    @JsonProperty
    private List<RuleAction> actions = new ArrayList<>();

    @JsonProperty
    private boolean stopPropagation = false;

    public BehaviorRule() {}

    public BehaviorRule(String id, TriggerType trigger) {
        this.id = id;
        this.trigger = trigger != null ? trigger : TriggerType.ON_DAMAGED;
    }

    public BehaviorRule(String id, TriggerType trigger, List<RuleCondition> conditions, List<RuleAction> actions) {
        this.id = id;
        this.trigger = trigger != null ? trigger : TriggerType.ON_DAMAGED;
        this.conditions = conditions != null ? conditions : new ArrayList<>();
        this.actions = actions != null ? actions : new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TriggerType getTrigger() { return trigger; }
    public void setTrigger(TriggerType trigger) { this.trigger = trigger; }

    public List<RuleCondition> getConditions() { return conditions; }
    public void setConditions(List<RuleCondition> conditions) { this.conditions = conditions; }

    public List<RuleAction> getActions() { return actions; }
    public void setActions(List<RuleAction> actions) { this.actions = actions; }

    public boolean isStopPropagation() { return stopPropagation; }
    public void setStopPropagation(boolean stopPropagation) { this.stopPropagation = stopPropagation; }

    public BehaviorRule addCondition(RuleCondition condition) {
        if (condition != null) {
            this.conditions.add(condition);
        }
        return this;
    }

    public BehaviorRule addAction(RuleAction action) {
        if (action != null) {
            this.actions.add(action);
        }
        return this;
    }
}
