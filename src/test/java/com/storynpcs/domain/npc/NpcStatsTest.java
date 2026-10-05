package com.storynpcs.domain.npc;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpcStatsTest {

    @Test
    void everyNumericFieldIsFiniteAndBounded() {
        NpcStats stats = new NpcStats();

        assertThatThrownBy(() -> stats.setMaxHealth(Double.NaN));
        assertThatThrownBy(() -> stats.setMaxHealth(0.0));
        assertThatThrownBy(() -> stats.setAttackDamage(-1.0));
        assertThatThrownBy(() -> stats.setAttackDamage(10_001.0));
        assertThatThrownBy(() -> stats.setMovementSpeed(Double.POSITIVE_INFINITY));
        assertThatThrownBy(() -> stats.setHealthRegenPerSecond(-0.5));
        assertThatThrownBy(() -> stats.setCombatRegenPerSecond(1_001.0));
        assertThatThrownBy(() -> stats.setRespawnTimeSeconds(-1));
        assertThatThrownBy(() -> stats.setAggroRange(129));
        assertThatThrownBy(() -> stats.setXpReward(-5));
        assertThatThrownBy(() -> stats.setXpReward(100_001));
    }

    @Test
    void meleeFieldsAreBoundedAndRejectPartialMutation() {
        NpcStats.Melee melee = new NpcStats.Melee();

        assertThatThrownBy(() -> melee.setAttackDelayTicks(0));
        assertThatThrownBy(() -> melee.setAttackDelayTicks(1_201));
        assertThatThrownBy(() -> melee.setAttackRange(0.1));
        assertThatThrownBy(() -> melee.setKnockbackStrength(10.5));
        assertThatThrownBy(() -> melee.setEffectAmplifier(5));
        assertThatThrownBy(() -> melee.setEffectDurationTicks(-1));
        assertThatThrownBy(() -> melee.setEffectId("x".repeat(257)));

        melee.setAttackDelayTicks(15);
        melee.setAttackRange(3.0);
        melee.setKnockbackStrength(1.5);
        melee.setEffectId("minecraft:poison");
        melee.setEffectDurationTicks(100);
        melee.setEffectAmplifier(1);
        assertThat(melee.getAttackDelayTicks()).isEqualTo(15);
        assertThat(melee.getAttackRange()).isEqualTo(3.0);
    }

    @Test
    void rangedFieldsCarryTheFullProjectileContract() {
        NpcStats.Ranged ranged = new NpcStats.Ranged();

        assertThatThrownBy(() -> ranged.setDamage(-1));
        assertThatThrownBy(() -> ranged.setProjectileSpeed(0.05));
        assertThatThrownBy(() -> ranged.setProjectileSize(9.0));
        assertThatThrownBy(() -> ranged.setAreaDamage(64.5));
        assertThatThrownBy(() -> ranged.setDelayTicks(0));
        assertThatThrownBy(() -> ranged.setRange(129.0));
        assertThatThrownBy(() -> ranged.setFireRateTicks(0));
        assertThatThrownBy(() -> ranged.setShotCount(17));
        assertThatThrownBy(() -> ranged.setAccuracyPercent(101));

        ranged.setDamage(7.5);
        ranged.setProjectileSpeed(2.5);
        ranged.setProjectileSize(0.3);
        ranged.setAreaDamage(4.0);
        ranged.setTrail(true);
        ranged.setDelayTicks(30);
        ranged.setRange(40.0);
        ranged.setFireRateTicks(10);
        ranged.setShotCount(3);
        ranged.setAccuracyPercent(95);
        ranged.setGravityAffected(false);
        ranged.setEffectId("minecraft:wither");
        ranged.setImpactSoundId("minecraft:entity.blaze.hurt");
        ranged.setTrailParticleId("minecraft:flame");

        assertThat(ranged.getShotCount()).isEqualTo(3);
        assertThat(ranged.getAccuracyPercent()).isEqualTo(95);
        assertThat(ranged.isGravityAffected()).isFalse();
        assertThat(ranged.getTrailParticleId()).isEqualTo("minecraft:flame");
    }

    @Test
    void resistanceChannelsClampToTargetContract() {
        NpcResistances resistances = new NpcResistances();

        // All four channels default to normal resistance (1.0); authored
        // setters clamp to [0, 2].
        assertThat(resistances.getKnockback()).isEqualTo(1.0);
        assertThat(resistances.getArrow()).isEqualTo(1.0);
        assertThat(resistances.getMelee()).isEqualTo(1.0);
        assertThat(resistances.getExplosion()).isEqualTo(1.0);

        resistances.setKnockback(-0.1);
        resistances.setArrow(2.1);
        resistances.setMelee(Double.NaN);
        resistances.setExplosion(Double.NEGATIVE_INFINITY);
        assertThat(resistances.getKnockback()).isEqualTo(0.0);
        assertThat(resistances.getArrow()).isEqualTo(2.0);
        assertThat(resistances.getMelee()).isEqualTo(1.0);
        assertThat(resistances.getExplosion()).isEqualTo(0.0);

        resistances.setArrow(2.0); // immunity (ADR-007)
        resistances.setExplosion(0.0); // vulnerability (ADR-007)
        assertThat(resistances.getArrow()).isEqualTo(2.0);
        assertThat(resistances.getExplosion()).isEqualTo(0.0);
    }

    @Test
    void damageScalesFollowTargetFaithfulTwoMinusResistance() {
        NpcResistances resistances = new NpcResistances();

        // ADR-007: incoming damage * (2.0 - resistance).
        assertThat(resistances.damageScaleArrow()).isEqualTo(1.0);
        assertThat(resistances.damageScaleMelee()).isEqualTo(1.0);
        assertThat(resistances.damageScaleExplosion()).isEqualTo(1.0);

        resistances.setArrow(2.0);      // immune → zero damage
        resistances.setMelee(0.0);      // vulnerable → double damage
        resistances.setExplosion(1.5);  // 50% reduced
        assertThat(resistances.damageScaleArrow()).isEqualTo(0.0);
        assertThat(resistances.damageScaleMelee()).isEqualTo(2.0);
        assertThat(resistances.damageScaleExplosion()).isEqualTo(0.5);
    }

    @Test
    void deserializedResistancesPassThroughUnclamped() {
        // ADR-007 target quirk: persisted reads bypass setter clamps — an
        // out-of-range document value reaches damage math verbatim.
        var def = new NpcDefinition(com.storynpcs.domain.common.NamespacedId.of("storynpcs", "r"), "r");
        String json = NpcDefinitionSerde.toJson(def);
        String tampered = json.replaceFirst("\"arrow\":1\\.0", "\"arrow\":2.6");
        assertThat(tampered).contains("\"arrow\":2.6");
        var restored = NpcDefinitionSerde.fromJson(tampered).orElseThrow();
        assertThat(restored.getStats().getResistances().getArrow()).isEqualTo(2.6);
        assertThat(restored.getStats().getResistances().damageScaleArrow()).isCloseTo(-0.6, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void knockbackMultiplierPreservesNeutralAndAppliesImmunityAndAmplification() {
        NpcResistances resistances = new NpcResistances();

        assertThat(resistances.scaleKnockback(0.8F)).isEqualTo(0.8F);
        resistances.setKnockback(2.0); // immune → no knockback
        assertThat(resistances.scaleKnockback(0.8F)).isEqualTo(0.0F);
        resistances.setKnockback(0.0); // vulnerable → double knockback
        assertThat(resistances.scaleKnockback(0.8F)).isEqualTo(1.6F);
    }

    @Test
    void allSixImmunityTogglesRoundTrip() {
        NpcImmunities immunities = new NpcImmunities();
        assertThat(immunities.isPotion()).isFalse();

        immunities.setPotion(true);
        immunities.setFall(true);
        immunities.setSunlight(true);
        immunities.setFire(true);
        immunities.setDrowning(true);
        immunities.setCobweb(true);

        assertThat(immunities.isPotion()).isTrue();
        assertThat(immunities.isFall()).isTrue();
        assertThat(immunities.isSunlight()).isTrue();
        assertThat(immunities.isFire()).isTrue();
        assertThat(immunities.isDrowning()).isTrue();
        assertThat(immunities.isCobweb()).isTrue();
    }

    @Test
    void defeatModesCoverDieHideAndFlee() {
        NpcStats.Defeat defeat = new NpcStats.Defeat();
        assertThat(defeat.getMode()).isEqualTo(NpcStats.Defeat.Mode.DIE);

        defeat.setMode(NpcStats.Defeat.Mode.HIDE);
        assertThat(defeat.getMode()).isEqualTo(NpcStats.Defeat.Mode.HIDE);

        defeat.setMode(NpcStats.Defeat.Mode.FLEE);
        defeat.setFleeHealthPercent(25);
        assertThat(defeat.getFleeHealthPercent()).isEqualTo(25);
        assertThatThrownBy(() -> defeat.setFleeHealthPercent(101));

        defeat.setDropsProfileId("storynpcs:drops/veteran_guard");
        assertThat(defeat.getDropsProfileId()).isEqualTo("storynpcs:drops/veteran_guard");
    }

    @Test
    void nullSectionsAreRejectedWithoutPartialMutation() {
        NpcStats stats = new NpcStats();
        NpcStats.Melee melee = stats.getMelee();
        NpcStats.Ranged ranged = stats.getRanged();
        NpcResistances resistances = stats.getResistances();
        NpcImmunities immunities = stats.getImmunities();
        NpcStats.Defeat defeat = stats.getDefeat();

        assertThatThrownBy(() -> stats.setMelee(null));
        assertThatThrownBy(() -> stats.setRanged(null));
        assertThatThrownBy(() -> stats.setResistances(null));
        assertThatThrownBy(() -> stats.setImmunities(null));
        assertThatThrownBy(() -> stats.setDefeat(null));

        assertThat(stats.getMelee()).isSameAs(melee);
        assertThat(stats.getRanged()).isSameAs(ranged);
        assertThat(stats.getResistances()).isSameAs(resistances);
        assertThat(stats.getImmunities()).isSameAs(immunities);
        assertThat(stats.getDefeat()).isSameAs(defeat);
    }

    @Test
    void fullCombatBlockSurvivesDefinitionJsonRoundTrip() {
        NpcDefinition original = new NpcDefinition(
                NamespacedId.of("storynpcs", "warlord"), "Warlord");
        NpcStats stats = original.getStats();
        stats.setMaxHealth(120.0);
        stats.setAttackDamage(14.0);
        stats.setHealthRegenPerSecond(1.5);
        stats.setCombatRegenPerSecond(0.5);
        stats.setRespawnTimeSeconds(120);
        stats.setAggroRange(32);
        stats.setXpReward(250);
        stats.getMelee().setAttackDelayTicks(12);
        stats.getMelee().setAttackRange(4.0);
        stats.getMelee().setKnockbackStrength(2.0);
        stats.getMelee().setEffectId("minecraft:slowness");
        stats.getRanged().setDamage(9.0);
        stats.getRanged().setShotCount(5);
        stats.getRanged().setAccuracyPercent(60);
        stats.getRanged().setGravityAffected(false);
        stats.getResistances().setArrow(0.25);
        stats.getResistances().setExplosion(1.5);
        stats.getImmunities().setFire(true);
        stats.getImmunities().setDrowning(true);
        stats.getDefeat().setMode(NpcStats.Defeat.Mode.FLEE);
        stats.getDefeat().setFleeHealthPercent(20);
        stats.getDefeat().setDropsProfileId("storynpcs:drops/warlord");

        String json = NpcDefinitionSerde.toJson(original);
        NpcDefinition restored = NpcDefinitionSerde.fromJson(json).orElseThrow();
        NpcStats restoredStats = restored.getStats();

        assertThat(restoredStats.getMaxHealth()).isEqualTo(120.0);
        assertThat(restoredStats.getHealthRegenPerSecond()).isEqualTo(1.5);
        assertThat(restoredStats.getCombatRegenPerSecond()).isEqualTo(0.5);
        assertThat(restoredStats.getXpReward()).isEqualTo(250);
        assertThat(restoredStats.getMelee().getAttackDelayTicks()).isEqualTo(12);
        assertThat(restoredStats.getMelee().getAttackRange()).isEqualTo(4.0);
        assertThat(restoredStats.getMelee().getKnockbackStrength()).isEqualTo(2.0);
        assertThat(restoredStats.getMelee().getEffectId()).isEqualTo("minecraft:slowness");
        assertThat(restoredStats.getRanged().getShotCount()).isEqualTo(5);
        assertThat(restoredStats.getRanged().getAccuracyPercent()).isEqualTo(60);
        assertThat(restoredStats.getRanged().isGravityAffected()).isFalse();
        assertThat(restoredStats.getResistances().getArrow()).isEqualTo(0.25);
        assertThat(restoredStats.getResistances().getExplosion()).isEqualTo(1.5);
        assertThat(restoredStats.getImmunities().isFire()).isTrue();
        assertThat(restoredStats.getImmunities().isDrowning()).isTrue();
        assertThat(restoredStats.getDefeat().getMode()).isEqualTo(NpcStats.Defeat.Mode.FLEE);
        assertThat(restoredStats.getDefeat().getFleeHealthPercent()).isEqualTo(20);
        assertThat(restoredStats.getDefeat().getDropsProfileId())
                .isEqualTo("storynpcs:drops/warlord");
    }
}
