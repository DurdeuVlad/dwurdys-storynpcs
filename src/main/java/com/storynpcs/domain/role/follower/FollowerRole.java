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
    private int daysHired = 0;

    @JsonProperty
    private int dailyRate = 10;

    public FollowerRole() {}

    public FollowerRole(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        this.state = State.FOLLOWING;
    }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state != null ? state : State.STAYING; }

    public int getDaysHired() { return daysHired; }
    public void setDaysHired(int daysHired) { this.daysHired = daysHired; }

    public int getDailyRate() { return dailyRate; }
    public void setDailyRate(int dailyRate) { this.dailyRate = dailyRate; }

    public boolean isOwnedBy(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }
}