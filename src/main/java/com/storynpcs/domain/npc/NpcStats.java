package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NpcStats {
    @JsonProperty
    private double maxHealth = 20.0;

    @JsonProperty
    private double attackDamage = 5.0;

    @JsonProperty
    private double movementSpeed = 0.25;

    @JsonProperty
    private int respawnTimeSeconds = 20;

    @JsonProperty
    private int aggroRange = 16;

    public NpcStats() {}

    public double getMaxHealth() { return maxHealth; }
    public void setMaxHealth(double maxHealth) { this.maxHealth = maxHealth; }

    public double getAttackDamage() { return attackDamage; }
    public void setAttackDamage(double attackDamage) { this.attackDamage = attackDamage; }

    public double getMovementSpeed() { return movementSpeed; }
    public void setMovementSpeed(double movementSpeed) { this.movementSpeed = movementSpeed; }

    public int getRespawnTimeSeconds() { return respawnTimeSeconds; }
    public void setRespawnTimeSeconds(int respawnTimeSeconds) { this.respawnTimeSeconds = respawnTimeSeconds; }

    public int getAggroRange() { return aggroRange; }
    public void setAggroRange(int aggroRange) { this.aggroRange = aggroRange; }
}
