package com.storynpcs.domain.quest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.persistence.IndexedRecordStore;

/**
 * Durable team-progression store (P5-5): one record per team keyed by
 * teamId, written atomically through {@link IndexedRecordStore}. Team
 * membership, ownership, and shared quest states survive crashes and member
 * reconnects — the store is the source of truth; membership is never held
 * only in memory.
 */
public final class TeamProgressionStore {

    private final IndexedRecordStore store;

    public TeamProgressionStore(Path directory, ObjectMapper mapper) {
        this.store = new IndexedRecordStore(directory, mapper);
    }

    public void open() throws IOException {
        store.open();
    }

    /** Persist a team record. Idempotent on teamId — rewrite replaces. */
    public void save(TeamProgression team) throws IOException {
        store.write(team.getTeamId().toString(), team);
    }

    public Optional<TeamProgression> get(UUID teamId) throws IOException {
        return store.read(teamId.toString(), TeamProgression.class);
    }

    /** Delete a team record — used when the last member leaves. */
    public void delete(UUID teamId) throws IOException {
        store.delete(teamId.toString());
    }

    /** The single team a player belongs to, if any. */
    public Optional<TeamProgression> findTeamFor(UUID memberUuid) throws IOException {
        for (String id : store.listIds()) {
            Optional<TeamProgression> team = store.read(id, TeamProgression.class);
            if (team.isPresent() && team.get().isMember(memberUuid)) {
                return team;
            }
        }
        return Optional.empty();
    }

    public List<TeamProgression> listAll() throws IOException {
        List<TeamProgression> result = new ArrayList<>();
        for (String id : store.listIds()) {
            store.read(id, TeamProgression.class).ifPresent(result::add);
        }
        return List.copyOf(result);
    }
}
