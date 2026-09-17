package com.storynpcs.domain.rule.condition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.rule.RuleContext;

/**
 * Evaluates whether NPC health percentage meets a threshold (e.g. <= 0.15 for yielding).
 */
public class HealthPercentCondition implements RuleCondition {

    public enum Operator {
        LESS_THAN_OR_EQUAL,
        GREATER_THAN
    }

    @JsonProperty
    private Operator operator = Operator.LESS_THAN_OR_EQUAL;

    @JsonProperty
    private double threshold = 0.20; // 20% health

    public HealthPercentCondition() {}

    public HealthPercentCondition(Operator operator, double threshold) {
        this.operator = operator != null ? operator : Operator.LESS_THAN_OR_EQUAL;
        this.threshold = threshold;
    }

    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    @Override
    public boolean evaluate(RuleContext ctx) {
        if (ctx == null) return false;
        double fraction = ctx.getHealthFraction();
        return switch (operator) {
            case LESS_THAN_OR_EQUAL -> fraction <= threshold + 1e-6;
            case GREATER_THAN -> fraction > threshold;
        };
    }
}
