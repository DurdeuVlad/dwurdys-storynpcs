package com.storynpcs.domain.role.follower;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class FollowerRole {

    public enum State {
        FOLLOWING,
        STAYING,
        GUARDING
    }

    @JsonProperty
    private UUID ownerUuid;

    @JsonProperty
    private State state = State.STAYING;

    @JsonProperty
    private FormationType formation = FormationType.WEDGE;

    @JsonProperty
    private int formationSlot = -1; // -1 = auto-assign

    @JsonProperty
    private double formationSpacing = 2.5;

    @JsonProperty
    private int daysHired = 0;

    @JsonProperty
    private int dailyRate = 10;

    public FollowerRole() {}

    public FollowerRole(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        this.state = State.FOLLOWING;
    }

    public FollowerRole(UUID ownerUuid, FormationType formation, int formationSlot, double formationSpacing) {
        this.ownerUuid = ownerUuid;
        this.state = State.FOLLOWING;
        this.formation = formation != null ? formation : FormationType.WEDGE;
        this.formationSlot = formationSlot;
        this.formationSpacing = formationSpacing > 0 ? formationSpacing : 2.5;
    }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state != null ? state : State.STAYING; }

    public FormationType getFormation() { return formation; }
    public void setFormation(FormationType formation) {
        this.formation = formation != null ? formation : FormationType.WEDGE;
    }

    public int getFormationSlot() { return formationSlot; }
    public void setFormationSlot(int formationSlot) { this.formationSlot = formationSlot; }

    public double getFormationSpacing() { return formationSpacing; }
    public void setFormationSpacing(double formationSpacing) {
        this.formationSpacing = formationSpacing > 0 ? formationSpacing : 2.5;
    }

    public int getDaysHired() { return daysHired; }
    public void setDaysHired(int daysHired) { this.daysHired = daysHired; }

    public int getDailyRate() { return dailyRate; }
    public void setDailyRate(int dailyRate) { this.dailyRate = dailyRate; }

    public boolean isOwnedBy(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }
}