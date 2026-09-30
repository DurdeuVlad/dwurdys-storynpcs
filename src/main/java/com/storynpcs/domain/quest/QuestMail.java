package com.storynpcs.domain.quest;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcItemStack;

/**
 * A recoverable overflow channel for quest rewards. When a reward cannot be
 * delivered (full inventory, offline player, failed side effect), the payload
 * is persisted as mail instead of being lost — claimed later exactly once.
 *
 * <p>Claim and delivery are two distinct durable phases: {@code claimed} marks
 * intent so concurrent deliveries cannot double-grant, {@code delivered} marks
 * the payload leg complete. A record that is claimed but not delivered is a
 * crash window — login recovery replays it, so the store degrades to
 * at-least-once delivery rather than silent loss.
 */
public record QuestMail(
        @JsonProperty(required = true) UUID mailId,
        @JsonProperty(required = true) UUID playerUuid,
        @JsonProperty(required = true) NamespacedId questId,
        @JsonProperty List<NpcItemStack> items,
        @JsonProperty int experience,
        @JsonProperty(required = true) String reason,
        @JsonProperty long createdTick,
        @JsonProperty long claimedTick,
        @JsonProperty long deliveredTick) {

    public QuestMail {
        items = items == null ? List.of() : List.copyOf(items);
        if (experience < 0) {
            throw new IllegalArgumentException("experience must be >= 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
    }

    public boolean claimed() {
        return claimedTick > 0;
    }

    /** The payload leg finished granting after the durable claim. */
    public boolean delivered() {
        return deliveredTick > 0;
    }

    public QuestMail claim(long tick) {
        return new QuestMail(mailId, playerUuid, questId, items, experience, reason,
                createdTick, positiveTick(tick), deliveredTick);
    }

    public QuestMail markDelivered(long tick) {
        return new QuestMail(mailId, playerUuid, questId, items, experience, reason,
                createdTick, claimedTick, positiveTick(tick));
    }

    private static long positiveTick(long tick) {
        // Sentinel discipline: both phase flags are "any tick > 0", so a real
        // event at game tick 0 must still persist a positive marker value.
        return Math.max(1L, tick);
    }
}
