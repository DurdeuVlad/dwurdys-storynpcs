package com.storynpcs.domain.quest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.progression.QuestProgressState;

/**
 * Shared quest progression for a team. Team state is keyed by teamId — it
 * survives member reconnects and is independent of any single player's data.
 * Revision bumps let clients detect authoritative changes.
 */
public final class TeamProgression {

    @JsonProperty(required = true)
    private UUID teamId;

    @JsonProperty
    private Set<UUID> memberUuids = new LinkedHashSet<>();

    @JsonProperty
    private UUID ownerUuid;

    @JsonProperty
    private Map<String, QuestProgressState> quests = new LinkedHashMap<>();

    /**
     * Explicit claim policy (P5-5): the first member whose completion commits
     * claims the shared quest slot — the claimer is durable so a later
     * reconnect can always answer "who completed this for the team".
     */
    @JsonProperty
    private Map<String, UUID> claimedBy = new LinkedHashMap<>();

    /** Outstanding invitations — joining without one is rejected. */
    @JsonProperty
    private Set<UUID> invited = new LinkedHashSet<>();

    @JsonProperty
    private long revision;

    public TeamProgression() {}

    public TeamProgression(UUID teamId, UUID ownerUuid) {
        this.teamId = teamId;
        this.ownerUuid = ownerUuid;
        if (ownerUuid != null) {
            memberUuids.add(ownerUuid);
        }
    }

    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID teamId) { this.teamId = teamId; }

    public Set<UUID> getMemberUuids() { return Set.copyOf(memberUuids); }
    public void setMemberUuids(Set<UUID> memberUuids) {
        this.memberUuids = memberUuids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(memberUuids);
    }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public Map<String, QuestProgressState> getQuests() {
        Map<String, QuestProgressState> copy = new LinkedHashMap<>(quests);
        return copy;
    }
    public void setQuests(Map<String, QuestProgressState> quests) {
        this.quests = quests == null ? new LinkedHashMap<>() : new LinkedHashMap<>(quests);
    }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    public void addMember(UUID uuid) {
        if (uuid != null && memberUuids.add(uuid)) {
            revision++;
        }
    }

    public void removeMember(UUID uuid) {
        if (memberUuids.remove(uuid)) {
            revision++;
        }
    }

    public Map<String, UUID> getClaimedBy() {
        return new LinkedHashMap<>(claimedBy);
    }
    public void setClaimedBy(Map<String, UUID> claimedBy) {
        this.claimedBy = claimedBy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(claimedBy);
    }

    /** Team-scoped quest state write — bumps the shared revision. */
    public void putQuestState(NamespacedId questId, QuestProgressState state) {
        quests.put(questId.toString(), state);
        revision++;
    }

    /**
     * Record the member who first completed the shared quest. Returns
     * {@code true} when this call recorded the claim; {@code false} when an
     * earlier member already holds it (idempotent — a replayed completion
     * can never steal or duplicate a claim).
     */
    public boolean claim(NamespacedId questId, UUID memberUuid) {
        if (memberUuid == null || claimedBy.containsKey(questId.toString())) {
            return false;
        }
        claimedBy.put(questId.toString(), memberUuid);
        revision++;
        return true;
    }

    /** The member who claimed the shared completion, if any. */
    public java.util.Optional<UUID> claimer(NamespacedId questId) {
        return java.util.Optional.ofNullable(claimedBy.get(questId.toString()));
    }

    public Set<UUID> getInvited() { return Set.copyOf(invited); }
    public void setInvited(Set<UUID> invited) {
        this.invited = invited == null ? new LinkedHashSet<>() : new LinkedHashSet<>(invited);
    }

    public boolean invite(UUID uuid) {
        if (uuid == null || isMember(uuid) || !invited.add(uuid)) {
            return false;
        }
        revision++;
        return true;
    }

    public boolean revokeInvite(UUID uuid) {
        if (invited.remove(uuid)) {
            revision++;
            return true;
        }
        return false;
    }

    public boolean isInvited(UUID uuid) { return invited.contains(uuid); }

    /** Consumes the invitation as part of a successful join. */
    public void consumeInvite(UUID uuid) {
        if (invited.remove(uuid)) {
            revision++;
        }
    }

    public boolean isMember(UUID uuid) {
        return memberUuids.contains(uuid);
    }
}
