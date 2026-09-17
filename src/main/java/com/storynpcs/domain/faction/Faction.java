package com.storynpcs.domain.faction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.Objects;

/**
 * Domain definition for a Faction and its reputation boundaries.
 */
public class Faction {
    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private String name;

    @JsonProperty
    private int defaultPoints = 1000;

    @JsonProperty
    private int hostileThreshold = 500;

    @JsonProperty
    private int friendlyThreshold = 1500;

    public Faction() {}

    public Faction(NamespacedId id, String name, int defaultPoints, int hostileThreshold, int friendlyThreshold) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.defaultPoints = defaultPoints;
        this.hostileThreshold = hostileThreshold;
        this.friendlyThreshold = friendlyThreshold;
    }

    public NamespacedId getId() {
        return id;
    }

    public void setId(NamespacedId id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getDefaultPoints() {
        return defaultPoints;
    }

    public void setDefaultPoints(int defaultPoints) {
        this.defaultPoints = defaultPoints;
    }

    public int getHostileThreshold() {
        return hostileThreshold;
    }

    public void setHostileThreshold(int hostileThreshold) {
        this.hostileThreshold = hostileThreshold;
    }

    public int getFriendlyThreshold() {
        return friendlyThreshold;
    }

    public void setFriendlyThreshold(int friendlyThreshold) {
        this.friendlyThreshold = friendlyThreshold;
    }

    public FactionStanding getStandingForPoints(int points) {
        if (points < hostileThreshold) {
            return FactionStanding.HOSTILE;
        } else if (points >= friendlyThreshold) {
            return FactionStanding.FRIENDLY;
        }
        return FactionStanding.NEUTRAL;
    }
}
