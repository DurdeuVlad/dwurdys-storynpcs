package com.storynpcs.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.admin.RuntimeTunables;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.command.AdminParityCatalog;
import com.storynpcs.domain.command.CommandParityStatus;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.persistence.RuntimeTunablesStore;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/** P9-4: remote/admin/config parity — durable tunables, player-data scope, parity catalog. */
class P94AdminParityTest {

    @TempDir
    Path tempDir;

    // ── parity catalog (named-surface mapping) ───────────────────────────────

    @Test
    void catalogCoversEveryNamedTargetSurface() {
        var ids = AdminParityCatalog.all().stream().map(e -> e.getInventoryId()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).containsExactlyInAnyOrder(
                "target.packets.0131", "target.packets.0132", "target.packets.0133",
                "target.packets.0134", "target.packets.0135", "target.packets.0136",
                "target.gui.0017", "target.packets.0108", "target.packets.0109",
                "target.gui.0070", "target.packets.0004", "target.packets.0001",
                "target.gui.0001", "target.packets.0082", "target.packets.0083",
                "target.packets.0084");
    }

    @Test
    void supportedEntriesNameAnEquivalentAndDeviationsNameARationale() {
        for (var entry : AdminParityCatalog.all()) {
            assertThat(entry.getStatus()).isNotNull();
            if (entry.getStatus() == CommandParityStatus.SUPPORTED) {
                assertThat(entry.getStorynpcsEquivalent()).as(entry.getInventoryId()).isNotBlank();
            }
            assertThat(entry.getRationale()).as(entry.getInventoryId()).isNotBlank();
        }
    }

    // ── durable runtime tunables ─────────────────────────────────────────────

    @Test
    void tunablesRestoreSwapsInValidatedPersistedState() {
        var tunables = new RuntimeTunables();
        var result = tunables.restore(
                Map.of(RuntimeTunables.SIM_BAND_ACTIVE, "80"), 7L);
        assertThat(result.hasErrors()).isFalse();
        assertThat(tunables.longValue(RuntimeTunables.SIM_BAND_ACTIVE)).isEqualTo(80L);
        assertThat(tunables.revision()).isEqualTo(7L);
    }

    @Test
    void tunablesRestoreRejectsCorruptRecordsAndKeepsDefaults() {
        var tunables = new RuntimeTunables();
        long defaultBand = tunables.longValue(RuntimeTunables.SIM_BAND_ACTIVE);
        var result = tunables.restore(
                Map.of(RuntimeTunables.SIM_BAND_ACTIVE, "not-a-number"), 3L);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.formatReport(3)).contains("INVALID_TUNABLE_VALUE");
        assertThat(tunables.longValue(RuntimeTunables.SIM_BAND_ACTIVE)).isEqualTo(defaultBand);
        assertThat(tunables.revision()).isEqualTo(0L);
    }

    @Test
    void tunablesStoreRoundTripsAndIsAbsentBeforeFirstCommit() throws Exception {
        var store = new RuntimeTunablesStore(tempDir, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(store.load()).isEmpty();
        store.save(4L, Map.of("sim.band.active", "96"));
        var loaded = store.load().orElseThrow();
        assertThat(loaded.revision()).isEqualTo(4L);
        assertThat(loaded.values()).containsEntry("sim.band.active", "96");
    }

    @Test
    void committedConfigMutationPersistsBeforeReportingSuccess() throws Exception {
        var tunables = new RuntimeTunables();
        var service = serviceWithTunables(tunables);
        var persisted = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
        service.setRuntimeTunablesPersister((rev, snapshot) -> persisted.set(snapshot));

        var result = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "command", "config.mutate",
                NamespacedId.of("storynpcs:config"), tunables.revision(),
                UUID.randomUUID(), 2), Map.of(RuntimeTunables.SIM_BAND_ACTIVE, "80"));

        assertThat(result.applied()).isTrue();
        assertThat(persisted.get()).containsEntry(RuntimeTunables.SIM_BAND_ACTIVE, "80");
    }

    @Test
    void persistFailureReportsConfigPersistFailed() throws Exception {
        var tunables = new RuntimeTunables();
        var service = serviceWithTunables(tunables);
        service.setRuntimeTunablesPersister((rev, snapshot) -> {
            throw new java.io.IOException("disk full");
        });

        var result = service.mutateRuntimeTunables(new MutationRequest(
                "config.mutate", "command", "config.mutate",
                NamespacedId.of("storynpcs:config"), tunables.revision(),
                UUID.randomUUID(), 2), Map.of(RuntimeTunables.SIM_BAND_ACTIVE, "80"));

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport(5)).contains("CONFIG_PERSIST_FAILED");
    }

    // ── player-data administration (SELF vs ADMIN scope) ─────────────────────

    @Test
    void playerDataReadIsSelfScopedForLowPermissionActors() throws Exception {
        var service = service();
        var self = UUID.randomUUID();
        var stranger = UUID.randomUUID();

        var ownRead = service.adminReadPlayerData(new PlayerProgressionActionRequest(
                "playerdata.read", "player", self, self, UUID.randomUUID(), 0));
        assertThat(ownRead.applied()).isTrue();
        assertThat(ownRead.summary()).isNotNull();

        var crossDenied = service.adminReadPlayerData(new PlayerProgressionActionRequest(
                "playerdata.read", "command", self, stranger, UUID.randomUUID(), 0));
        assertThat(crossDenied.applied()).isFalse();
        assertThat(crossDenied.authorization().decision().code()).isEqualTo("PERMISSION_DENIED");

        var crossAdmin = service.adminReadPlayerData(new PlayerProgressionActionRequest(
                "playerdata.read", "command", self, stranger, UUID.randomUUID(), 2));
        assertThat(crossAdmin.applied()).isTrue();
    }

    @Test
    void playerDataReadReportsDetachedCounts() throws Exception {
        var service = service();
        var player = UUID.randomUUID();
        service.mutateFactionProgression(FactionProgressionMutationRequest.set(
                "command", null, player, NamespacedId.of("storynpcs:villagers"), 400,
                service.currentFactionProgressionRevision(player), UUID.randomUUID(), 2));

        var read = service.adminReadPlayerData(new PlayerProgressionActionRequest(
                "playerdata.read", "command", null, player, UUID.randomUUID(), 2));
        assertThat(read.applied()).isTrue();
        assertThat(read.summary().factionEntries()).isEqualTo(1);
    }

    @Test
    void playerDataClearDeletesTheDurableRecord() throws Exception {
        var repository = new ProgressionRepository(tempDir.resolve("progression"));
        var service = service(repository);
        var player = UUID.randomUUID();
        service.mutateFactionProgression(FactionProgressionMutationRequest.set(
                "command", null, player, NamespacedId.of("storynpcs:villagers"), 400,
                service.currentFactionProgressionRevision(player), UUID.randomUUID(), 2));
        assertThat(Files.exists(
                tempDir.resolve("progression").resolve(player + ".json"))).isTrue();

        var clear = service.adminClearPlayerData(new PlayerProgressionActionRequest(
                "playerdata.clear", "command", null, player, UUID.randomUUID(), 2));
        assertThat(clear.applied()).isTrue();
        assertThat(Files.exists(
                tempDir.resolve("progression").resolve(player + ".json"))).isFalse();
        // Post-clear read yields a fresh empty record — no resurrection.
        var read = service.adminReadPlayerData(new PlayerProgressionActionRequest(
                "playerdata.read", "command", null, player, UUID.randomUUID(), 2));
        assertThat(read.summary().factionEntries()).isEqualTo(0);
    }

    @Test
    void playerDataClearDeniedForCrossPlayerLowPermission() throws Exception {
        var service = service();
        var self = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var denied = service.adminClearPlayerData(new PlayerProgressionActionRequest(
                "playerdata.clear", "command", self, stranger, UUID.randomUUID(), 0));
        assertThat(denied.applied()).isFalse();
        assertThat(denied.decision().code()).isEqualTo("PERMISSION_DENIED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private StoryNpcsApplicationService service() throws Exception {
        return service(new ProgressionRepository(tempDir.resolve("progression")));
    }

    private StoryNpcsApplicationService service(ProgressionRepository repository) throws Exception {
        var registry = new DefinitionRegistry();
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(tempDir);
        registry.registerFaction(new com.storynpcs.domain.faction.Faction(
                NamespacedId.of("storynpcs:villagers"), "Villagers", 1000, 0, 2000));
        var service = new StoryNpcsApplicationService(registry, repository, new EventPublisher());
        service.setLoader(loader);
        return service;
    }

    private StoryNpcsApplicationService serviceWithTunables(RuntimeTunables tunables) throws Exception {
        var service = service();
        service.setRuntimeTunables(tunables);
        return service;
    }
}
