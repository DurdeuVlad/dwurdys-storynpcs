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

    /** Team-scoped quest state write — bumps the shared revision. */
    public void putQuestState(NamespacedId questId, QuestProgressState state) {
        quests.put(questId.toString(), state);
        revision++;
    }

    public boolean isMember(UUID uuid) {
        return memberUuids.contains(uuid);
    }
}
