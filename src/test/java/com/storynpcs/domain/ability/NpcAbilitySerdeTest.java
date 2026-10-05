package com.storynpcs.domain.ability;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.rule.condition.HealthPercentCondition;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import com.storynpcs.yaml.YamlDefinitionWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trip coverage for the authored abilities list (#147). */
class NpcAbilitySerdeTest {

    @Test
    void abilitiesRoundTripThroughJson() {
        var def = new NpcDefinition(NamespacedId.of("storynpcs:brute"), "Brute");
        var smash = new NpcAbility(AbilityType.SMASH, AbilityTrigger.ATTACK);
        smash.setCooldownTicks(60);
        smash.setChance(0.35);
        smash.setStrength(0.9);
        smash.setBonusDamage(3.0);
        smash.getConditions().add(new HealthPercentCondition());
        var pull = new NpcAbility(AbilityType.PULL, AbilityTrigger.UPDATE);
        pull.setRange(14.0);
        pull.setStrength(1.2);
        def.getAbilities().add(smash);
        def.getAbilities().add(pull);

        var restored = NpcDefinitionSerde.fromJson(NpcDefinitionSerde.toJson(def));
        assertThat(restored).isPresent();
        var abilities = restored.get().getAbilities();
        assertThat(abilities).hasSize(2);

        var s = abilities.get(0);
        assertThat(s.getType()).isEqualTo(AbilityType.SMASH);
        assertThat(s.getTrigger()).isEqualTo(AbilityTrigger.ATTACK);
        assertThat(s.getCooldownTicks()).isEqualTo(60);
        assertThat(s.getChance()).isEqualTo(0.35);
        assertThat(s.getStrength()).isEqualTo(0.9);
        assertThat(s.getBonusDamage()).isEqualTo(3.0);
        assertThat(s.getConditions()).hasSize(1);
        assertThat(s.getConditions().get(0)).isInstanceOf(HealthPercentCondition.class);

        var p = abilities.get(1);
        assertThat(p.getType()).isEqualTo(AbilityType.PULL);
        assertThat(p.getRange()).isEqualTo(14.0);
    }

    @Test
    void abilitiesLoadFromYaml() {
        String yaml = """
                id: "storynpcs:ability_guard"
                display:
                  name: "Warden"
                abilities:
                  - type: "BLOCK"
                    trigger: "DAMAGED"
                    cooldownTicks: 40
                    chance: 0.5
                    damageMultiplier: 0.4
                  - type: "SNARE"
                    trigger: "UPDATE"
                    range: 10.0
                    durationTicks: 60
                    amplifier: 1
                """;
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        var npc = loader.loadNpc(yaml, "ability_guard.yaml", result);

        assertThat(result.isValid()).as(result.formatReport()).isTrue();
        assertThat(npc).isNotNull();
        assertThat(npc.getAbilities()).hasSize(2);
        assertThat(npc.getAbilities().get(0).getType()).isEqualTo(AbilityType.BLOCK);
        assertThat(npc.getAbilities().get(0).getDamageMultiplier()).isEqualTo(0.4);
        assertThat(npc.getAbilities().get(1).getAmplifier()).isEqualTo(1);
    }

    @Test
    void invalidAbilityRejectedOnLoad() {
        String yaml = """
                id: "storynpcs:bad_ability"
                abilities:
                  - type: "BLOCK"
                    trigger: "UPDATE"
                """;
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        var npc = loader.loadNpc(yaml, "bad_ability.yaml", result);

        assertThat(npc).isNull();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.formatReport()).contains("ABILITY_INVALID");
    }

    @Test
    void outOfBoundsAbilityRejectedOnLoad() {
        String yaml = """
                id: "storynpcs:huge_pull"
                abilities:
                  - type: "PULL"
                    trigger: "UPDATE"
                    range: 9000
                """;
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        assertThat(loader.loadNpc(yaml, "huge_pull.yaml", result)).isNull();
        assertThat(result.formatReport()).contains("range");
    }

    @Test
    void abilitiesRoundTripThroughYamlWrite(@TempDir java.nio.file.Path dir) throws Exception {
        var def = new NpcDefinition(NamespacedId.of("storynpcs:warden"), "Warden");
        var block = new NpcAbility(AbilityType.BLOCK, AbilityTrigger.DAMAGED);
        block.setCooldownTicks(40);
        block.setChance(0.5);
        block.setDamageMultiplier(0.4);
        var snare = new NpcAbility(AbilityType.SNARE, AbilityTrigger.UPDATE);
        snare.setRange(10.0);
        snare.setDurationTicks(60);
        snare.setAmplifier(1);
        def.getAbilities().add(block);
        def.getAbilities().add(snare);

        var written = new YamlDefinitionWriter()
                .writeDefinition(dir, "npcs", "warden", def);

        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        var result = ValidationResult.valid();
        var npc = loader.loadNpc(java.nio.file.Files.readString(written), "warden.yaml", result);

        assertThat(result.isValid()).as(result.formatReport()).isTrue();
        assertThat(npc).isNotNull();
        assertThat(npc.getAbilities()).hasSize(2);
        var b = npc.getAbilities().get(0);
        assertThat(b.getType()).isEqualTo(AbilityType.BLOCK);
        assertThat(b.getTrigger()).isEqualTo(AbilityTrigger.DAMAGED);
        assertThat(b.getCooldownTicks()).isEqualTo(40);
        assertThat(b.getChance()).isEqualTo(0.5);
        assertThat(b.getDamageMultiplier()).isEqualTo(0.4);
        var s = npc.getAbilities().get(1);
        assertThat(s.getType()).isEqualTo(AbilityType.SNARE);
        assertThat(s.getRange()).isEqualTo(10.0);
        assertThat(s.getDurationTicks()).isEqualTo(60);
        assertThat(s.getAmplifier()).isEqualTo(1);
    }
}
