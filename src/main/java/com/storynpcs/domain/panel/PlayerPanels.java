package com.storynpcs.domain.panel;

import java.util.List;

/**
 * Server-issued player-panel view models (issue #150). These records are the
 * serialized contract pushed to the client — the client renders them verbatim
 * and never mutates them. All fields are plain data so Jackson round-trips
 * them through the panel payload's JSON channel.
 *
 * <p>Panels: {@link QuestLogView}, {@link FactionPanelView}, {@link MailView},
 * {@link TransportView} — the four surfaces backed by live progression/state
 * today (per-screen AC). Stale views are harmless by construction: the server
 * re-authorizes every committed action against the current session and live
 * progression state, and a panel/role switch rotates the session token, which
 * fails the old screen's commits closed.
 */
public final class PlayerPanels {

    /** Quest log entry: definition title resolved server-side + durable status. */
    public record QuestRow(String questId, String title, String status,
                           List<String> objectives, boolean pendingCompletion) {}

    /** Pending-completion intent row (P5-5 recovered outcome surfacing). */
    public record PendingQuestRow(String questId, long stateRevision) {}

    public record QuestLogView(List<QuestRow> quests, List<PendingQuestRow> pending,
                               long revision) {}

    /** Faction standing row: authored thresholds classify the player's points. */
    public record FactionRow(String factionId, String name, int points,
                             String standing, int color) {}

    public record FactionPanelView(List<FactionRow> factions, long revision) {}

    /** Mailbox row — full body included (mailbox is capacity-bounded). */
    public record MailRow(String id, String sender, String subject, String body,
                          long deliveredAtEpochMillis, boolean read) {}

    public record MailView(List<MailRow> messages) {}

    /** Transport destination row — only destinations visible to the player. */
    public record TransportRow(String id, String name, String dimensionId,
                               double x, double y, double z, int fee,
                               boolean unlocked) {}

    public record TransportView(List<TransportRow> destinations) {}

    /** Companion row (GuiNpcCompanion* parity): read view of a hired NPC. */
    public record CompanionRow(String entityUuid, String name,
                               double health, double maxHealth,
                               String stageId, int talentCount, int carryCapacity,
                               boolean paused) {}

    public record CompanionView(List<CompanionRow> companions) {}

    /** Follower hire candidate (GuiNpcFollowerHire parity). */
    public record HireRow(String entityUuid, String name,
                          int wageAmount, int wageIntervalTicks) {}

    public record HireView(List<HireRow> candidates) {}

    /**
     * Achievement row (GuiAchievement equivalent — the target's achievement
     * page has no vanilla-achievement counterpart, so StoryNPCs surfaces
     * earned progress: completed quests, standings, hired companions).
     */
    public record AchievementRow(String id, String title, String detail) {}

    public record AchievementView(List<AchievementRow> rows) {}

    /** Carpentry bench row (GuiNpcCarpentryBench parity): recipe + craft commit. */
    public record CarpentryRow(String id, String group, String outputItemId,
                               int outputCount, boolean shapeless,
                               List<String> ingredientSummary) {}

    public record CarpentryView(List<CarpentryRow> recipes) {}

    private PlayerPanels() {}
}
