package com.storynpcs.domain.rule;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import com.storynpcs.domain.rule.condition.CompositeCondition;
import com.storynpcs.domain.rule.condition.FactionStandingCondition;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.domain.rule.condition.StrikeCountCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleConditionTest {

    private RuleContext context;

    @BeforeEach
    void setUp() {
        context = new RuleContext(NamespacedId.of("test:npc"), "TestNPC", 100.0, 100.0);
        context.setActorUuid(UUID.randomUUID());
        context.setPlayerActor(true);
        context.setStrikesAgainstNpc(1);
    }

    @Test
    void testStrikeCountCondition() {
        var eq = new StrikeCountCondition(StrikeCountCondition.Operator.EQUAL, 1);
        assertThat(eq.evaluate(context)).isTrue();

        var lte = new StrikeCountCondition(StrikeCountCondition.Operator.LESS_THAN_OR_EQUAL, 1);
        assertThat(lte.evaluate(context)).isTrue();

        var gt = new StrikeCountCondition(StrikeCountCondition.Operator.GREATER_THAN, 1);
        assertThat(gt.evaluate(context)).isFalse();

        context.setStrikesAgainstNpc(2);
        assertThat(eq.evaluate(context)).isFalse();
        assertThat(lte.evaluate(context)).isFalse();
        assertThat(gt.evaluate(context)).isTrue();
    }

    @Test
    void testHealthPercentCondition() {
        // At 100/100 health -> 1.0 fraction
        var lte20 = new HealthPercentCondition(HealthPercentCondition.Operator.LESS_THAN_OR_EQUAL, 0.20);
        assertThat(lte20.evaluate(context)).isFalse();

        var gt50 = new HealthPercentCondition(HealthPercentCondition.Operator.GREATER_THAN, 0.50);
        assertThat(gt50.evaluate(context)).isTrue();

        // Drop health to 15%
        context.setNpcHealth(15.0);
        assertThat(lte20.evaluate(context)).isTrue();
        assertThat(gt50.evaluate(context)).isFalse();
    }

    @Test
    void testActorIsPlayerCondition() {
        var playerRequired = new ActorIsPlayerCondition(true);
        assertThat(playerRequired.evaluate(context)).isTrue();

        context.setPlayerActor(false);
        assertThat(playerRequired.evaluate(context)).isFalse();

        var playerForbidden = new ActorIsPlayerCondition(false);
        assertThat(playerForbidden.evaluate(context)).isTrue();
    }

    @Test
    void testFactionStandingCondition() {
        var condition = new FactionStandingCondition(
                NamespacedId.of("test:guards"),
                FactionStandingCondition.Standing.HOSTILE
        );

        // Without standing metadata, defaults to true
        assertThat(condition.evaluate(context)).isTrue();

        // With friendly standing metadata
        context.setMetadata("Standing", FactionStandingCondition.Standing.FRIENDLY);
        assertThat(condition.evaluate(context)).isFalse();

        // With hostile standing metadata
        context.setMetadata("Standing", FactionStandingCondition.Standing.HOSTILE);
        assertThat(condition.evaluate(context)).isTrue();
    }

    @Test
    void testCompositeCondition() {
        var cond1 = new StrikeCountCondition(StrikeCountCondition.Operator.EQUAL, 1);
        var cond2 = new ActorIsPlayerCondition(true);

        var and = new CompositeCondition(CompositeCondition.Mode.AND, List.of(cond1, cond2));
        assertThat(and.evaluate(context)).isTrue();

        context.setPlayerActor(false);
        assertThat(and.evaluate(context)).isFalse();

        var or = new CompositeCondition(CompositeCondition.Mode.OR, List.of(cond1, cond2));
        assertThat(or.evaluate(context)).isTrue();

        var not = new CompositeCondition(CompositeCondition.Mode.NOT, List.of(cond2));
        assertThat(not.evaluate(context)).isTrue();
    }
}
