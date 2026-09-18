package com.storynpcs.domain.progression;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.*;

/**
 * Separate, crash-safe player progression state.
 * Stores quest progress, faction standing, and dialogue interaction history.
 */
public class PlayerProgression {
    @JsonProperty(required = true)
    private UUID playerUuid;

    @JsonProperty
    private Map<NamespacedId, QuestProgressState> quests = new HashMap<>();

    @JsonProperty
    private Map<NamespacedId, Integer> factionPoints = new HashMap<>();

    @JsonProperty
    private Set<String> visitedDialogueNodes = new HashSet<>();

    public PlayerProgression() {}

    public PlayerProgression(UUID playerUuid) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }

    public Map<NamespacedId, QuestProgressState> getQuests() { return quests; }
    public void setQuests(Map<NamespacedId, QuestProgressState> quests) { this.quests = quests; }

    public Map<NamespacedId, Integer> getFactionPoints() { return factionPoints; }
    public void setFactionPoints(Map<NamespacedId, Integer> factionPoints) { this.factionPoints = factionPoints; }

    public Set<String> getVisitedDialogueNodes() { return visitedDialogueNodes; }
    public void setVisitedDialogueNodes(Set<String> visitedDialogueNodes) { this.visitedDialogueNodes = visitedDialogueNodes; }

    public QuestProgressState getQuestState(NamespacedId questId) {
        return quests.computeIfAbsent(questId, QuestProgressState::new);
    }

    public int getFactionScore(NamespacedId factionId, int defaultPoints) {
        return factionPoints.getOrDefault(factionId, defaultPoints);
    }

    public void setFactionScore(NamespacedId factionId, int points) {
        int clamped = Math.max(-100_000, Math.min(100_000, points));
        factionPoints.put(factionId, clamped);
    }

    public void adjustFactionScore(NamespacedId factionId, int delta, int defaultPoints) {
        int current = getFactionScore(factionId, defaultPoints);
        long sum = (long) current + (long) delta;
        int clamped = (int) Math.max(-100_000, Math.min(100_000, sum));
        factionPoints.put(factionId, clamped);
    }

    public void recordDialogueNodeVisit(String nodeId) {
        visitedDialogueNodes.add(nodeId);
    }

    public boolean hasVisitedDialogueNode(String nodeId) {
        return visitedDialogueNodes.contains(nodeId);
    }
}
