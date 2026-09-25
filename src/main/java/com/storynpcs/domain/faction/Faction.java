package com.storynpcs.domain.faction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.HashMap;
import java.util.Map;
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

    /**
     * Explicit inter-faction relationship overrides (issue #70 — faction relationship
     * matrix). A faction not present in this map has no declared relationship to this
     * one; callers must treat that as {@link FactionStanding#NEUTRAL} via
     * {@link #getDeclaredRelationship(NamespacedId)} rather than assuming absence
     * means hostility or friendliness.
     */
    @JsonProperty
    private Map<NamespacedId, FactionStanding> relationships = new HashMap<>();

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

    public Map<NamespacedId, FactionStanding> getRelationships() {
        return relationships;
    }

    public void setRelationships(Map<NamespacedId, FactionStanding> relationships) {
        this.relationships = new HashMap<>();
        if (relationships == null) return;
        for (Map.Entry<NamespacedId, FactionStanding> entry : relationships.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("faction relationship entries require a non-null faction ID and standing");
            }
            if (id != null && entry.getKey().equals(id)) {
                throw new IllegalArgumentException("a faction cannot declare a relationship to itself: " + id);
            }
            this.relationships.put(entry.getKey(), entry.getValue());
        }
    }

    /** Declares (or overwrites) this faction's relationship to another. Rejects a self-relationship. */
    public void setRelationshipTo(NamespacedId otherFactionId, FactionStanding standing) {
        Objects.requireNonNull(otherFactionId, "otherFactionId");
        Objects.requireNonNull(standing, "standing");
        if (id != null && otherFactionId.equals(id)) {
            throw new IllegalArgumentException("a faction cannot declare a relationship to itself: " + id);
        }
        relationships.put(otherFactionId, standing);
    }

    public void removeRelationshipTo(NamespacedId otherFactionId) {
        relationships.remove(otherFactionId);
    }

    /**
     * This faction's declared relationship to another, or {@link FactionStanding#NEUTRAL}
     * if none is declared. Never returns null.
     */
    public FactionStanding getDeclaredRelationship(NamespacedId otherFactionId) {
        return relationships.getOrDefault(otherFactionId, FactionStanding.NEUTRAL);
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
