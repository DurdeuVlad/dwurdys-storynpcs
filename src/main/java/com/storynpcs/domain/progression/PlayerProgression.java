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

    /** Optimistic-concurrency clock for faction-standing mutations. */
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

    /** Durable mailbox (issue #71 — postman/mailbox role). Newest-last insertion order. */
    @JsonProperty
    private List<MailMessage> mailbox = new ArrayList<>();

    /** Unlocked transport destinations (issue #72 — transport locations). */
    @JsonProperty
    private Set<NamespacedId> unlockedTransportLocations = new HashSet<>();

    /**
     * Durable idempotency ledger for request-id-keyed mutations whose effect
     * commits in the same save as this record (issue #57 — P2-3). Value is
     * {@code fingerprint\n<outcome payload>} — the fingerprint blocks a reused
     * request id bound to different input, and the payload lets a replay after
     * restart return the recorded outcome instead of re-executing (which would
     * double-apply non-idempotent mutations like faction ADJUST). Bounded FIFO
     * so the ledger cannot grow without limit.
     */
    @JsonProperty
    private LinkedHashMap<String, String> appliedActionRequests = new LinkedHashMap<>();

    private static final int MAX_APPLIED_ACTION_REQUESTS = 512;

    /**
     * Durable companion-wage backstop (issue #57 — P2-3): companion entity UUID
     * string -> last charged wage period. The entity-owned {@code WageLedger}
     * only persists on periodic entity NBT saves, so a crash between the
     * emerald deduction and that save loses the period marker and the next
     * period boundary would double-charge. This map is force-saved on each
     * successful charge, narrowing the uncovered window to the deduction-to-
     * save gap itself. Bounded FIFO — dismissed companions' entries evict.
     */
    @JsonProperty
    private LinkedHashMap<String, Long> companionWagePeriods = new LinkedHashMap<>();

    private static final int MAX_COMPANION_WAGE_PERIODS = 256;

    /**
     * Optional shared-party membership (P5-5): the durable
     * {@link com.storynpcs.domain.quest.TeamProgressionStore} holds the team
     * record; this field is the per-player lookup so membership survives
     * reconnect without scanning every team.
     */
    @JsonProperty
    private UUID teamId;


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

    /** Shared-party membership; {@code null} means the player is in no team. */
    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID teamId) { this.teamId = teamId; }
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

    /** Unlocked transport destination IDs. */
    public Set<NamespacedId> getUnlockedTransportLocations() { return unlockedTransportLocations; }
    public void setUnlockedTransportLocations(Set<NamespacedId> unlocked) {
        this.unlockedTransportLocations = unlocked == null ? new HashSet<>() : new HashSet<>(unlocked);
    }

    /**
     * Durable request ledger — raw map for serde only; mutate via
     * {@link #recordAppliedActionRequest}.
     */
    public Map<String, String> getAppliedActionRequests() { return appliedActionRequests; }
    public void setAppliedActionRequests(Map<String, String> applied) {
        this.appliedActionRequests = new LinkedHashMap<>();
        if (applied == null) return;
        applied.forEach((id, value) -> {
            if (id == null || id.isBlank() || value == null) {
                throw new IllegalArgumentException("applied action requests require non-blank ids and non-null values");
            }
            this.appliedActionRequests.put(id, value);
        });
        while (this.appliedActionRequests.size() > MAX_APPLIED_ACTION_REQUESTS) {
            this.appliedActionRequests.remove(this.appliedActionRequests.keySet().iterator().next());
        }
    }

    /** Recorded outcome for a request id, or {@code null} when this request never committed. */
    public String appliedActionOutcome(UUID requestId) {
        return requestId == null ? null : appliedActionRequests.get(requestId.toString());
    }

    /**
     * Records a committed request outcome. Must be called inside the same
     * mutation critical section so the marker and the effect land in one
     * durable save — that atomicity is what makes replay dedup crash-safe.
     * The ledger is bounded ({@link #MAX_APPLIED_ACTION_REQUESTS}, FIFO);
     * after a marker is evicted the dedup guarantee degrades to the
     * operation's natural idempotency — faction replays are bounded by
     * monotonically increasing revisions and transport unlocks are
     * idempotent set additions, so eviction cannot double-apply effects.
     */
    public void recordAppliedActionRequest(UUID requestId, String fingerprint, String outcome) {
        if (requestId == null) throw new IllegalArgumentException("requestId cannot be null");
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint cannot be blank");
        }
        if (outcome == null) throw new IllegalArgumentException("outcome cannot be null");
        String key = requestId.toString();
        if (appliedActionRequests.size() >= MAX_APPLIED_ACTION_REQUESTS
                && !appliedActionRequests.containsKey(key)) {
            appliedActionRequests.remove(appliedActionRequests.keySet().iterator().next());
        }
        appliedActionRequests.put(key, fingerprint + "\n" + outcome);
    }

    /**
     * Durable companion-wage backstop — raw map for serde only; mutate via
     * {@link #recordCompanionWagePeriod}.
     */
    public Map<String, Long> getCompanionWagePeriods() { return companionWagePeriods; }
    public void setCompanionWagePeriods(Map<String, Long> periods) {
        this.companionWagePeriods = new LinkedHashMap<>();
        if (periods == null) return;
        periods.forEach((companionId, period) -> {
            if (companionId == null || companionId.isBlank() || period == null || period < 0) {
                throw new IllegalArgumentException("companion wage periods require non-blank ids and non-negative periods");
            }
            this.companionWagePeriods.put(companionId, period);
        });
        while (this.companionWagePeriods.size() > MAX_COMPANION_WAGE_PERIODS) {
            this.companionWagePeriods.remove(this.companionWagePeriods.keySet().iterator().next());
        }
    }

    /**
     * Records a charged wage period for one companion. Call inside the same
     * critical section as the deduction it keys so the marker and the charge
     * land in one durable save. Re-recording refreshes eviction recency —
     * actively charging companions are never evicted while dormant ones
     * (typically dismissed) age out first.
     */
    public void recordCompanionWagePeriod(UUID companionId, long period) {
        if (companionId == null) throw new IllegalArgumentException("companionId cannot be null");
        if (period < 0) throw new IllegalArgumentException("period cannot be negative");
        String key = companionId.toString();
        companionWagePeriods.remove(key); // LinkedHashMap.put does not refresh position
        if (companionWagePeriods.size() >= MAX_COMPANION_WAGE_PERIODS) {
            companionWagePeriods.remove(companionWagePeriods.keySet().iterator().next());
        }
        companionWagePeriods.put(key, period);
    }

    /** Last durably-recorded charged wage period for one companion, or {@code -1}. */
    public long chargedWagePeriod(UUID companionId) {
        Long recorded = companionId == null ? null : companionWagePeriods.get(companionId.toString());
        return recorded == null ? -1L : recorded;
    }

    /** Durable mailbox — newest-last. */
    public List<MailMessage> getMailbox() { return mailbox; }
    public void setMailbox(List<MailMessage> mailbox) {
        this.mailbox = new ArrayList<>();
        if (mailbox == null) return;
        for (MailMessage m : mailbox) {
            if (m == null || m.getId() == null) {
                throw new IllegalArgumentException("mailbox messages require a non-null id");
            }
            this.mailbox.add(m);
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
        copy.mailbox = new ArrayList<>();
        for (MailMessage m : mailbox) copy.mailbox.add(m.copy());
        copy.unlockedTransportLocations = new HashSet<>(unlockedTransportLocations);
        copy.appliedActionRequests = new LinkedHashMap<>(appliedActionRequests);
        copy.companionWagePeriods = new LinkedHashMap<>(companionWagePeriods);
        copy.teamId = teamId;
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
        mailbox = new ArrayList<>();
        for (MailMessage m : snapshot.mailbox) mailbox.add(m.copy());
        unlockedTransportLocations = new HashSet<>(snapshot.unlockedTransportLocations);
        appliedActionRequests = new LinkedHashMap<>(snapshot.appliedActionRequests);
        companionWagePeriods = new LinkedHashMap<>(snapshot.companionWagePeriods);
        teamId = snapshot.teamId;
    }

    public QuestProgressState getQuestState(NamespacedId questId) {
        return quests.computeIfAbsent(questId, QuestProgressState::new);
    }

    /**
     * Non-mutating variant of {@link #getQuestState(NamespacedId)} for read-only
     * paths (condition evaluation, views). Returns a detached default state for
     * an unstarted quest instead of inserting one into the persisted map.
     */
    public QuestProgressState peekQuestState(NamespacedId questId) {
        QuestProgressState state = quests.get(questId);
        return state != null ? state : new QuestProgressState(questId);
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

    /**
     * Dialogue-scoped visit tracking: two dialogues may share a node id (e.g.
     * every graph conventionally has a {@code "start"} entry), so visits are
     * keyed {@code dialogueId#nodeId} to keep per-dialogue state distinct.
     * Bare-node-id entries written by older builds remain in the set and stay
     * readable through the unscoped accessors above.
     */
    public void recordDialogueNodeVisit(NamespacedId dialogueId, String nodeId) {
        visitedDialogueNodes.add(dialogueId + "#" + nodeId);
    }

    public boolean hasVisitedDialogueNode(NamespacedId dialogueId, String nodeId) {
        return visitedDialogueNodes.contains(dialogueId + "#" + nodeId);
    }
}
