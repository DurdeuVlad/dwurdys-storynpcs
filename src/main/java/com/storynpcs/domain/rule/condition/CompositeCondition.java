package com.storynpcs.domain.rule.condition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Logical combinator (AND, OR, NOT) for composing complex conditions.
 */
public class CompositeCondition implements RuleCondition {

    public enum Mode {
        AND,
        OR,
        NOT
    }

    @JsonProperty
    private Mode mode = Mode.AND;

    @JsonProperty
    private List<RuleCondition> conditions = new ArrayList<>();

    public CompositeCondition() {}

    public CompositeCondition(Mode mode, List<RuleCondition> conditions) {
        this.mode = mode != null ? mode : Mode.AND;
        this.conditions = conditions != null ? conditions : new ArrayList<>();
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public List<RuleCondition> getConditions() { return conditions; }
    public void setConditions(List<RuleCondition> conditions) { this.conditions = conditions; }

    @Override
    public boolean evaluate(RuleContext ctx) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        return switch (mode) {
            case AND -> conditions.stream().allMatch(c -> c.evaluate(ctx));
            case OR -> conditions.stream().anyMatch(c -> c.evaluate(ctx));
            case NOT -> conditions.isEmpty() || !conditions.get(0).evaluate(ctx);
        };
    }
}
