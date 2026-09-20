package com.storynpcs.editor;

import com.storynpcs.domain.rule.BehaviorRule;
import com.storynpcs.domain.rule.action.AddThreatAction;
import com.storynpcs.domain.rule.action.AdjustFactionAction;
import com.storynpcs.domain.rule.action.ChangeStanceAction;
import com.storynpcs.domain.rule.action.RuleAction;
import com.storynpcs.domain.rule.action.SendMessageAction;
import com.storynpcs.domain.rule.action.ShoutAlertAction;
import com.storynpcs.domain.rule.action.YieldCombatAction;
import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import com.storynpcs.domain.rule.condition.FactionStandingCondition;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.domain.rule.condition.RuleCondition;
import com.storynpcs.domain.rule.condition.StrikeCountCondition;

import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable summaries of behavior rules, shared by {@code npc rule list}
 * command output and the NPC editor Rules view so both surfaces describe a rule
 * identically (issue #26 parity requirement).
 */
public final class RuleSummaries {

    private RuleSummaries() {}

    public static String describeConditions(BehaviorRule rule) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            return "always";
        }
        List<String> parts = new ArrayList<>();
        for (RuleCondition c : rule.getConditions()) {
            parts.add(describeCondition(c));
        }
        return String.join(" & ", parts);
    }

    public static String describeCondition(RuleCondition c) {
        if (c instanceof ActorIsPlayerCondition) {
            return "actor_is_player";
        }
        if (c instanceof FactionStandingCondition f) {
            return "faction_standing(" + f.getFactionId() + " " + f.getExpectedStanding() + ")";
        }
        if (c instanceof HealthPercentCondition h) {
            return "health_percent("
                    + (h.getOperator() == HealthPercentCondition.Operator.GREATER_THAN ? "gt" : "le")
                    + " " + h.getThreshold() + ")";
        }
        if (c instanceof StrikeCountCondition s) {
            return "strike_count("
                    + (s.getOperator() == StrikeCountCondition.Operator.GREATER_THAN ? "gt" : "le")
                    + " " + s.getThreshold() + ")";
        }
        return String.valueOf(c);
    }

    public static String describeActions(BehaviorRule rule) {
        if (rule.getActions() == null || rule.getActions().isEmpty()) {
            return "(no action)";
        }
        List<String> parts = new ArrayList<>();
        for (RuleAction a : rule.getActions()) {
            parts.add(describeAction(a));
        }
        return String.join(" & ", parts);
    }

    public static String describeAction(RuleAction a) {
        if (a instanceof SendMessageAction s) {
            return "send_message(\"" + s.getMessage() + "\")";
        }
        if (a instanceof AddThreatAction t) {
            return "add_threat(" + t.getThreat() + ")";
        }
        if (a instanceof ShoutAlertAction s) {
            return "shout_alert(" + s.getRadius() + ",\"" + s.getAlertMessage() + "\")";
        }
        if (a instanceof YieldCombatAction y) {
            return "yield_combat(" + y.getResetHealthFraction() + ")";
        }
        if (a instanceof ChangeStanceAction c) {
            return "change_stance(" + c.getStance() + ")";
        }
        if (a instanceof AdjustFactionAction f) {
            return "adjust_faction(" + f.getFactionId() + " " + f.getDelta() + ")";
        }
        return String.valueOf(a);
    }
}
