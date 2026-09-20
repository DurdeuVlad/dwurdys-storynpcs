package com.storynpcs.editor;

import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.rule.BehaviorRule;
import com.storynpcs.domain.rule.TriggerType;
import com.storynpcs.domain.rule.action.AddThreatAction;
import com.storynpcs.domain.rule.action.AdjustFactionAction;
import com.storynpcs.domain.rule.action.ChangeStanceAction;
import com.storynpcs.domain.rule.action.SendMessageAction;
import com.storynpcs.domain.rule.action.ShoutAlertAction;
import com.storynpcs.domain.rule.action.YieldCombatAction;
import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import com.storynpcs.domain.rule.condition.FactionStandingCondition;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.domain.rule.condition.RuleCondition;
import com.storynpcs.domain.rule.condition.StrikeCountCondition;
import com.storynpcs.domain.npc.TacticalStance;

import java.util.List;

/**
 * Pure client-side state for {@link com.storynpcs.client.gui.NpcRulesScreen}
 * (issue #26). Mirrors the /storynpcs npc rule add grammar exactly — same
 * trigger/condition/action sets, same arg shapes — and produces real domain
 * objects so saving routes through the existing ServerboundNpcSavePayload →
 * saveNpc path with no duplicated business logic.
 */
public final class NpcRulesScreenModel {

    /** Picker values — keep in lockstep with StoryNpcsCommands npcRuleCommands(). */
    public static final String[] TRIGGERS = {
            "on_damaged", "on_witness_assault", "on_interact",
            "on_health_percent_drop", "on_target_lost", "on_tick", "on_yield"};
    public static final String[] CONDITIONS = {
            "always", "actor_is_player", "faction_standing", "health_percent", "strike_count"};
    public static final String[] ACTIONS = {
            "send_message", "add_threat", "shout_alert", "yield_combat", "change_stance", "adjust_faction"};
    public static final String[] OPERATORS = {"le", "gt"};
    public static final String[] STANDINGS = {"hostile", "neutral", "friendly"};
    public static final String[] STANCES = {
            "passive", "retaliate_only", "defensive", "guard", "aggressive"};

    private final NpcDefinition npc;

    private boolean addMode;
    private int triggerIdx;
    private int condIdx;
    private int actionIdx;
    private int condOpIdx;
    private int standingIdx;
    private int stanceIdx = 3; // GUARD

    // Free-text args (validated on build)
    private String condFaction = "";
    private String condThreshold = "";
    private String actText = "";
    private String actAmount = "";
    private String actRadius = "";
    private String actMessage = "";
    private String actFraction = "";
    private String actDialogue = "";
    private String actFaction = "";
    private String actDelta = "";

    private String statusMessage = "";
    private boolean statusError;

    public NpcRulesScreenModel(NpcDefinition npc) {
        this.npc = npc;
    }

    public NpcDefinition getNpc() { return npc; }
    public List<BehaviorRule> getRules() { return npc.getRules(); }
    public boolean isAddMode() { return addMode; }

    public int getTriggerIdx() { return triggerIdx; }
    public int getCondIdx() { return condIdx; }
    public int getActionIdx() { return actionIdx; }
    public int getCondOpIdx() { return condOpIdx; }
    public int getStandingIdx() { return standingIdx; }
    public int getStanceIdx() { return stanceIdx; }

    public void cycleTrigger(int dir) { triggerIdx = Math.floorMod(triggerIdx + dir, TRIGGERS.length); }
    public void cycleCondition(int dir) { condIdx = Math.floorMod(condIdx + dir, CONDITIONS.length); }
    public void cycleAction(int dir) { actionIdx = Math.floorMod(actionIdx + dir, ACTIONS.length); }
    public void cycleCondOp(int dir) { condOpIdx = Math.floorMod(condOpIdx + dir, OPERATORS.length); }
    public void cycleStanding(int dir) { standingIdx = Math.floorMod(standingIdx + dir, STANDINGS.length); }
    public void cycleStance(int dir) { stanceIdx = Math.floorMod(stanceIdx + dir, STANCES.length); }

    public void setCondFaction(String v) { condFaction = v; }
    public void setCondThreshold(String v) { condThreshold = v; }
    public void setActText(String v) { actText = v; }
    public void setActAmount(String v) { actAmount = v; }
    public void setActRadius(String v) { actRadius = v; }
    public void setActMessage(String v) { actMessage = v; }
    public void setActFraction(String v) { actFraction = v; }
    public void setActDialogue(String v) { actDialogue = v; }
    public void setActFaction(String v) { actFaction = v; }
    public void setActDelta(String v) { actDelta = v; }

    public String getStatusMessage() { return statusMessage; }
    public boolean isStatusError() { return statusError; }
    public void setStatus(String message, boolean isError) {
        statusMessage = message != null ? message : "";
        statusError = isError;
    }

    /** Which arg fields the current condition type needs. */
    public boolean condNeedsFaction() { return "faction_standing".equals(CONDITIONS[condIdx]); }
    public boolean condNeedsStanding() { return "faction_standing".equals(CONDITIONS[condIdx]); }
    public boolean condNeedsThreshold() {
        String c = CONDITIONS[condIdx];
        return "health_percent".equals(c) || "strike_count".equals(c);
    }

    public boolean actNeedsText() { return "send_message".equals(ACTIONS[actionIdx]); }
    public boolean actNeedsAmount() { return "add_threat".equals(ACTIONS[actionIdx]); }
    public boolean actNeedsRadiusMessage() { return "shout_alert".equals(ACTIONS[actionIdx]); }
    public boolean actNeedsFractionDialogue() { return "yield_combat".equals(ACTIONS[actionIdx]); }
    public boolean actNeedsStance() { return "change_stance".equals(ACTIONS[actionIdx]); }
    public boolean actNeedsFactionDelta() { return "adjust_faction".equals(ACTIONS[actionIdx]); }

    public void beginAdd() {
        addMode = true;
        setStatus("", false);
    }

    public void cancelAdd() {
        addMode = false;
        setStatus("", false);
    }

    /**
     * Builds the rule from picker state and appends it to the NPC's rule list.
     * Returns null on success, or an error string to show inline.
     */
    public String commitAdd() {
        BehaviorRule rule;
        try {
            rule = buildRule();
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), true);
            return e.getMessage();
        }
        npc.getRules().add(rule);
        addMode = false;
        return null;
    }

    /** Removes the 0-based rule index. Returns an error string or null. */
    public String removeRule(int index) {
        List<BehaviorRule> rules = npc.getRules();
        if (index < 0 || index >= rules.size()) {
            String err = "Rule index " + (index + 1) + " out of range.";
            setStatus(err, true);
            return err;
        }
        rules.remove(index);
        return null;
    }

    public String describe(BehaviorRule rule) {
        return rule.getTrigger() + " if "
                + RuleSummaries.describeConditions(rule) + " → "
                + RuleSummaries.describeActions(rule);
    }

    private BehaviorRule buildRule() {
        TriggerType trigger;
        try {
            trigger = TriggerType.valueOf(TRIGGERS[triggerIdx].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown trigger '" + TRIGGERS[triggerIdx] + "'.");
        }

        BehaviorRule rule = new BehaviorRule(nextRuleId(), trigger);
        RuleCondition condition = buildCondition();
        if (condition != null) {
            rule.addCondition(condition);
        }
        rule.addAction(buildAction());
        return rule;
    }

    private RuleCondition buildCondition() {
        switch (CONDITIONS[condIdx]) {
            case "always":
                return null;
            case "actor_is_player":
                return new ActorIsPlayerCondition(true);
            case "faction_standing": {
                NamespacedId faction = parseId(condFaction, "condition faction");
                FactionStandingCondition.Standing standing =
                        FactionStandingCondition.Standing.valueOf(STANDINGS[standingIdx].toUpperCase());
                return new FactionStandingCondition(faction, standing);
            }
            case "health_percent":
                return new HealthPercentCondition(
                        "gt".equals(OPERATORS[condOpIdx])
                                ? HealthPercentCondition.Operator.GREATER_THAN
                                : HealthPercentCondition.Operator.LESS_THAN_OR_EQUAL,
                        parseDouble(condThreshold, "health threshold", 0.0, 1.0));
            case "strike_count":
                return new StrikeCountCondition(
                        "gt".equals(OPERATORS[condOpIdx])
                                ? StrikeCountCondition.Operator.GREATER_THAN
                                : StrikeCountCondition.Operator.LESS_THAN_OR_EQUAL,
                        parseInt(condThreshold, "strike count", 0, 1000000));
            default:
                throw new IllegalArgumentException("Unknown condition '" + CONDITIONS[condIdx] + "'.");
        }
    }

    private com.storynpcs.domain.rule.action.RuleAction buildAction() {
        switch (ACTIONS[actionIdx]) {
            case "send_message":
                if (actText.isBlank()) throw new IllegalArgumentException("send_message needs message text.");
                return new SendMessageAction(actText);
            case "add_threat":
                return new AddThreatAction(actAmount.isBlank()
                        ? 100.0 : parseDouble(actAmount, "threat amount", 0.0, 1_000_000.0));
            case "shout_alert":
                if (actMessage.isBlank()) throw new IllegalArgumentException("shout_alert needs a message.");
                return new ShoutAlertAction(
                        actRadius.isBlank() ? 16.0 : parseDouble(actRadius, "radius", 1.0, 64.0),
                        actMessage);
            case "yield_combat":
                return new YieldCombatAction(
                        actFraction.isBlank() ? 0.50 : parseDouble(actFraction, "heal fraction", 0.0, 1.0),
                        actDialogue.isBlank() ? "I yield! Well fought." : actDialogue);
            case "change_stance":
                return new ChangeStanceAction(TacticalStance.valueOf(STANCES[stanceIdx].toUpperCase()));
            case "adjust_faction": {
                NamespacedId faction = parseId(actFaction, "action faction");
                return new AdjustFactionAction(faction, parseInt(actDelta, "faction delta", -100000, 100000));
            }
            default:
                throw new IllegalArgumentException("Unknown action '" + ACTIONS[actionIdx] + "'.");
        }
    }

    private static NamespacedId parseId(String raw, String label) {
        try {
            return NamespacedId.of(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Bad " + label + " id '" + raw + "' — expected namespace:path (e.g. storynpcs:town_guard).");
        }
    }

    private static double parseDouble(String raw, String label, double min, double max) {
        try {
            double v = Double.parseDouble(raw.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid number for " + label + ".");
        }
    }

    private static int parseInt(String raw, String label, int min, int max) {
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid whole number for " + label + ".");
        }
    }

    private String nextRuleId() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var r : npc.getRules()) {
            if (r.getId() != null) ids.add(r.getId());
        }
        int n = 1;
        while (ids.contains("rule_" + n)) n++;
        return "rule_" + n;
    }
}
