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

    @JsonProperty
    private long questRevision;

    @JsonProperty
    private Map<NamespacedId, PendingQuestCompletion> pendingQuestCompletions = new HashMap<>();


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

    public long getQuestRevision() { return questRevision; }
    public void setQuestRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        this.questRevision = revision;
    }

    public Map<NamespacedId, PendingQuestCompletion> getPendingQuestCompletions() {
        return pendingQuestCompletions;
    }

    public void setPendingQuestCompletions(Map<NamespacedId, PendingQuestCompletion> pending) {
        pendingQuestCompletions = pending == null ? new HashMap<>() : new HashMap<>(pending);
        for (Map.Entry<NamespacedId, PendingQuestCompletion> entry : pendingQuestCompletions.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !entry.getKey().equals(entry.getValue().questId())) {
                throw new IllegalArgumentException("pending quest completion key does not match its quest ID");
            }
        }
    }

    /** Deep snapshot used to restore cached progression after a failed durable write. */
    public PlayerProgression copy() {
        PlayerProgression copy = new PlayerProgression(playerUuid);
        copy.questRevision = questRevision;
        copy.quests = new HashMap<>();
        quests.forEach((id, state) -> copy.quests.put(id, state.copy()));
        copy.factionPoints = new HashMap<>(factionPoints);
        copy.visitedDialogueNodes = new HashSet<>(visitedDialogueNodes);
        copy.pendingQuestCompletions = new HashMap<>(pendingQuestCompletions);
        return copy;
    }

    public void restoreFrom(PlayerProgression snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Objects.equals(playerUuid, snapshot.playerUuid)) {
            throw new IllegalArgumentException("Cannot restore progression from a different player");
        }
        questRevision = snapshot.questRevision;
        quests = new HashMap<>();
        snapshot.quests.forEach((id, state) -> quests.put(id, state.copy()));
        factionPoints = new HashMap<>(snapshot.factionPoints);
        visitedDialogueNodes = new HashSet<>(snapshot.visitedDialogueNodes);
        pendingQuestCompletions = new HashMap<>(snapshot.pendingQuestCompletions);
    }

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
