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

    /** Display color for UI surfaces, 0xRRGGBB. */
    @JsonProperty
    private int color = 0xFFFFFF;

    /** Passive factions never initiate hostile targeting regardless of standing. */
    @JsonProperty
    private boolean passive = false;

    /**
     * Inter-faction relationship matrix entry: factionId -> standing name
     * (HOSTILE/NEUTRAL/FRIENDLY). Pairwise symmetric lookup is normalized by
     * FactionRelationshipProvider; absent pairs remain NEUTRAL.
     */
    @JsonProperty
    private java.util.Map<String, String> relationships = new java.util.LinkedHashMap<>();

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

    public int getColor() { return color; }

    public void setColor(int color) {
        if (color < 0 || color > 0xFFFFFF) {
            throw new IllegalArgumentException("color must be a 0xRRGGBB value");
        }
        this.color = color;
    }

    public boolean isPassive() { return passive; }
    public void setPassive(boolean passive) { this.passive = passive; }

    /** Unmodifiable view — mutation goes through setRelationship/removeRelationship. */
    public java.util.Map<String, String> getRelationships() {
        return java.util.Collections.unmodifiableMap(relationships);
    }

    public void setRelationships(java.util.Map<String, String> relationships) {
        // Validate into a staging map first: a rejected entry must not destroy
        // the previously committed matrix state.
        java.util.Map<String, String> validated = new java.util.LinkedHashMap<>();
        if (relationships != null) {
            for (var entry : relationships.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("relationship key cannot be null");
                }
                validated.put(NamespacedId.of(entry.getKey()).toString(),
                        normalizeStanding(entry.getValue()));
            }
        }
        this.relationships = validated;
    }

    public void setRelationship(NamespacedId other, String standing) {
        relationships.put(other.toString(), normalizeStanding(standing));
    }

    private static String normalizeStanding(String standing) {
        String normalized = standing == null ? "" : standing.toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("HOSTILE") && !normalized.equals("NEUTRAL") && !normalized.equals("FRIENDLY")) {
            throw new IllegalArgumentException("standing must be HOSTILE, NEUTRAL, or FRIENDLY");
        }
        return normalized;
    }

    public void removeRelationship(NamespacedId other) {
        relationships.remove(other.toString());
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
