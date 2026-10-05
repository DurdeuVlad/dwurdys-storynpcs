package com.storynpcs.domain.ability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.storynpcs.domain.rule.condition.RuleCondition;

import java.util.ArrayList;
import java.util.List;

/**
 * One authored combat ability on an NPC definition: a typed effect with a
 * trigger, a rate limit, an optional probability, and optional rule-engine
 * conditions (reused from {@code domain/rule} rather than a parallel gate DSL).
 *
 * <p>All parameters are bounded — {@link #validate()} rejects out-of-range
 * values and trigger combinations the target cannot express. Parameters that
 * do not apply to a type are ignored at runtime but still validated when set.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NpcAbility {

    /** Max cooldown: 60 seconds. */
    public static final long MAX_COOLDOWN_TICKS = 1200L;
    /** Max effect range (pull/push/snare/teleport). */
    public static final double MAX_RANGE = 32.0;
    public static final double MIN_RANGE = 0.5;
    /** Max applied velocity magnitude. */
    public static final double MAX_STRENGTH = 4.0;
    /** Max snare duration: 10 seconds. */
    public static final int MAX_DURATION_TICKS = 200;
    public static final int MAX_AMPLIFIER = 3;
    /** Max smash bonus damage. */
    public static final double MAX_BONUS_DAMAGE = 20.0;

    private AbilityType type;
    private AbilityTrigger trigger;
    private long cooldownTicks;
    private double chance = 1.0;

    /** Effect radius / max blink distance (pull, push, snare, teleport). */
    private Double range;
    /** Applied velocity magnitude (pull, push, smash). */
    private Double strength;
    /** Incoming-damage multiplier in [0,1] (block). */
    private Double damageMultiplier;
    /** Effect duration in ticks (snare). */
    private Integer durationTicks;
    /** Effect amplifier (snare). */
    private Integer amplifier;
    /** Extra attack damage (smash). */
    private Double bonusDamage;

    private List<RuleCondition> conditions = new ArrayList<>();

    public NpcAbility() {}

    public NpcAbility(AbilityType type, AbilityTrigger trigger) {
        this.type = type;
        this.trigger = trigger;
    }

    public AbilityType getType() { return type; }
    public void setType(AbilityType type) { this.type = type; }

    public AbilityTrigger getTrigger() { return trigger; }
    public void setTrigger(AbilityTrigger trigger) { this.trigger = trigger; }

    public long getCooldownTicks() { return cooldownTicks; }
    public void setCooldownTicks(long cooldownTicks) { this.cooldownTicks = cooldownTicks; }

    public double getChance() { return chance; }
    public void setChance(double chance) { this.chance = chance; }

    public Double getRange() { return range; }
    public void setRange(Double range) { this.range = range; }

    public Double getStrength() { return strength; }
    public void setStrength(Double strength) { this.strength = strength; }

    public Double getDamageMultiplier() { return damageMultiplier; }
    public void setDamageMultiplier(Double damageMultiplier) { this.damageMultiplier = damageMultiplier; }

    public Integer getDurationTicks() { return durationTicks; }
    public void setDurationTicks(Integer durationTicks) { this.durationTicks = durationTicks; }

    public Integer getAmplifier() { return amplifier; }
    public void setAmplifier(Integer amplifier) { this.amplifier = amplifier; }

    public Double getBonusDamage() { return bonusDamage; }
    public void setBonusDamage(Double bonusDamage) { this.bonusDamage = bonusDamage; }

    public List<RuleCondition> getConditions() { return conditions; }
    public void setConditions(List<RuleCondition> conditions) {
        this.conditions = conditions != null ? conditions : new ArrayList<>();
    }

    /**
     * Effective range with a safe default per type when unset.
     * Pull/push/snare default to 12 blocks; teleport blinks up to 10.
     */
    public double effectiveRange() {
        if (range != null) return range;
        return type == AbilityType.TELEPORT ? 10.0 : 12.0;
    }

    public double effectiveStrength() {
        return strength != null ? strength : 1.0;
    }

    public double effectiveDamageMultiplier() {
        return damageMultiplier != null ? damageMultiplier : 0.5;
    }

    public int effectiveDurationTicks() {
        return durationTicks != null ? durationTicks : 60;
    }

    public int effectiveAmplifier() {
        return amplifier != null ? amplifier : 0;
    }

    public double effectiveBonusDamage() {
        return bonusDamage != null ? bonusDamage : 0.0;
    }

    /**
     * Rejects illegal combinations and unbounded parameters — load and
     * canonical-write paths both call this so a malformed ability can never
     * persist and then silently break on reload.
     */
    public void validate() {
        if (type == null) {
            throw new IllegalStateException("ability requires a 'type'");
        }
        if (trigger == null) {
            throw new IllegalStateException("ability '" + type + "' requires a 'trigger'");
        }
        if (!type.allows(trigger)) {
            throw new IllegalStateException("ability '" + type + "' cannot use trigger '" + trigger
                    + "' — allowed: " + type.allowedTriggers());
        }
        if (cooldownTicks < 0 || cooldownTicks > MAX_COOLDOWN_TICKS) {
            throw new IllegalStateException("ability '" + type + "' cooldownTicks must be in [0, "
                    + MAX_COOLDOWN_TICKS + "], got " + cooldownTicks);
        }
        if (Double.isNaN(chance) || chance < 0.0 || chance > 1.0) {
            throw new IllegalStateException("ability '" + type + "' chance must be in [0,1], got " + chance);
        }
        checkBounded(range, "range", MIN_RANGE, MAX_RANGE);
        checkBounded(strength, "strength", 0.0, MAX_STRENGTH);
        checkBounded(damageMultiplier, "damageMultiplier", 0.0, 1.0);
        checkBounded(bonusDamage, "bonusDamage", 0.0, MAX_BONUS_DAMAGE);
        if (durationTicks != null && (durationTicks < 1 || durationTicks > MAX_DURATION_TICKS)) {
            throw new IllegalStateException("ability '" + type + "' durationTicks must be in [1, "
                    + MAX_DURATION_TICKS + "], got " + durationTicks);
        }
        if (amplifier != null && (amplifier < 0 || amplifier > MAX_AMPLIFIER)) {
            throw new IllegalStateException("ability '" + type + "' amplifier must be in [0, "
                    + MAX_AMPLIFIER + "], got " + amplifier);
        }
        for (RuleCondition condition : conditions) {
            validateConditionTree(condition, 0);
        }
    }

    private static final int MAX_CONDITION_DEPTH = 8;

    /**
     * Deep-checks composite condition trees — a nested {@code null} child must
     * fail at validation time, not as an NPE inside the damage pipeline.
     */
    private void validateConditionTree(RuleCondition condition, int depth) {
        if (condition == null) {
            throw new IllegalStateException("ability '" + type + "' has a null condition");
        }
        if (depth > MAX_CONDITION_DEPTH) {
            throw new IllegalStateException("ability '" + type + "' conditions nested deeper than "
                    + MAX_CONDITION_DEPTH);
        }
        if (condition instanceof com.storynpcs.domain.rule.condition.CompositeCondition composite) {
            for (RuleCondition child : composite.getConditions()) {
                validateConditionTree(child, depth + 1);
            }
        }
    }

    private void checkBounded(Double value, String name, double min, double max) {
        if (value != null && (Double.isNaN(value) || value < min || value > max)) {
            throw new IllegalStateException("ability '" + type + "' " + name
                    + " must be in [" + min + ", " + max + "], got " + value);
        }
    }
}
