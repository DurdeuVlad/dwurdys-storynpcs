package com.storynpcs.domain.rule;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.domain.rule.action.AddThreatAction;
import com.storynpcs.domain.rule.action.AdjustFactionAction;
import com.storynpcs.domain.rule.action.ChangeStanceAction;
import com.storynpcs.domain.rule.action.SendMessageAction;
import com.storynpcs.domain.rule.action.ShoutAlertAction;
import com.storynpcs.domain.rule.action.YieldCombatAction;
import com.storynpcs.domain.rule.condition.ActorIsPlayerCondition;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.domain.rule.condition.StrikeCountCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleYamlSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void testRuleYamlRoundtrip() throws Exception {
        String yaml = """
                id: "storynpcs:dojo_master"
                display:
                  name: "Master Kenshi"
                rules:
                  - id: "sparring_yield"
                    trigger: "ON_DAMAGED"
                    stopPropagation: true
                    conditions:
                      - type: "HEALTH_PERCENT"
                        operator: "LESS_THAN_OR_EQUAL"
                        threshold: 0.20
                    actions:
                      - type: "YIELD_COMBAT"
                        resetHealthFraction: 0.50
                        yieldDialogue: "Well fought! You pass the trial."
                      - type: "SEND_MESSAGE"
                        message: "Trial completed."
                        actionBar: true
                  - id: "strike_warning"
                    trigger: "ON_DAMAGED"
                    conditions:
                      - type: "STRIKE_COUNT"
                        operator: "EQUAL"
                        threshold: 1
                      - type: "ACTOR_IS_PLAYER"
                        required: true
                    actions:
                      - type: "SEND_MESSAGE"
                        message: "Watch your blade!"
                  - id: "retaliate"
                    trigger: "ON_DAMAGED"
                    conditions:
                      - type: "STRIKE_COUNT"
                        operator: "GREATER_THAN"
                        threshold: 1
                    actions:
                      - type: "ADD_THREAT"
                        threat: 250.0
                      - type: "SHOUT_ALERT"
                        radius: 20.0
                        alertMessage: "Guards to arms!"
                      - type: "CHANGE_STANCE"
                        stance: "AGGRESSIVE"
                      - type: "ADJUST_FACTION"
                        factionId: "storynpcs:honor_guard"
                        delta: -15
                """;

        NpcDefinition def = mapper.readValue(yaml, NpcDefinition.class);
        assertThat(def).isNotNull();
        assertThat(def.getId().asString()).isEqualTo("storynpcs:dojo_master");
        assertThat(def.getRules()).hasSize(3);

        // Rule 1: sparring yield
        BehaviorRule rule1 = def.getRules().get(0);
        assertThat(rule1.getId()).isEqualTo("sparring_yield");
        assertThat(rule1.getTrigger()).isEqualTo(TriggerType.ON_DAMAGED);
        assertThat(rule1.isStopPropagation()).isTrue();
        assertThat(rule1.getConditions()).hasSize(1);
        assertThat(rule1.getConditions().get(0)).isInstanceOf(HealthPercentCondition.class);
        assertThat(rule1.getActions()).hasSize(2);
        assertThat(rule1.getActions().get(0)).isInstanceOf(YieldCombatAction.class);
        YieldCombatAction yieldAction = (YieldCombatAction) rule1.getActions().get(0);
        assertThat(yieldAction.getResetHealthFraction()).isEqualTo(0.50);
        assertThat(yieldAction.getYieldDialogue()).isEqualTo("Well fought! You pass the trial.");

        // Rule 2: strike warning
        BehaviorRule rule2 = def.getRules().get(1);
        assertThat(rule2.getId()).isEqualTo("strike_warning");
        assertThat(rule2.getConditions()).hasSize(2);
        assertThat(rule2.getConditions().get(0)).isInstanceOf(StrikeCountCondition.class);
        assertThat(rule2.getConditions().get(1)).isInstanceOf(ActorIsPlayerCondition.class);
        assertThat(rule2.getActions().get(0)).isInstanceOf(SendMessageAction.class);

        // Rule 3: retaliate
        BehaviorRule rule3 = def.getRules().get(2);
        assertThat(rule3.getId()).isEqualTo("retaliate");
        assertThat(rule3.getActions()).hasSize(4);
        assertThat(rule3.getActions().get(0)).isInstanceOf(AddThreatAction.class);
        assertThat(rule3.getActions().get(1)).isInstanceOf(ShoutAlertAction.class);
        assertThat(rule3.getActions().get(2)).isInstanceOf(ChangeStanceAction.class);
        ChangeStanceAction stanceAction = (ChangeStanceAction) rule3.getActions().get(2);
        assertThat(stanceAction.getStance()).isEqualTo(TacticalStance.AGGRESSIVE);
        assertThat(rule3.getActions().get(3)).isInstanceOf(AdjustFactionAction.class);

        // Verify roundtrip serialization
        String serialized = mapper.writeValueAsString(def);
        NpcDefinition roundtripped = mapper.readValue(serialized, NpcDefinition.class);
        assertThat(roundtripped.getRules()).hasSize(3);
        assertThat(roundtripped.getRules().get(0).getId()).isEqualTo("sparring_yield");
    }
}
