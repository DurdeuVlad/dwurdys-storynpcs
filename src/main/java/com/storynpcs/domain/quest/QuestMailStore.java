package com.storynpcs.domain.quest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.persistence.IndexedRecordStore;

/**
 * Durable quest-mail store backed by the indexed record store — mail survives
 * crashes and restarts; claiming marks the record rather than deleting it so
 * concurrent deliveries cannot both grant the payload. Delivery is a second
 * durable mark: mail that is claimed but not delivered is a recoverable
 * crash window replayed on login — never silently lost.
 */
public final class QuestMailStore {

    private final IndexedRecordStore store;

    public QuestMailStore(Path directory, ObjectMapper mapper) {
        this.store = new IndexedRecordStore(directory, mapper);
    }

    public void open() throws IOException {
        store.open();
    }

    /** Persist a new mail entry. Idempotent on mailId — rewrite replaces the record. */
    public void enqueue(QuestMail mail) throws IOException {
        store.write(mail.mailId().toString(), mail);
    }

    public Optional<QuestMail> get(UUID mailId) throws IOException {
        return store.read(mailId.toString(), QuestMail.class);
    }

    /** Unclaimed mail for a player, oldest first — deterministic order for the UI. */
    public List<QuestMail> pendingFor(UUID playerUuid) throws IOException {
        List<QuestMail> result = new ArrayList<>();
        for (String id : store.listIds()) {
            Optional<QuestMail> mail = store.read(id, QuestMail.class);
            mail.filter(m -> m.playerUuid().equals(playerUuid) && !m.claimed()).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(QuestMail::createdTick).thenComparing(m -> m.mailId().toString()));
        return List.copyOf(result);
    }

    /**
     * Claim exactly once: the read-check-write runs as one critical section
     * under the record store's lock ({@link IndexedRecordStore#computeIfPresent}),
     * so concurrent claims on the same mail cannot both succeed — unclaimed mail
     * is marked claimed and returned; already-claimed mail returns empty.
     */
    public Optional<QuestMail> claim(UUID mailId, long tick) throws IOException {
        return store.computeIfPresent(mailId.toString(), QuestMail.class,
                mail -> mail.claimed() ? null : mail.claim(tick));
    }

    /**
     * Confirm the payload leg after a durable claim. Runs under the same
     * critical-section semantics as {@link #claim}: a claimed-but-undelivered
     * record is marked delivered and returned; anything else returns empty.
     */
    public Optional<QuestMail> confirmDelivery(UUID mailId, long tick) throws IOException {
        return store.computeIfPresent(mailId.toString(), QuestMail.class,
                mail -> mail.claimed() && !mail.delivered() ? mail.markDelivered(tick) : null);
    }

    /**
     * Mail claimed but never confirmed delivered — the crash-recovery set for
     * {@code recoverQuestMailDeliveries}. Oldest first, matching
     * {@link #pendingFor}'s deterministic order.
     */
    public List<QuestMail> claimedUndeliveredFor(UUID playerUuid) throws IOException {
        List<QuestMail> result = new ArrayList<>();
        for (String id : store.listIds()) {
            Optional<QuestMail> mail = store.read(id, QuestMail.class);
            mail.filter(m -> m.playerUuid().equals(playerUuid)
                    && m.claimed() && !m.delivered()).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(QuestMail::createdTick).thenComparing(m -> m.mailId().toString()));
        return List.copyOf(result);
    }

    public List<String> listIds() {
        return store.listIds();
    }
}
