package com.storynpcs.domain.rule;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.domain.rule.action.AddThreatAction;
import com.storynpcs.domain.rule.action.ChangeStanceAction;
import com.storynpcs.domain.rule.action.SendMessageAction;
import com.storynpcs.domain.rule.action.ShoutAlertAction;
import com.storynpcs.domain.rule.action.YieldCombatAction;
import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.domain.rule.condition.StrikeCountCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private RuleEngine engine;
    private RuleContext context;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        engine = new RuleEngine();
        playerUuid = UUID.randomUUID();
        context = new RuleContext(NamespacedId.of("test:master"), "Swordmaster Kenshi", 100.0, 100.0);
        context.setActorUuid(playerUuid);
        context.setPlayerActor(true);
        context.setStrikesAgainstNpc(0);
    }

    @Test
    void testTriggerTypeFiltering() {
        BehaviorRule interactRule = new BehaviorRule("interact_rule", TriggerType.ON_INTERACT);
        interactRule.addAction(new SendMessageAction("Hello traveler!"));

        var summary = engine.evaluate(List.of(interactRule), TriggerType.ON_DAMAGED, context);
        assertThat(summary.getRulesEvaluated()).isEqualTo(0);
        assertThat(summary.getRulesTriggered()).isEqualTo(0);
        assertThat(context.getRecordedMessages()).isEmpty();

        var interactSummary = engine.evaluate(List.of(interactRule), TriggerType.ON_INTERACT, context);
        assertThat(interactSummary.getRulesEvaluated()).isEqualTo(1);
        assertThat(interactSummary.getRulesTriggered()).isEqualTo(1);
        assertThat(context.getRecordedMessages()).containsExactly("Hello traveler!");
    }

    @Test
    void testDojoSparringYieldScenario() {
        // Build the Dojo Master rules
        BehaviorRule yieldRule = new BehaviorRule("sparring_yield", TriggerType.ON_DAMAGED)
                .addCondition(new HealthPercentCondition(HealthPercentCondition.Operator.LESS_THAN_OR_EQUAL, 0.20))
                .addAction(new YieldCombatAction(0.50, "Impressive technique! You pass the trial."))
                .addAction(new SendMessageAction("Duel completed successfully.", true, SendMessageAction.Target.ACTOR));
        yieldRule.setStopPropagation(true);

        BehaviorRule normalHitRule = new BehaviorRule("normal_sparring_hit", TriggerType.ON_DAMAGED)
                .addAction(new SendMessageAction("Keep your guard up!", false, SendMessageAction.Target.ACTOR));

        List<BehaviorRule> rules = List.of(yieldRule, normalHitRule);

        // Case A: NPC takes hit but is still above 20% health (e.g. 75/100)
        context.setNpcHealth(75.0);
        var hitSummary = engine.evaluate(rules, TriggerType.ON_DAMAGED, context);

        assertThat(hitSummary.getRulesTriggered()).isEqualTo(1);
        assertThat(context.isCombatYielded()).isFalse();
        assertThat(context.getRecordedMessages()).containsExactly("Keep your guard up!");

        // Case B: NPC health drops below 20% (e.g. 15/100)
        context = new RuleContext(NamespacedId.of("test:master"), "Swordmaster Kenshi", 15.0, 100.0);
        context.setActorUuid(playerUuid);
        context.setPlayerActor(true);

        var yieldSummary = engine.evaluate(rules, TriggerType.ON_DAMAGED, context);

        assertThat(yieldSummary.getRulesTriggered()).isEqualTo(1);
        assertThat(yieldSummary.isStopped()).isTrue(); // Stop propagation prevented normalHitRule
        assertThat(context.isCombatYielded()).isTrue();
        assertThat(context.getYieldDialogue()).isEqualTo("Impressive technique! You pass the trial.");
        assertThat(context.getNpcHealth()).isEqualTo(50.0); // Reset/healed to 50%
        assertThat(context.getRecordedMessages()).containsExactly("Duel completed successfully.");
    }

    @Test
    void testTwoStrikeGuardWarningScenario() {
        BehaviorRule warningRule = new BehaviorRule("first_strike_warn", TriggerType.ON_DAMAGED)
                .addCondition(new StrikeCountCondition(StrikeCountCondition.Operator.EQUAL, 1))
                .addCondition(new ActorIsPlayerCondition(true))
                .addAction(new SendMessageAction("§e[Guard]§6 Watch your weapon, citizen! (1/2 warnings)", true, SendMessageAction.Target.ACTOR));

        BehaviorRule retaliateRule = new BehaviorRule("second_strike_retaliate", TriggerType.ON_DAMAGED)
                .addCondition(new StrikeCountCondition(StrikeCountCondition.Operator.GREATER_THAN, 1))
                .addAction(new AddThreatAction(200.0, true))
                .addAction(new ShoutAlertAction(20.0, "Assault on city watch! Subdue the criminal!"))
                .addAction(new ChangeStanceAction(TacticalStance.AGGRESSIVE));

        List<BehaviorRule> rules = List.of(warningRule, retaliateRule);

        // Strike 1
        context.setStrikesAgainstNpc(1);
        var summary1 = engine.evaluate(rules, TriggerType.ON_DAMAGED, context);
        assertThat(summary1.getRulesTriggered()).isEqualTo(1);
        assertThat(context.getRecordedMessages()).hasSize(1);
        assertThat(context.getRecordedThreats()).isEmpty();

        // Strike 2
        RuleContext context2 = new RuleContext(NamespacedId.of("test:guard"), "City Guard", 100.0, 100.0);
        context2.setActorUuid(playerUuid);
        context2.setPlayerActor(true);
        context2.setStrikesAgainstNpc(2);

        var summary2 = engine.evaluate(rules, TriggerType.ON_DAMAGED, context2);
        assertThat(summary2.getRulesTriggered()).isEqualTo(1);
        assertThat(context2.getRecordedThreats().get(playerUuid)).isEqualTo(200.0);
        assertThat(context2.getRecordedAlerts()).containsExactly("Assault on city watch! Subdue the criminal!");
        assertThat(context2.getNewStance()).isEqualTo(TacticalStance.AGGRESSIVE);
    }
}
