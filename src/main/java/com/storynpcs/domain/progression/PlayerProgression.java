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
    private long factionRevision;

    @JsonProperty
    private Map<NamespacedId, PendingQuestCompletion> pendingQuestCompletions = new HashMap<>();

    /**
     * Durable idempotency marks for non-atomic quest reward side effects
     * (XP or item grants). A reward is marked before it is delivered and the
     * marks are cleared when the completion commits, so a retry after a
     * failed commit cannot grant the same reward twice.
     */
    @JsonProperty
    private Map<NamespacedId, Set<String>> deliveredQuestRewards = new HashMap<>();


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

    public long getFactionRevision() { return factionRevision; }
    public void setFactionRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        this.factionRevision = revision;
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

    /** Durable per-quest reward-delivery marks used for idempotent retry after a failed commit. */
    public Map<NamespacedId, Set<String>> getDeliveredQuestRewards() {
        return deliveredQuestRewards;
    }

    public void setDeliveredQuestRewards(Map<NamespacedId, Set<String>> delivered) {
        deliveredQuestRewards = new HashMap<>();
        if (delivered == null) return;
        for (Map.Entry<NamespacedId, Set<String>> entry : delivered.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("delivered quest rewards require non-null keys and values");
            }
            deliveredQuestRewards.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    /** Deep snapshot used to restore cached progression after a failed durable write. */
    public PlayerProgression copy() {
        PlayerProgression copy = new PlayerProgression(playerUuid);
        copy.questRevision = questRevision;
        copy.factionRevision = factionRevision;
        copy.quests = new HashMap<>();
        quests.forEach((id, state) -> copy.quests.put(id, state.copy()));
        copy.factionPoints = new HashMap<>(factionPoints);
        copy.visitedDialogueNodes = new HashSet<>(visitedDialogueNodes);
        copy.pendingQuestCompletions = new HashMap<>(pendingQuestCompletions);
        copy.deliveredQuestRewards = new HashMap<>();
        deliveredQuestRewards.forEach((id, keys) -> copy.deliveredQuestRewards.put(id, new HashSet<>(keys)));
        return copy;
    }

    public void restoreFrom(PlayerProgression snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Objects.equals(playerUuid, snapshot.playerUuid)) {
            throw new IllegalArgumentException("Cannot restore progression from a different player");
        }
        questRevision = snapshot.questRevision;
        factionRevision = snapshot.factionRevision;
        quests = new HashMap<>();
        snapshot.quests.forEach((id, state) -> quests.put(id, state.copy()));
        factionPoints = new HashMap<>(snapshot.factionPoints);
        visitedDialogueNodes = new HashSet<>(snapshot.visitedDialogueNodes);
        pendingQuestCompletions = new HashMap<>(snapshot.pendingQuestCompletions);
        deliveredQuestRewards = new HashMap<>();
        snapshot.deliveredQuestRewards.forEach((id, keys) -> deliveredQuestRewards.put(id, new HashSet<>(keys)));
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
