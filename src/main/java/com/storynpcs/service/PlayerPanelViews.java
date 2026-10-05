package com.storynpcs.service;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.panel.PlayerPanels.FactionPanelView;
import com.storynpcs.domain.panel.PlayerPanels.FactionRow;
import com.storynpcs.domain.panel.PlayerPanels.MailRow;
import com.storynpcs.domain.panel.PlayerPanels.MailView;
import com.storynpcs.domain.panel.PlayerPanels.PendingQuestRow;
import com.storynpcs.domain.panel.PlayerPanels.QuestLogView;
import com.storynpcs.domain.panel.PlayerPanels.QuestRow;
import com.storynpcs.domain.panel.PlayerPanels.TransportRow;
import com.storynpcs.domain.panel.PlayerPanels.TransportView;
import com.storynpcs.domain.progression.PlayerProgression;
import com.storynpcs.domain.progression.QuestProgressState;
import com.storynpcs.domain.transport.TransportLocation;
import com.storynpcs.yaml.DefinitionRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side builders for the player-panel views (issue #150). Every row is
 * resolved here — quest titles come from the definition registry, standings
 * from authored faction thresholds, mailbox rows from the durable progression
 * record — so the client renders server truth and cannot fabricate contents.
 * Views carry a revision so a stale screen can fail closed.
 */
public final class PlayerPanelViews {

    /** Panel ids used by the payload discriminator and session kind. */
    public static final String PANEL_QUEST_LOG = "quest_log";
    public static final String PANEL_FACTIONS = "factions";
    public static final String PANEL_MAIL = "mail";
    public static final String PANEL_TRANSPORT = "transport";

    public static final Set<String> PANEL_IDS =
            Set.of(PANEL_QUEST_LOG, PANEL_FACTIONS, PANEL_MAIL, PANEL_TRANSPORT);

    /**
     * Quest log (GuiQuestLog parity): every quest the player has state for,
     * sorted by status then title; pending completions surface separately so a
     * recovered/replayable intent is visible instead of a stuck IN_PROGRESS row.
     */
    public static QuestLogView questLog(DefinitionRegistry registry,
                                        PlayerProgression progression) {
        Objects.requireNonNull(progression, "progression");
        // Snapshot every mutable field under the progression monitor —
        // getObjectiveCounts() returns the live inner map, so copying only the
        // outer map would still race a concurrent increment.
        Map<NamespacedId, QuestProgressState> states;
        Map<NamespacedId, com.storynpcs.domain.progression.PendingQuestCompletion> pending;
        Map<NamespacedId, List<String>> objectiveLines = new java.util.HashMap<>();
        Map<NamespacedId, QuestProgressState.Status> statuses = new java.util.HashMap<>();
        synchronized (progression) {
            states = Map.copyOf(progression.getQuests());
            pending = Map.copyOf(progression.getPendingQuestCompletions());
            for (Map.Entry<NamespacedId, QuestProgressState> e : states.entrySet()) {
                var state = e.getValue();
                if (state == null) continue;
                statuses.put(e.getKey(), state.getStatus());
                var counts = state.getObjectiveCounts();
                objectiveLines.put(e.getKey(), counts == null ? List.of()
                        : counts.entrySet().stream()
                                .map(o -> o.getKey() + ": " + o.getValue())
                                .sorted()
                                .toList());
            }
        }
        List<QuestRow> rows = new ArrayList<>();
        for (Map.Entry<NamespacedId, QuestProgressState> e : states.entrySet()) {
            var status = statuses.get(e.getKey());
            if (status == null || status == QuestProgressState.Status.NOT_STARTED) {
                continue;
            }
            String title = registry != null
                    ? registry.getQuest(e.getKey()).map(q -> q.getTitle()).orElse(e.getKey().toString())
                    : e.getKey().toString();
            rows.add(new QuestRow(e.getKey().toString(), title,
                    status.name(), objectiveLines.getOrDefault(e.getKey(), List.of()),
                    pending.containsKey(e.getKey())));
        }
        rows.sort(Comparator.comparing(QuestRow::status).thenComparing(QuestRow::title));
        List<PendingQuestRow> pendingRows = pending.entrySet().stream()
                .map(e -> new PendingQuestRow(e.getKey().toString(),
                        e.getValue() == null ? 0L : e.getValue().questStateRevision()))
                .sorted(Comparator.comparing(PendingQuestRow::questId))
                .toList();
        return new QuestLogView(rows, pendingRows, progression.getQuestRevision());
    }

    /**
     * Faction panel (InventoryTabFactions parity): every authored faction with
     * the player's points and the standing the authored thresholds produce.
     */
    public static FactionPanelView factionPanel(DefinitionRegistry registry,
                                                PlayerProgression progression) {
        Objects.requireNonNull(progression, "progression");
        Map<NamespacedId, Integer> points;
        synchronized (progression) {
            points = Map.copyOf(progression.getFactionPoints());
        }
        List<FactionRow> rows = new ArrayList<>();
        if (registry != null) {
            for (Faction faction : registry.getAllFactions()) {
                int pts = points.getOrDefault(faction.getId(), faction.getDefaultPoints());
                rows.add(new FactionRow(faction.getId().toString(), faction.getName(),
                        pts, faction.getStandingForPoints(pts).name(), faction.getColor()));
            }
        }
        rows.sort(Comparator.comparing(FactionRow::name));
        return new FactionPanelView(rows, progression.getFactionRevision());
    }

    /** Mailbox view — newest first (the durable list is newest-last). */
    public static MailView mail(
            List<com.storynpcs.domain.progression.MailMessage> mailbox) {
        List<MailRow> rows = (mailbox == null ? List.<com.storynpcs.domain.progression.MailMessage>of() : mailbox)
                .stream()
                .map(m -> new MailRow(m.getId().toString(), m.getSender(), m.getSubject(),
                        m.getBody(), m.getDeliveredAtEpochMillis(), m.isRead()))
                .collect(Collectors.toCollection(ArrayList::new));
        java.util.Collections.reverse(rows); // newest first for display
        return new MailView(List.copyOf(rows));
    }

    /** Transport picker (GuiTransportSelection parity): visible destinations with the unlocked flag resolved server-side. */
    public static TransportView transport(List<TransportLocation> visible,
                                          Set<NamespacedId> unlocked) {
        List<TransportRow> rows = (visible == null ? List.<TransportLocation>of() : visible)
                .stream()
                .map(l -> new TransportRow(l.getId().toString(), l.getName(),
                        l.getDimensionId() == null ? "" : l.getDimensionId().toString(),
                        l.getX(), l.getY(), l.getZ(), l.getFee(),
                        unlocked != null && unlocked.contains(l.getId())))
                .sorted(Comparator.comparing(TransportRow::name))
                .toList();
        return new TransportView(rows);
    }

    private PlayerPanelViews() {}
}
