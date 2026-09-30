package com.storynpcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.FactionReputationChangeEvent;
import com.storynpcs.api.event.StoryNpcsEvent;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.job.JobConfig;
import com.storynpcs.domain.job.JobType;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcStats;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestMail;
import com.storynpcs.domain.quest.QuestMailStore;
import com.storynpcs.domain.role.trader.TradeListing;
import com.storynpcs.domain.role.trader.TraderRole;
import com.storynpcs.sim.SquadCoordinator;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/**
 * Regression coverage for the adversarial-review fixes: quest-mail two-phase
 * claim/delivery + tick-0 sentinel, write-path validation parity with the
 * loader (job/listing/quest dependencies), squad assignment pruning, the
 * wired TemplateLibrary delete outcome, and the ranged opt-in flag.
 */
class ReviewFixesTest {

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private StoryNpcsApplicationService service;
    private EventPublisher events;
    private final List<StoryNpcsEvent> published = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        registry = new DefinitionRegistry();
        var progressionRepository = new ProgressionRepository(tempDir.resolve("progression"));
        events = new EventPublisher();
        events.register(published::add);
        service = new StoryNpcsApplicationService(registry, progressionRepository, events);
        Path defsDir = tempDir.resolve("definitions");
        Files.createDirectories(defsDir);
        var loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(defsDir);
        service.setLoader(loader);
    }

    // ── quest mail: tick-0 sentinel + two-phase claim/delivery ─────────────

    @Test
    void questMail_claimAtTickZero_staysClaimed() {
        var mail = new QuestMail(UUID.randomUUID(), UUID.randomUUID(),
                NamespacedId.of("storynpcs:reward"), List.of(), 0, "INVENTORY_FULL", 0, 0, 0);

        var claimed = mail.claim(0);

        assertThat(claimed.claimed()).isTrue();
        assertThat(claimed.claimedTick()).isEqualTo(1); // positive sentinel, not raw 0
        assertThat(claimed.delivered()).isFalse();
    }

    @Test
    void questMailStore_claimAtTickZero_isExactlyOnce() throws IOException {
        var store = new QuestMailStore(tempDir.resolve("mail"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.open();
        UUID player = UUID.randomUUID();
        var mail = new QuestMail(UUID.randomUUID(), player,
                NamespacedId.of("storynpcs:reward"), List.of(), 25, "INVENTORY_FULL", 0, 0, 0);
        store.enqueue(mail);

        // Game tick 0 is a legitimate claim tick — the record must still be claimed.
        assertThat(store.claim(mail.mailId(), 0)).isPresent();
        assertThat(store.claim(mail.mailId(), 0)).isEmpty();
        assertThat(store.pendingFor(player)).isEmpty();
    }

    @Test
    void questMailStore_claimedUndelivered_isTheCrashRecoverySet() throws IOException {
        var store = new QuestMailStore(tempDir.resolve("mail"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.open();
        UUID player = UUID.randomUUID();
        var mail = new QuestMail(UUID.randomUUID(), player,
                NamespacedId.of("storynpcs:reward"), List.of(), 25, "INVENTORY_FULL", 0, 0, 0);
        store.enqueue(mail);

        // Unclaimed mail is not in the recovery set.
        assertThat(store.claimedUndeliveredFor(player)).isEmpty();

        store.claim(mail.mailId(), 100);
        // Claimed but not delivered → stranded, recoverable.
        assertThat(store.claimedUndeliveredFor(player)).hasSize(1);
        assertThat(store.pendingFor(player)).isEmpty();

        // confirmDelivery completes the two-phase leg.
        assertThat(store.confirmDelivery(mail.mailId(), 200)).isPresent();
        assertThat(store.claimedUndeliveredFor(player)).isEmpty();
        // A second confirm is refused — delivery is once-only.
        assertThat(store.confirmDelivery(mail.mailId(), 300)).isEmpty();
    }

    // ── write-path validation parity with the loader ───────────────────────

    @Test
    void saveNpc_rejectsJobConfigTheLoaderWouldReject() {
        var npc = new NpcDefinition();
        npc.setId(NamespacedId.of("storynpcs:bad_spawner"));
        var job = new JobConfig();
        job.setType(JobType.SPAWNER); // requires spawnDefinitionId
        npc.setJob(job);

        var result = service.saveNpc(npc);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().toString()).contains("JOB_CONFIG_INVALID");
        assertThat(registry.getNpc(npc.getId())).isEmpty(); // nothing registered
    }

    @Test
    void saveNpc_rejectsTradeListingTheLoaderWouldReject() {
        var npc = new NpcDefinition();
        npc.setId(NamespacedId.of("storynpcs:bad_trader"));
        var trader = new TraderRole();
        var listing = new TradeListing();
        listing.setOfferItemId("minecraft:diamond");
        listing.setOfferCount(1);
        listing.setPriceItemId("minecraft:emerald");
        listing.setPriceCount(0); // invalid — loader rejects count < 1
        trader.setListings(List.of(listing));
        npc.setTrader(trader);

        var result = service.saveNpc(npc);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().toString()).contains("TRADE_LISTING_INVALID");
        assertThat(registry.getNpc(npc.getId())).isEmpty();
    }

    @Test
    void saveNpc_acceptsValidJobAndListing() {
        var npc = new NpcDefinition();
        npc.setId(NamespacedId.of("storynpcs:guard_npc"));
        var job = new JobConfig();
        job.setType(JobType.GUARD);
        npc.setJob(job);

        var result = service.saveNpc(npc);

        assertThat(result.hasErrors()).isFalse();
        assertThat(registry.getNpc(npc.getId())).isPresent();
    }

    private static Quest validQuest(String name, List<NamespacedId> prerequisites) {
        var quest = new Quest(NamespacedId.of("storynpcs", name), name);
        quest.setObjectives(List.of(new com.storynpcs.domain.quest.QuestObjective(
                "obj", com.storynpcs.domain.quest.QuestObjective.Type.CUSTOM, "any", 1)));
        quest.setPrerequisites(prerequisites);
        return quest;
    }

    @Test
    void saveQuest_rejectsPrerequisiteCyclesTheLoaderRejects() {
        var a = validQuest("quest_a", List.of(NamespacedId.of("storynpcs:quest_b")));
        assertThat(service.saveQuest(a).hasErrors()).isTrue(); // b missing
        assertThat(registry.getQuest(a.getId())).isEmpty();

        assertThat(service.saveQuest(validQuest("quest_b", List.of())).hasErrors()).isFalse();

        // Now a's prereq resolves and a can save — but pointing b back at a
        // must create a cycle the write path refuses.
        assertThat(service.saveQuest(a).hasErrors()).isFalse();
        var bCycle = validQuest("quest_b", List.of(NamespacedId.of("storynpcs:quest_a")));
        assertThat(service.saveQuest(bCycle).hasErrors()).isTrue();
    }

    // ── squad coordinator: stale assignments released ──────────────────────

    @Test
    void squadCoordinator_releasesAssignmentsForDepartedActors() {
        var coordinator = new SquadCoordinator();
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID target1 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();

        coordinator.coordinate(Set.of(actorA, actorB), Set.of(target1, target2), id -> 1.0);
        assertThat(coordinator.assignments()).hasSize(2);

        // actorB leaves the squad (despawned) — its assignment must be released
        // rather than lingering in the map forever.
        coordinator.coordinate(Set.of(actorA), Set.of(target1), id -> 1.0);
        assertThat(coordinator.assignments()).containsOnlyKeys(actorA);
    }

    // ── template library: live registry wiring ─────────────────────────────

    @Test
    void registryTemplate_deleteSurfacesDependentSpawners() {
        var templateId = NamespacedId.of("storynpcs:guard_template");
        var spawnerId = NamespacedId.of("storynpcs:east_gate_spawner");
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(templateId);
        registry.registerTemplate(template);
        registry.registerTemplateSpawnerDependent(templateId, spawnerId);

        var outcome = registry.removeTemplate(templateId);

        assertThat(outcome.removed()).isTrue();
        assertThat(outcome.dependentSpawners()).containsExactly(spawnerId);
        assertThat(registry.getTemplate(templateId)).isEmpty();
        // Dependent bookkeeping is wiped with the template.
        assertThat(registry.templateSpawnerDependents(templateId)).isEmpty();
    }

    @Test
    void registryTemplate_searchDelegatesToLibrary() {
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(NamespacedId.of("storynpcs:guard_template"));
        template.setDescription("elite gate guard");
        registry.registerTemplate(template);

        assertThat(registry.searchTemplates("guard")).containsExactly(template.getId());
        assertThat(registry.searchTemplates("nonexistent")).isEmpty();
    }

    // ── ranged schema: opt-in gate ─────────────────────────────────────────

    @Test
    void ranged_disabledByDefault_optInArms() {
        var ranged = new NpcStats.Ranged();
        assertThat(ranged.isEnabled()).isFalse(); // default block must not arm every NPC
        ranged.setEnabled(true);
        assertThat(ranged.isEnabled()).isTrue();
    }

    // ── canonical boundary: faction reputation adapter ─────────────────────

    @Test
    void adjustFactionReputation_routesThroughCanonicalBoundary() {
        var factionId = NamespacedId.of("storynpcs:guards");
        assertThat(service.saveFaction(new Faction(factionId, "Guards", 1000, 500, 1500)).hasErrors())
                .isFalse();
        UUID player = UUID.randomUUID();

        assertThat(service.currentFactionProgressionRevision(player)).isEqualTo(0);
        service.adjustFactionReputation(player, factionId, -250);

        // Canonical mutation bumped the persisted faction revision — the old
        // bypass path adjusted the score without advancing it.
        assertThat(service.currentFactionProgressionRevision(player)).isEqualTo(1);
        var reputationEvents = published.stream()
                .filter(FactionReputationChangeEvent.class::isInstance)
                .map(FactionReputationChangeEvent.class::cast).toList();
        assertThat(reputationEvents).hasSize(1);
        assertThat(reputationEvents.get(0).newPoints()).isEqualTo(750);

        // A rule referencing a missing faction fails closed instead of writing
        // a score entry for it.
        assertThatThrownBy(() -> service.adjustFactionReputation(
                player, NamespacedId.of("storynpcs:missing"), 1))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── canonical boundary: faction reference repair ───────────────────────

    private Faction savedFaction(String path) {
        var faction = new Faction(NamespacedId.of("storynpcs:" + path), path, 0, -500, 500);
        assertThat(service.saveFaction(faction).hasErrors()).isFalse();
        return faction;
    }

    @Test
    void deleteFactionWithRepairs_repairsViaDetachedCopies() {
        var doomedId = NamespacedId.of("storynpcs:doomed");
        var safeId = NamespacedId.of("storynpcs:safe");
        var otherId = NamespacedId.of("storynpcs:other");
        savedFaction("doomed");
        savedFaction("safe");
        var other = savedFaction("other");
        other.setRelationship(doomedId, "HOSTILE");
        assertThat(service.saveFaction(other).hasErrors()).isFalse();

        var npcId = NamespacedId.of("storynpcs:citizen");
        var npc = new NpcDefinition(npcId, "Citizen");
        npc.setFactionId(doomedId);
        assertThat(service.saveNpc(npc).hasErrors()).isFalse();

        // Hold references to the live objects — the repair must commit detached
        // copies, never mutate these in place.
        var liveNpc = registry.getNpc(npcId).orElseThrow();
        var liveOther = registry.getFaction(otherId).orElseThrow();
        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", doomedId, service.currentRevision("faction", doomedId),
                UUID.randomUUID(), 2);

        var result = service.deleteFactionWithRepairs(request, safeId);

        assertThat(result.applied()).isTrue();
        assertThat(registry.getFaction(doomedId)).isEmpty();
        // Registry now serves repaired definitions...
        assertThat(registry.getNpc(npcId).orElseThrow().getFactionId()).isEqualTo(safeId);
        assertThat(registry.getFaction(otherId).orElseThrow().getRelationships())
                .doesNotContainKey(doomedId.toString());
        // ...while the previously live objects were never mutated.
        assertThat(liveNpc.getFactionId()).isEqualTo(doomedId);
        assertThat(liveOther.getRelationships()).containsKey(doomedId.toString());
        assertThat(registry.getNpc(npcId).orElseThrow()).isNotSameAs(liveNpc);
        assertThat(registry.getFaction(otherId).orElseThrow()).isNotSameAs(liveOther);
    }

    @Test
    void factionDeleteFailureRestoresEveryReferenceRepair() throws IOException {
        var doomedId = NamespacedId.of("storynpcs:doomed_atomic");
        var safeId = NamespacedId.of("storynpcs:safe_atomic");
        var otherId = NamespacedId.of("storynpcs:other_atomic");
        savedFaction("doomed_atomic");
        savedFaction("safe_atomic");
        var other = savedFaction("other_atomic");
        other.setRelationship(doomedId, "HOSTILE");
        assertThat(service.saveFaction(other).hasErrors()).isFalse();
        var npc = new NpcDefinition(NamespacedId.of("storynpcs:citizen_atomic"), "Citizen");
        npc.setFactionId(doomedId);
        assertThat(service.saveNpc(npc).hasErrors()).isFalse();

        Path failingRoot = tempDir.resolve("delete-failure-root");
        Files.createDirectories(failingRoot);
        var failingLoader = new YamlDefinitionLoader(registry) {
            @Override
            public synchronized boolean deleteDefinitionFile(String type, NamespacedId id) throws IOException {
                if ("faction".equals(type) && doomedId.equals(id)) {
                    throw new IOException("injected faction delete failure");
                }
                return super.deleteDefinitionFile(type, id);
            }
        };
        failingLoader.loadDirectory(failingRoot);
        service.setLoader(failingLoader);
        var request = new com.storynpcs.service.MutationRequest(
                "faction.delete", "command", "faction.delete", doomedId,
                service.currentRevision("faction", doomedId), UUID.randomUUID(), 2);

        var result = service.deleteFactionWithRepairs(request, safeId);

        assertThat(result.applied()).isFalse();
        assertThat(registry.getFaction(doomedId)).isPresent();
        assertThat(registry.getNpc(npc.getId()).orElseThrow().getFactionId()).isEqualTo(doomedId);
        assertThat(registry.getFaction(otherId).orElseThrow().getRelationships())
                .containsEntry(doomedId.toString(), "HOSTILE");
    }

    @Test
    void deleteFactionWithoutFallbackRejectsNpcReferences() {
        var doomedId = NamespacedId.of("storynpcs:doomed");
        savedFaction("doomed");
        var npcId = NamespacedId.of("storynpcs:citizen");
        var npc = new NpcDefinition(npcId, "Citizen");
        npc.setFactionId(doomedId);
        assertThat(service.saveNpc(npc).hasErrors()).isFalse();

        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", doomedId, service.currentRevision("faction", doomedId),
                UUID.randomUUID(), 2);
        var result = service.deleteFaction(request);
        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("fallback");
        // Rejected before any mutation — live state untouched.
        assertThat(registry.getNpc(npcId).orElseThrow().getFactionId()).isEqualTo(doomedId);
    }

    // ── canonical boundary: template deletion + import sink ────────────────

    @Test
    void deleteTemplate_fileThenRegistry_surfacesDependents() {
        var templateId = NamespacedId.of("storynpcs:t_del");
        var spawnerId = NamespacedId.of("storynpcs:gate_spawner");
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(templateId);
        template.setDefinition(new NpcDefinition(NamespacedId.of("storynpcs:t_inner"), "Inner"));
        assertThat(service.saveTemplate(template).hasErrors()).isFalse();
        registry.registerTemplateSpawnerDependent(templateId, spawnerId);
        Path templateDir = tempDir.resolve("definitions").resolve("templates");
        assertThat(templateDir).isDirectoryContaining(p -> p.toString().contains("t_del"));

        var outcome = service.deleteTemplate(templateId);

        assertThat(outcome.removed()).isTrue();
        assertThat(outcome.dependentSpawners()).containsExactly(spawnerId);
        assertThat(registry.getTemplate(templateId)).isEmpty();
        // Durable source removed alongside the registry binding.
        assertThat(templateDir).isDirectoryNotContaining(p -> p.toString().contains("t_del"));
        // A second delete reports not-removed instead of throwing or duplicating.
        assertThat(service.deleteTemplate(templateId).removed()).isFalse();
    }

    @Test
    void importSink_snapshotDetached_deleteTemplateCanonical() {
        var sink = new com.storynpcs.migration.RegistryImportSink(service, registry);
        var npcId = NamespacedId.of("storynpcs:snap_npc");
        var npc = new NpcDefinition(npcId, "Snap");
        assertThat(service.saveNpc(npc).hasErrors()).isFalse();

        var snapshot = (NpcDefinition) sink.snapshot("npc", npcId);
        assertThat(snapshot).isNotSameAs(registry.getNpc(npcId).orElseThrow());
        assertThat(snapshot.getId()).isEqualTo(npcId);

        var templateId = NamespacedId.of("storynpcs:snap_t");
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(templateId);
        template.setDefinition(new NpcDefinition(NamespacedId.of("storynpcs:snap_inner"), "I"));
        assertThat(service.saveTemplate(template).hasErrors()).isFalse();

        sink.deleteTemplate(templateId);
        assertThat(registry.getTemplate(templateId)).isEmpty();
        assertThatThrownBy(() -> sink.deleteTemplate(templateId))
                .isInstanceOf(IllegalStateException.class);
    }
}
