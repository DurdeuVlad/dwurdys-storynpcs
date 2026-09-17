package com.storynpcs.domain.rule.condition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Evaluates the number of accidental/intentional strikes registered against the NPC.
 */
public class StrikeCountCondition implements RuleCondition {

    public enum Operator {
        EQUAL,
        LESS_THAN_OR_EQUAL,
        GREATER_THAN
    }

    @JsonProperty
    private Operator operator = Operator.LESS_THAN_OR_EQUAL;

    @JsonProperty
    private int threshold = 1;

    public StrikeCountCondition() {}

    public StrikeCountCondition(Operator operator, int threshold) {
        this.operator = operator != null ? operator : Operator.LESS_THAN_OR_EQUAL;
        this.threshold = threshold;
    }

    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }

    public int getThreshold() { return threshold; }
    public void setThreshold(int threshold) { this.threshold = threshold; }

    @Override
    public boolean evaluate(RuleContext ctx) {
        if (ctx == null) return false;
        int strikes = ctx.getStrikesAgainstNpc();
        return switch (operator) {
            case EQUAL -> strikes == threshold;
            case LESS_THAN_OR_EQUAL -> strikes <= threshold;
            case GREATER_THAN -> strikes > threshold;
        };
    }
}
