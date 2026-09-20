package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.rule.BehaviorRule;
import com.storynpcs.domain.rule.TriggerType;
import com.storynpcs.domain.rule.action.AdjustFactionAction;
import com.storynpcs.domain.rule.action.SendMessageAction;
import com.storynpcs.domain.rule.action.YieldCombatAction;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.domain.rule.condition.StrikeCountCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NpcRulesScreenModel} — the client-side builder behind the
 * NPC Rules screen (issue #26).
 */
class NpcRulesScreenModelTest {

    private NpcRulesScreenModel newModel() {
        return new NpcRulesScreenModel(new NpcDefinition(NamespacedId.of("storynpcs:test_npc"), "Test NPC"));
    }

    private static void cycleTo(NpcRulesScreenModel m, String target, String[] options,
                                java.util.function.IntConsumer cycler) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(target)) return;
            cycler.accept(1);
        }
    }

    @Test
    @DisplayName("always + send_message builds a rule with no conditions and the message action")
    void testAlwaysSendMessage() {
        var m = newModel();
        m.beginAdd();
        // defaults: trigger on_damaged, condition always, action send_message
        m.setActText("Halt, citizen!");
        assertNull(m.commitAdd());
        assertEquals(1, m.getRules().size());
        BehaviorRule r = m.getRules().get(0);
        assertEquals(TriggerType.ON_DAMAGED, r.getTrigger());
        assertTrue(r.getConditions().isEmpty());
        assertEquals("Halt, citizen!", ((SendMessageAction) r.getActions().get(0)).getMessage());
        assertEquals("rule_1", r.getId());
    }

    @Test
    @DisplayName("health_percent le + yield_combat mirrors the #19 command grammar")
    void testHealthPercentYield() {
        var m = newModel();
        m.beginAdd();
        cycleTo(m, "health_percent", NpcRulesScreenModel.CONDITIONS, m::cycleCondition);
        m.setCondThreshold("0.5");
        cycleTo(m, "yield_combat", NpcRulesScreenModel.ACTIONS, m::cycleAction);
        assertNull(m.commitAdd());
        BehaviorRule r = m.getRules().get(0);
        var cond = (HealthPercentCondition) r.getConditions().get(0);
        assertEquals(HealthPercentCondition.Operator.LESS_THAN_OR_EQUAL, cond.getOperator());
        assertEquals(0.5, cond.getThreshold());
        assertEquals(0.5, ((YieldCombatAction) r.getActions().get(0)).getResetHealthFraction());
    }

    @Test
    @DisplayName("strike_count gt operator picker maps to GREATER_THAN")
    void testStrikeCountGt() {
        var m = newModel();
        m.beginAdd();
        cycleTo(m, "strike_count", NpcRulesScreenModel.CONDITIONS, m::cycleCondition);
        m.cycleCondOp(1); // le -> gt
        m.setCondThreshold("2");
        m.setActText("Enough!");
        assertNull(m.commitAdd());
        var cond = (StrikeCountCondition) m.getRules().get(0).getConditions().get(0);
        assertEquals(StrikeCountCondition.Operator.GREATER_THAN, cond.getOperator());
        assertEquals(2, cond.getThreshold());
    }

    @Test
    @DisplayName("adjust_faction validates faction id and delta inline")
    void testAdjustFactionValidation() {
        var m = newModel();
        m.beginAdd();
        cycleTo(m, "adjust_faction", NpcRulesScreenModel.ACTIONS, m::cycleAction);
        m.setActFaction("not an id!!");
        m.setActDelta("-50");
        String err = m.commitAdd();
        assertNotNull(err);
        assertTrue(m.isStatusError());
        assertTrue(m.isAddMode(), "form stays open on validation error");
        assertTrue(m.getRules().isEmpty(), "invalid rule must not be appended");
    }

    @Test
    @DisplayName("non-numeric threshold produces an inline error, not an exception")
    void testBadNumeric() {
        var m = newModel();
        m.beginAdd();
        cycleTo(m, "health_percent", NpcRulesScreenModel.CONDITIONS, m::cycleCondition);
        m.setCondThreshold("abc");
        assertNotNull(m.commitAdd());
        assertTrue(m.getRules().isEmpty());
    }

    @Test
    @DisplayName("rule ids stay unique across add/remove churn")
    void testRuleIdUniqueness() {
        var m = newModel();
        m.beginAdd();
        m.setActText("one");
        assertNull(m.commitAdd());
        m.beginAdd();
        m.setActText("two");
        assertNull(m.commitAdd());
        assertNull(m.removeRule(0));
        m.beginAdd();
        m.setActText("three");
        assertNull(m.commitAdd());
        var ids = m.getRules().stream().map(BehaviorRule::getId).toList();
        assertEquals(2, ids.size());
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(), "duplicate rule id");
    }

    @Test
    @DisplayName("removeRule bounds-checks like the command path")
    void testRemoveBounds() {
        var m = newModel();
        assertNotNull(m.removeRule(0));
        assertNotNull(m.removeRule(5));
    }

    @Test
    @DisplayName("describe() output matches npc rule list formatting")
    void testDescribeFormat() {
        var m = newModel();
        m.beginAdd();
        cycleTo(m, "health_percent", NpcRulesScreenModel.CONDITIONS, m::cycleCondition);
        m.setCondThreshold("0.5");
        cycleTo(m, "adjust_faction", NpcRulesScreenModel.ACTIONS, m::cycleAction);
        m.setActFaction("storynpcs:town_guard");
        m.setActDelta("-50");
        assertNull(m.commitAdd());
        String desc = m.describe(m.getRules().get(0));
        assertEquals("ON_DAMAGED if health_percent(le 0.5) → adjust_faction(storynpcs:town_guard -50)", desc);
    }
}
