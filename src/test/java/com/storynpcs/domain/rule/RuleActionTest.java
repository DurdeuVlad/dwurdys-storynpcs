package com.storynpcs.domain.rule;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.domain.rule.action.AddThreatAction;
import com.storynpcs.domain.rule.action.AdjustFactionAction;
import com.storynpcs.domain.rule.action.ChangeStanceAction;
import com.storynpcs.domain.rule.action.SendMessageAction;
import com.storynpcs.domain.rule.action.ShoutAlertAction;
import com.storynpcs.domain.rule.action.YieldCombatAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleActionTest {

    private RuleContext context;
    private UUID actorUuid;

    @BeforeEach
    void setUp() {
        actorUuid = UUID.randomUUID();
        context = new RuleContext(NamespacedId.of("test:master"), "Master Hiro", 20.0, 100.0);
        context.setActorUuid(actorUuid);
        context.setPlayerActor(true);
    }

    @Test
    void testSendMessageAction() {
        var action = new SendMessageAction("Halt, {npc} commands you!", true, SendMessageAction.Target.ACTOR);
        boolean executed = action.execute(context);

        assertThat(executed).isTrue();
        assertThat(context.getRecordedMessages()).containsExactly("Halt, Master Hiro commands you!");
    }

    @Test
    void testAddThreatAction() {
        var action = new AddThreatAction(150.0, true);
        boolean executed = action.execute(context);

        assertThat(executed).isTrue();
        assertThat(context.getRecordedThreats().get(actorUuid)).isEqualTo(150.0);
    }

    @Test
    void testShoutAlertAction() {
        var action = new ShoutAlertAction(24.0, "Intruder detected in the courtyard!");
        boolean executed = action.execute(context);

        assertThat(executed).isTrue();
        assertThat(context.getRecordedAlerts()).containsExactly("Intruder detected in the courtyard!");
    }

    @Test
    void testYieldCombatAction() {
        context.setNpcHealth(10.0); // 10% health
        var action = new YieldCombatAction(0.50, "I concede! You have mastered the blade.");
        boolean executed = action.execute(context);

        assertThat(executed).isTrue();
        assertThat(context.isCombatYielded()).isTrue();
        assertThat(context.getYieldDialogue()).isEqualTo("I concede! You have mastered the blade.");
        assertThat(context.getNpcHealth()).isEqualTo(50.0); // Healed to 50%
    }

    @Test
    void testChangeStanceAction() {
        var action = new ChangeStanceAction(TacticalStance.DEFENSIVE);
        boolean executed = action.execute(context);

        assertThat(executed).isTrue();
        assertThat(context.getNewStance()).isEqualTo(TacticalStance.DEFENSIVE);
    }

    @Test
    void testAdjustFactionAction() {
        NamespacedId faction = NamespacedId.of("test:monks");
        var action = new AdjustFactionAction(faction, 25);
        boolean executed = action.execute(context);

        assertThat(executed).isTrue();
        assertThat(context.getRecordedFactionAdjustments().get(faction)).isEqualTo(25);
    }
}
