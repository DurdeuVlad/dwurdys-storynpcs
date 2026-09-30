package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authoritative combat statistics for an NPC definition. Numeric combat fields
 * are finite and bounded; most setters reject invalid values while the authored
 * resistance channels clamp to their target range.
 */
public class NpcStats {

    @JsonProperty
    private double maxHealth = 20.0;

    @JsonProperty
    private double attackDamage = 5.0;

    @JsonProperty
    private double movementSpeed = 0.25;

    /** Health regenerated per second while out of combat. */
    @JsonProperty
    private double healthRegenPerSecond = 0.0;

    /** Health regenerated per second while engaged in combat. */
    @JsonProperty
    private double combatRegenPerSecond = 0.0;

    @JsonProperty
    private int respawnTimeSeconds = 20;

    @JsonProperty
    private int aggroRange = 16;

    @JsonProperty
    private Melee melee = new Melee();

    @JsonProperty
    private Ranged ranged = new Ranged();

    @JsonProperty
    private Resistances resistances = new Resistances();

    @JsonProperty
    private Immunities immunities = new Immunities();

    @JsonProperty
    private Defeat defeat = new Defeat();

    /** Experience granted to the killer on defeat. */
    @JsonProperty
    private int xpReward = 0;

    public NpcStats() {}

    public double getMaxHealth() { return maxHealth; }
    public void setMaxHealth(double maxHealth) {
        this.maxHealth = boundedDouble(maxHealth, 0.5, 100_000.0, "maxHealth");
    }

    public double getAttackDamage() { return attackDamage; }
    public void setAttackDamage(double attackDamage) {
        this.attackDamage = boundedDouble(attackDamage, 0.0, 10_000.0, "attackDamage");
    }

    public double getMovementSpeed() { return movementSpeed; }
    public void setMovementSpeed(double movementSpeed) {
        this.movementSpeed = boundedDouble(movementSpeed, 0.0, 5.0, "movementSpeed");
    }

    public double getHealthRegenPerSecond() { return healthRegenPerSecond; }
    public void setHealthRegenPerSecond(double healthRegenPerSecond) {
        this.healthRegenPerSecond = boundedDouble(healthRegenPerSecond, 0.0, 1_000.0, "healthRegenPerSecond");
    }

    public double getCombatRegenPerSecond() { return combatRegenPerSecond; }
    public void setCombatRegenPerSecond(double combatRegenPerSecond) {
        this.combatRegenPerSecond = boundedDouble(combatRegenPerSecond, 0.0, 1_000.0, "combatRegenPerSecond");
    }

    public int getRespawnTimeSeconds() { return respawnTimeSeconds; }
    public void setRespawnTimeSeconds(int respawnTimeSeconds) {
        this.respawnTimeSeconds = boundedInt(respawnTimeSeconds, 0, 86_400, "respawnTimeSeconds");
    }

    public int getAggroRange() { return aggroRange; }
    public void setAggroRange(int aggroRange) {
        this.aggroRange = boundedInt(aggroRange, 0, 128, "aggroRange");
    }

    public Melee getMelee() { return melee; }
    public void setMelee(Melee melee) {
        if (melee == null) throw new IllegalArgumentException("melee cannot be null");
        this.melee = melee;
    }

    public Ranged getRanged() { return ranged; }
    public void setRanged(Ranged ranged) {
        if (ranged == null) throw new IllegalArgumentException("ranged cannot be null");
        this.ranged = ranged;
    }

    public Resistances getResistances() { return resistances; }
    public void setResistances(Resistances resistances) {
        if (resistances == null) throw new IllegalArgumentException("resistances cannot be null");
        this.resistances = resistances;
    }

    public Immunities getImmunities() { return immunities; }
    public void setImmunities(Immunities immunities) {
        if (immunities == null) throw new IllegalArgumentException("immunities cannot be null");
        this.immunities = immunities;
    }

    public Defeat getDefeat() { return defeat; }
    public void setDefeat(Defeat defeat) {
        if (defeat == null) throw new IllegalArgumentException("defeat cannot be null");
        this.defeat = defeat;
    }

    public int getXpReward() { return xpReward; }
    public void setXpReward(int xpReward) {
        this.xpReward = boundedInt(xpReward, 0, 100_000, "xpReward");
    }

    /** Melee cadence, reach, knockback, and applied-on-hit effect. */
    public static class Melee {
        @JsonProperty
        private int attackDelayTicks = 20;

        @JsonProperty
        private double attackRange = 2.5;

        @JsonProperty
        private double knockbackStrength = 0.0;

        /** Namespaced mob-effect id applied on hit; blank disables the effect. */
        @JsonProperty
        private String effectId = "";

        @JsonProperty
        private int effectDurationTicks = 0;

        @JsonProperty
        private int effectAmplifier = 0;

        public int getAttackDelayTicks() { return attackDelayTicks; }
        public void setAttackDelayTicks(int attackDelayTicks) {
            this.attackDelayTicks = boundedInt(attackDelayTicks, 1, 1_200, "attackDelayTicks");
        }

        public double getAttackRange() { return attackRange; }
        public void setAttackRange(double attackRange) {
            this.attackRange = boundedDouble(attackRange, 0.5, 64.0, "attackRange");
        }

        public double getKnockbackStrength() { return knockbackStrength; }
        public void setKnockbackStrength(double knockbackStrength) {
            this.knockbackStrength = boundedDouble(knockbackStrength, 0.0, 10.0, "knockbackStrength");
        }

        public String getEffectId() { return effectId; }
        public void setEffectId(String effectId) {
            this.effectId = boundedText(effectId, "effectId", 256);
        }

        public int getEffectDurationTicks() { return effectDurationTicks; }
        public void setEffectDurationTicks(int effectDurationTicks) {
            this.effectDurationTicks = boundedInt(effectDurationTicks, 0, 6_000, "effectDurationTicks");
        }

        public int getEffectAmplifier() { return effectAmplifier; }
        public void setEffectAmplifier(int effectAmplifier) {
            this.effectAmplifier = boundedInt(effectAmplifier, 0, 4, "effectAmplifier");
        }
    }

    /** Projectile contract — deterministic parameters, no client authority. */
    public static class Ranged {
        /**
         * Opt-in switch: every definition carries a default Ranged block, so
         * the values only arm {@code NpcRangedAttackGoal} when the author
         * explicitly writes {@code enabled: true}.
         */
        @JsonProperty
        private boolean enabled = false;

        @JsonProperty
        private double damage = 4.0;

        @JsonProperty
        private double projectileSpeed = 1.5;

        @JsonProperty
        private double projectileSize = 0.5;

        /** Area-of-effect radius applied on impact; 0 disables splash. */
        @JsonProperty
        private double areaDamage = 0.0;

        @JsonProperty
        private boolean trail = false;

        @JsonProperty
        private int delayTicks = 40;

        @JsonProperty
        private double range = 32.0;

        @JsonProperty
        private int fireRateTicks = 20;

        @JsonProperty
        private int shotCount = 1;

        /** Spread/accuracy in percent: 100 = perfectly accurate. */
        @JsonProperty
        private int accuracyPercent = 80;

        @JsonProperty
        private boolean gravityAffected = true;

        @JsonProperty
        private String effectId = "";

        @JsonProperty
        private int effectDurationTicks = 0;

        @JsonProperty
        private int effectAmplifier = 0;

        @JsonProperty
        private String impactSoundId = "";

        @JsonProperty
        private String trailParticleId = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getDamage() { return damage; }
        public void setDamage(double damage) {
            this.damage = boundedDouble(damage, 0.0, 10_000.0, "damage");
        }

        public double getProjectileSpeed() { return projectileSpeed; }
        public void setProjectileSpeed(double projectileSpeed) {
            this.projectileSpeed = boundedDouble(projectileSpeed, 0.1, 10.0, "projectileSpeed");
        }

        public double getProjectileSize() { return projectileSize; }
        public void setProjectileSize(double projectileSize) {
            this.projectileSize = boundedDouble(projectileSize, 0.01, 8.0, "projectileSize");
        }

        public double getAreaDamage() { return areaDamage; }
        public void setAreaDamage(double areaDamage) {
            this.areaDamage = boundedDouble(areaDamage, 0.0, 64.0, "areaDamage");
        }

        public boolean hasTrail() { return trail; }
        public void setTrail(boolean trail) { this.trail = trail; }

        public int getDelayTicks() { return delayTicks; }
        public void setDelayTicks(int delayTicks) {
            this.delayTicks = boundedInt(delayTicks, 1, 1_200, "delayTicks");
        }

        public double getRange() { return range; }
        public void setRange(double range) {
            this.range = boundedDouble(range, 0.5, 128.0, "range");
        }

        public int getFireRateTicks() { return fireRateTicks; }
        public void setFireRateTicks(int fireRateTicks) {
            this.fireRateTicks = boundedInt(fireRateTicks, 1, 600, "fireRateTicks");
        }

        public int getShotCount() { return shotCount; }
        public void setShotCount(int shotCount) {
            this.shotCount = boundedInt(shotCount, 1, 16, "shotCount");
        }

        public int getAccuracyPercent() { return accuracyPercent; }
        public void setAccuracyPercent(int accuracyPercent) {
            this.accuracyPercent = boundedInt(accuracyPercent, 0, 100, "accuracyPercent");
        }

        public boolean isGravityAffected() { return gravityAffected; }
        public void setGravityAffected(boolean gravityAffected) { this.gravityAffected = gravityAffected; }

        public String getEffectId() { return effectId; }
        public void setEffectId(String effectId) {
            this.effectId = boundedText(effectId, "effectId", 256);
        }

        public int getEffectDurationTicks() { return effectDurationTicks; }
        public void setEffectDurationTicks(int effectDurationTicks) {
            this.effectDurationTicks = boundedInt(effectDurationTicks, 0, 6_000, "effectDurationTicks");
        }

        public int getEffectAmplifier() { return effectAmplifier; }
        public void setEffectAmplifier(int effectAmplifier) {
            this.effectAmplifier = boundedInt(effectAmplifier, 0, 4, "effectAmplifier");
        }

        public String getImpactSoundId() { return impactSoundId; }
        public void setImpactSoundId(String impactSoundId) {
            this.impactSoundId = boundedText(impactSoundId, "impactSoundId", 256);
        }

        public String getTrailParticleId() { return trailParticleId; }
        public void setTrailParticleId(String trailParticleId) {
            this.trailParticleId = boundedText(trailParticleId, "trailParticleId", 256);
        }
    }

    /**
     * Incoming-effect multipliers per channel, clamped to [0, 2]: 1.0 is normal,
     * 0 disables the effect, and values above 1 amplify it. The four channels
     * match the target contract: knockback, arrow, melee, explosion.
     */
    public static class Resistances {
        private static final double MIN_MULTIPLIER = 0.0;
        private static final double NEUTRAL_MULTIPLIER = 1.0;
        private static final double MAX_MULTIPLIER = 2.0;

        @JsonProperty
        private double knockback = NEUTRAL_MULTIPLIER;

        @JsonProperty
        private double arrow = NEUTRAL_MULTIPLIER;

        @JsonProperty
        private double melee = NEUTRAL_MULTIPLIER;

        @JsonProperty
        private double explosion = NEUTRAL_MULTIPLIER;

        public double getKnockback() { return knockback; }
        public void setKnockback(double knockback) { this.knockback = clamp(knockback); }

        public float scaleKnockback(float strength) {
            return (float) (strength * knockback);
        }

        public double getArrow() { return arrow; }
        public void setArrow(double arrow) { this.arrow = clamp(arrow); }

        public double getMelee() { return melee; }
        public void setMelee(double melee) { this.melee = clamp(melee); }

        public double getExplosion() { return explosion; }
        public void setExplosion(double explosion) { this.explosion = clamp(explosion); }

        private static double clamp(double value) {
            if (Double.isNaN(value)) return NEUTRAL_MULTIPLIER;
            return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
        }
    }

    /** Six toggleable damage sources matching the target immunity contract. */
    public static class Immunities {
        @JsonProperty
        private boolean potion = false;

        @JsonProperty
        private boolean fall = false;

        @JsonProperty
        private boolean sunlight = false;

        @JsonProperty
        private boolean fire = false;

        @JsonProperty
        private boolean drowning = false;

        @JsonProperty
        private boolean cobweb = false;

        public boolean isPotionImmune() { return potion; }
        public void setPotionImmune(boolean potion) { this.potion = potion; }

        public boolean isFallImmune() { return fall; }
        public void setFallImmune(boolean fall) { this.fall = fall; }

        public boolean isSunlightImmune() { return sunlight; }
        public void setSunlightImmune(boolean sunlight) { this.sunlight = sunlight; }

        public boolean isFireImmune() { return fire; }
        public void setFireImmune(boolean fire) { this.fire = fire; }

        public boolean isDrowningImmune() { return drowning; }
        public void setDrowningImmune(boolean drowning) { this.drowning = drowning; }

        public boolean isCobwebImmune() { return cobweb; }
        public void setCobwebImmune(boolean cobweb) { this.cobweb = cobweb; }
    }

    /** Defeat behavior: how the NPC resolves fatal damage and respawn. */
    public static class Defeat {
        public enum Mode {
            /** Normal death; the entity is removed. */
            DIE,
            /** The NPC vanishes but respawns at its start position after the timer. */
            HIDE,
            /** The NPC disengages and returns home instead of dying. */
            FLEE
        }

        @JsonProperty
        private Mode mode = Mode.DIE;

        /** Health percentage below which FLEE mode disengages. */
        @JsonProperty
        private int fleeHealthPercent = 10;

        /** Namespaced id of the drops profile applied on defeat; blank uses the default table. */
        @JsonProperty
        private String dropsProfileId = "";

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) {
            this.mode = mode != null ? mode : Mode.DIE;
        }

        public int getFleeHealthPercent() { return fleeHealthPercent; }
        public void setFleeHealthPercent(int fleeHealthPercent) {
            this.fleeHealthPercent = boundedInt(fleeHealthPercent, 0, 100, "fleeHealthPercent");
        }

        public String getDropsProfileId() { return dropsProfileId; }
        public void setDropsProfileId(String dropsProfileId) {
            this.dropsProfileId = boundedText(dropsProfileId, "dropsProfileId", 256);
        }
    }

    private static double boundedDouble(double value, double min, double max, String field) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    field + " must be finite and between " + min + " and " + max);
        }
        return value;
    }

    private static int boundedInt(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static String boundedText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
