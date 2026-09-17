package com.storynpcs.domain.rule;

import com.storynpcs.domain.rule.action.RuleAction;
import com.storynpcs.domain.rule.condition.RuleCondition;

import java.util.List;
import java.util.Objects;

/**
 * Execution engine that evaluates behavioral rules against an incoming trigger and context.
 */
public class RuleEngine {

    public static class RuleExecutionSummary {
        private final int rulesEvaluated;
        private final int rulesTriggered;
        private final int actionsExecuted;
        private final boolean stopped;

        public RuleExecutionSummary(int rulesEvaluated, int rulesTriggered, int actionsExecuted, boolean stopped) {
            this.rulesEvaluated = rulesEvaluated;
            this.rulesTriggered = rulesTriggered;
            this.actionsExecuted = actionsExecuted;
            this.stopped = stopped;
        }

        public int getRulesEvaluated() { return rulesEvaluated; }
        public int getRulesTriggered() { return rulesTriggered; }
        public int getActionsExecuted() { return actionsExecuted; }
        public boolean isStopped() { return stopped; }
        public boolean hasTriggered() { return rulesTriggered > 0; }
    }

    /**
     * Evaluates all rules matching the trigger against the provided context.
     *
     * @param rules the list of candidate behavior rules
     * @param trigger the triggering event type
     * @param context the runtime context
     * @return execution summary with metrics and stop status
     */
    public RuleExecutionSummary evaluate(List<BehaviorRule> rules, TriggerType trigger, RuleContext context) {
        if (rules == null || rules.isEmpty() || trigger == null || context == null) {
            return new RuleExecutionSummary(0, 0, 0, false);
        }

        int evaluated = 0;
        int triggered = 0;
        int actionsExecuted = 0;
        boolean stopped = false;

        for (BehaviorRule rule : rules) {
            if (rule == null || rule.getTrigger() != trigger) {
                continue;
            }

            evaluated++;

            // Evaluate all conditions
            boolean conditionsMet = true;
            if (rule.getConditions() != null) {
                for (RuleCondition condition : rule.getConditions()) {
                    if (condition != null && !condition.evaluate(context)) {
                        conditionsMet = false;
                        break;
                    }
                }
            }

            if (conditionsMet) {
                triggered++;
                if (rule.getActions() != null) {
                    for (RuleAction action : rule.getActions()) {
                        if (action != null && action.execute(context)) {
                            actionsExecuted++;
                        }
                    }
                }

                if (rule.isStopPropagation()) {
                    stopped = true;
                    break;
                }
            }
        }

        return new RuleExecutionSummary(evaluated, triggered, actionsExecuted, stopped);
    }
}
