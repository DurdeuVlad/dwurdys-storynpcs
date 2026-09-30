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
        other.setRelationshipTo(doomedId, com.storynpcs.domain.faction.FactionStanding.HOSTILE);
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
                .doesNotContainKey(doomedId);
        // ...while the previously live objects were never mutated.
        assertThat(liveNpc.getFactionId()).isEqualTo(doomedId);
        assertThat(liveOther.getRelationships()).containsKey(doomedId);
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
        other.setRelationshipTo(doomedId, com.storynpcs.domain.faction.FactionStanding.HOSTILE);
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
                .containsEntry(doomedId, com.storynpcs.domain.faction.FactionStanding.HOSTILE);
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

    @Test
    void factionDeletionBlocksNpcAiTargetFactionReferences() {
        var doomedId = NamespacedId.of("storynpcs:doomed_ai_target");
        var fallbackId = NamespacedId.of("storynpcs:fallback_ai_target");
        savedFaction("doomed_ai_target");
        savedFaction("fallback_ai_target");

        var npc = new NpcDefinition(NamespacedId.of("storynpcs:ai_target_ref_npc"), "AI target ref");
        npc.getAi().setAttackOnSight(true);
        npc.getAi().setTargetFactionIds(Set.of(doomedId));
        assertThat(service.saveNpc(npc).hasErrors()).isFalse();

        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", doomedId, service.currentRevision("faction", doomedId),
                UUID.randomUUID(), 2);
        var result = service.deleteFactionWithRepairs(request, fallbackId);

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains(npc.getId().toString(), "npc-ai-target-faction");
        assertThat(registry.getFaction(doomedId)).isPresent();
        assertThat(registry.getNpc(npc.getId()).orElseThrow().getAi().getTargetFactionIds())
                .contains(doomedId);
    }

    @Test
    void factionDeletionBlocksUnrewritableReferencesAcrossDefinitions() {
        var doomedId = NamespacedId.of("storynpcs:doomed_external_refs");
        var fallbackId = NamespacedId.of("storynpcs:fallback_external_refs");
        savedFaction("doomed_external_refs");
        savedFaction("fallback_external_refs");

        var npc = new NpcDefinition(NamespacedId.of("storynpcs:trade_ref_npc"), "Trade ref");
        npc.setFactionId(doomedId);
        var listing = new TradeListing("minecraft:bread", 1, "minecraft:wheat", 2);
        listing.setRequiredFaction(doomedId);
        var trader = new TraderRole();
        trader.setListings(List.of(listing));
        npc.setTrader(trader);
        var behavior = new com.storynpcs.domain.rule.BehaviorRule(
                "faction-ref", com.storynpcs.domain.rule.TriggerType.ON_DAMAGED);
        behavior.addCondition(new com.storynpcs.domain.rule.condition.CompositeCondition(
                com.storynpcs.domain.rule.condition.CompositeCondition.Mode.AND,
                List.of(new com.storynpcs.domain.rule.condition.FactionStandingCondition(
                        doomedId, com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.FRIENDLY))));
        behavior.addAction(new com.storynpcs.domain.rule.action.AdjustFactionAction(doomedId, 5));
        npc.setRules(List.of(behavior));
        registry.registerNpc(npc);

        var dialogueId = NamespacedId.of("storynpcs:dialogue_ref");
        var dialogue = new com.storynpcs.domain.dialogue.DialogueGraph(dialogueId, "Faction ref", "start");
        var start = new com.storynpcs.domain.dialogue.DialogueNode("start", "Start");
        var edge = new com.storynpcs.domain.dialogue.DialogueEdge("Continue", "end");
        edge.setConditions(List.of(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_POINTS,
                doomedId.toString(), ">=", "1")));
        edge.setActions(List.of(new com.storynpcs.domain.dialogue.DialogueAction(
                com.storynpcs.domain.dialogue.DialogueAction.Type.ADJUST_FACTION,
                doomedId.toString(), "1")));
        start.addOption(edge);
        dialogue.addNode(start);
        dialogue.addNode(new com.storynpcs.domain.dialogue.DialogueNode("end", "End"));
        registry.registerDialogue(dialogue);

        var quest = new Quest(NamespacedId.of("storynpcs:quest_ref"), "Quest ref");
        quest.setRewards(List.of(new com.storynpcs.domain.quest.QuestReward(
                com.storynpcs.domain.quest.QuestReward.Type.FACTION_POINTS, doomedId.toString(), 10)));
        registry.registerQuest(quest);

        var transport = new com.storynpcs.domain.transport.TransportLocation();
        transport.setId(NamespacedId.of("storynpcs:transport_ref"));
        transport.setName("Transport ref");
        transport.setDimensionId(NamespacedId.of("minecraft:overworld"));
        transport.setUnlockConditions(List.of(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_POINTS,
                doomedId.toString(), ">=", "1")));
        registry.registerTransportLocation(transport);

        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", doomedId, service.currentRevision("faction", doomedId),
                UUID.randomUUID(), 2);
        var result = service.deleteFactionWithRepairs(request, fallbackId);

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport())
                .contains("trade_ref_npc", "rule 0 condition", "dialogue_ref", "quest_ref", "transport_ref");
        assertThat(registry.getFaction(doomedId)).isPresent();
        assertThat(registry.getNpc(npc.getId()).orElseThrow().getFactionId()).isEqualTo(doomedId);
        assertThat(service.deleteFaction(doomedId)).isFalse();
        assertThat(registry.getFaction(doomedId)).isPresent();
    }

    @Test
    void factionDeletionBlocksTemplateEmbeddedNpcFactionReferences() {
        var factionId = NamespacedId.of("storynpcs:template_faction_ref");
        var templateId = NamespacedId.of("storynpcs:template_with_faction_ref");
        savedFaction("template_faction_ref");
        var embeddedNpc = new NpcDefinition(NamespacedId.of("storynpcs:template_npc"), "Template NPC");
        embeddedNpc.setFactionId(factionId);
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(templateId);
        template.setDefinition(embeddedNpc);
        assertThat(service.saveTemplate(template).hasErrors()).isFalse();

        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, service.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);
        var result = service.deleteFaction(request);

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains(templateId.toString(), "template-faction");
        assertThat(registry.getFaction(factionId)).isPresent();
    }

    @Test
    void factionDeletionRemovesOnlineAndOfflineReputationBeforeIdCanBeReused() throws IOException {
        var factionId = NamespacedId.of("storynpcs:deleted_reputation");
        savedFaction("deleted_reputation");
        UUID onlinePlayer = UUID.randomUUID();
        UUID offlinePlayer = UUID.randomUUID();
        service.setFactionPoints(onlinePlayer, factionId, 275);
        long onlineFactionRevision = service.currentFactionProgressionRevision(onlinePlayer);

        var progressionDirectory = tempDir.resolve("progression");
        var offlineRepository = new ProgressionRepository(progressionDirectory);
        var offlineProgression = offlineRepository.getOrCreate(offlinePlayer);
        offlineProgression.setFactionScore(factionId, 640);
        offlineProgression.setFactionRevision(10);
        offlineRepository.save(offlinePlayer);

        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, service.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);
        var result = service.deleteFaction(request);

        assertThat(result.applied()).isTrue();
        assertThat(registry.getFaction(factionId)).isEmpty();

        assertThat(service.saveFaction(new Faction(factionId, "Recreated", 1000, 500, 1500)).hasErrors())
                .isFalse();
        var reloadedRepository = new ProgressionRepository(progressionDirectory);
        for (UUID player : List.of(onlinePlayer, offlinePlayer)) {
            var restored = reloadedRepository.getOrCreate(player);
            assertThat(restored.getFactionPoints()).doesNotContainKey(factionId);
            assertThat(restored.getFactionScore(factionId, 1000)).isEqualTo(1000);
        }
        assertThat(service.currentFactionProgressionRevision(onlinePlayer))
                .isEqualTo(onlineFactionRevision + 1L);
        assertThat(reloadedRepository.getOrCreate(offlinePlayer).getFactionRevision()).isEqualTo(11);
        var staleMutation = service.mutateFactionProgression(
                com.storynpcs.service.FactionProgressionMutationRequest.set("system", null, onlinePlayer,
                        factionId, 999, onlineFactionRevision, UUID.randomUUID()));
        assertThat(staleMutation.applied()).isFalse();
        assertThat(staleMutation.formatReport()).contains("STALE_REVISION");
    }

    @Test
    void factionDeletionInvalidatesStaleMutationsWithoutExplicitReputation() throws IOException {
        var factionId = NamespacedId.of("storynpcs:stale_default_reputation");
        savedFaction("stale_default_reputation");
        UUID onlinePlayer = UUID.randomUUID();
        UUID offlinePlayer = UUID.randomUUID();
        long onlineRevision = service.currentFactionProgressionRevision(onlinePlayer);

        Path progressionDirectory = tempDir.resolve("progression");
        var offlineRepository = new ProgressionRepository(progressionDirectory);
        var offlineProgression = offlineRepository.getOrCreate(offlinePlayer);
        long offlineRevision = offlineProgression.getFactionRevision();
        offlineRepository.save(offlinePlayer);

        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, service.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);
        assertThat(service.deleteFaction(request).applied()).isTrue();
        assertThat(service.saveFaction(new Faction(factionId, "Recreated", 1000, 500, 1500)).hasErrors())
                .isFalse();

        var staleOnlineMutation = service.mutateFactionProgression(
                com.storynpcs.service.FactionProgressionMutationRequest.set("system", null, onlinePlayer,
                        factionId, 900, onlineRevision, UUID.randomUUID()));
        var staleOfflineMutation = service.mutateFactionProgression(
                com.storynpcs.service.FactionProgressionMutationRequest.set("system", null, offlinePlayer,
                        factionId, 900, offlineRevision, UUID.randomUUID()));

        assertThat(staleOnlineMutation.applied()).isFalse();
        assertThat(staleOnlineMutation.formatReport()).contains("STALE_REVISION");
        assertThat(staleOfflineMutation.applied()).isFalse();
        assertThat(staleOfflineMutation.formatReport()).contains("STALE_REVISION");
        var resultRepository = new ProgressionRepository(progressionDirectory);
        assertThat(resultRepository.getOrCreate(onlinePlayer).getFactionScore(factionId, 1000)).isEqualTo(1000);
        assertThat(resultRepository.getOrCreate(offlinePlayer).getFactionScore(factionId, 1000)).isEqualTo(1000);
    }

    @Test
    void factionDeletionRollsBackOnlineAndOfflineReputationWhenDefinitionDeleteFails() throws IOException {
        var factionId = NamespacedId.of("storynpcs:reputation_rollback");
        savedFaction("reputation_rollback");
        UUID onlinePlayer = UUID.randomUUID();
        UUID offlinePlayer = UUID.randomUUID();
        UUID playerWithoutFactionOverride = UUID.randomUUID();
        service.setFactionPoints(onlinePlayer, factionId, 275);
        long onlineFactionRevision = service.currentFactionProgressionRevision(onlinePlayer);
        long noOverrideFactionRevision = service.currentFactionProgressionRevision(playerWithoutFactionOverride);

        var progressionDirectory = tempDir.resolve("progression");
        var offlineRepository = new ProgressionRepository(progressionDirectory);
        var offlineProgression = offlineRepository.getOrCreate(offlinePlayer);
        offlineProgression.setFactionScore(factionId, 640);
        offlineProgression.setFactionRevision(10);
        offlineRepository.save(offlinePlayer);

        Path failingRoot = tempDir.resolve("progression-delete-failure-root");
        Files.createDirectories(failingRoot);
        boolean[] reputationRemovedBeforeDelete = {false};
        var failingLoader = new YamlDefinitionLoader(registry) {
            @Override
            public synchronized boolean deleteDefinitionFile(String type, NamespacedId id) throws IOException {
                if ("faction".equals(type) && factionId.equals(id)) {
                    var currentRecords = new ProgressionRepository(progressionDirectory);
                    reputationRemovedBeforeDelete[0] =
                            List.of(onlinePlayer, offlinePlayer, playerWithoutFactionOverride).stream()
                                    .allMatch(player -> !currentRecords.getOrCreate(player).getFactionPoints()
                                            .containsKey(factionId));
                    throw new IOException("injected faction delete failure");
                }
                return super.deleteDefinitionFile(type, id);
            }
        };
        failingLoader.loadDirectory(failingRoot);
        service.setLoader(failingLoader);
        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, service.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);

        var result = service.deleteFaction(request);

        assertThat(result.applied()).isFalse();
        assertThat(reputationRemovedBeforeDelete[0]).isTrue();
        assertThat(registry.getFaction(factionId)).isPresent();
        var reloadedRepository = new ProgressionRepository(progressionDirectory);
        assertThat(reloadedRepository.getOrCreate(onlinePlayer).getFactionScore(factionId, 0)).isEqualTo(275);
        assertThat(reloadedRepository.getOrCreate(offlinePlayer).getFactionScore(factionId, 0)).isEqualTo(640);
        assertThat(reloadedRepository.getOrCreate(onlinePlayer).getFactionRevision())
                .isGreaterThan(onlineFactionRevision);
        assertThat(reloadedRepository.getOrCreate(offlinePlayer).getFactionRevision()).isGreaterThan(10);
        var restoredWithoutOverride = reloadedRepository.getOrCreate(playerWithoutFactionOverride);
        assertThat(restoredWithoutOverride.getFactionPoints()).doesNotContainKey(factionId);
        assertThat(restoredWithoutOverride.getFactionRevision()).isEqualTo(noOverrideFactionRevision + 2L);
    }

    @Test
    void factionDeletionFailsClosedWhenOfflineProgressionCannotBeInspected() throws IOException {
        var factionId = NamespacedId.of("storynpcs:uninspectable_reputation");
        savedFaction("uninspectable_reputation");
        UUID player = UUID.randomUUID();
        Files.writeString(tempDir.resolve("progression").resolve(player + ".json"), "{ not json");
        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, service.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);

        var result = service.deleteFaction(request);

        assertThat(result.applied()).isFalse();
        assertThat(result.formatReport()).contains("progression", player.toString());
        assertThat(registry.getFaction(factionId)).isPresent();
    }

    @Test
    void factionReputationMutationCannotRaceFactionDeletion() throws Exception {
        var factionId = NamespacedId.of("storynpcs:reputation_delete_race");
        savedFaction("reputation_delete_race");
        var deleteReached = new java.util.concurrent.CountDownLatch(1);
        var allowDelete = new java.util.concurrent.CountDownLatch(1);
        Path blockingRoot = tempDir.resolve("reputation-delete-race-root");
        Files.createDirectories(blockingRoot);
        var blockingLoader = new YamlDefinitionLoader(registry) {
            @Override
            public synchronized boolean deleteDefinitionFile(String type, NamespacedId id) throws IOException {
                if ("faction".equals(type) && factionId.equals(id)) {
                    deleteReached.countDown();
                    try {
                        if (!allowDelete.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IOException("faction delete race test timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("faction delete race test interrupted", interrupted);
                    }
                    return true;
                }
                return super.deleteDefinitionFile(type, id);
            }
        };
        blockingLoader.loadDirectory(blockingRoot);
        service.setLoader(blockingLoader);
        UUID player = UUID.randomUUID();
        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, service.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> service.deleteFaction(request).applied());
            assertThat(deleteReached.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            var mutationStarted = new java.util.concurrent.CountDownLatch(1);
            var mutation = pool.submit(() -> {
                mutationStarted.countDown();
                try {
                    service.setFactionPoints(player, factionId, 444);
                    return true;
                } catch (NoSuchElementException missingFaction) {
                    return false;
                }
            });
            assertThat(mutationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> mutation.get(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            allowDelete.countDown();
            assertThat(deletion.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(mutation.get(5, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        } finally {
            allowDelete.countDown();
            pool.shutdownNow();
        }

        assertThat(registry.getFaction(factionId)).isEmpty();
        assertThat(new ProgressionRepository(tempDir.resolve("progression"))
                .getOrCreate(player).getFactionPoints()).doesNotContainKey(factionId);
    }

    @Test
    void factionDeletionSerializesConcurrentDefinitionSaves() throws Exception {
        var concurrentRegistry = new DefinitionRegistry();
        var progressionScanEntered = new java.util.concurrent.CountDownLatch(1);
        var allowProgressionScan = new java.util.concurrent.CountDownLatch(1);
        var progressionRepository = new ProgressionRepository(tempDir.resolve("definition-race-progression")) {
            @Override
            public java.util.Map<UUID, FactionProgressionSnapshot> factionProgressionSnapshots(
                    NamespacedId factionId) throws IOException {
                progressionScanEntered.countDown();
                try {
                    if (!allowProgressionScan.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IOException("faction reference race test timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("faction reference race test interrupted", interrupted);
                }
                return super.factionProgressionSnapshots(factionId);
            }
        };
        var concurrentService = new StoryNpcsApplicationService(
                concurrentRegistry, progressionRepository, new EventPublisher());
        Path definitions = tempDir.resolve("definition-race-definitions");
        Files.createDirectories(definitions);
        var loader = new YamlDefinitionLoader(concurrentRegistry);
        loader.loadDirectory(definitions);
        concurrentService.setLoader(loader);

        var factionId = NamespacedId.of("storynpcs:reference_race");
        assertThat(concurrentService.saveFaction(
                new Faction(factionId, "Reference race", 1000, 500, 1500)).hasErrors()).isFalse();
        var request = new com.storynpcs.service.MutationRequest("faction.delete", "command",
                "faction.delete", factionId, concurrentService.currentRevision("faction", factionId),
                UUID.randomUUID(), 2);
        var dialogueId = NamespacedId.of("storynpcs:concurrent_faction_reference");
        var dialogue = new com.storynpcs.domain.dialogue.DialogueGraph(dialogueId, "Faction reference", "start");
        var start = new com.storynpcs.domain.dialogue.DialogueNode("start", "Start");
        var edge = new com.storynpcs.domain.dialogue.DialogueEdge("Continue", "end");
        edge.setConditions(List.of(new com.storynpcs.domain.dialogue.DialogueCondition(
                com.storynpcs.domain.dialogue.DialogueCondition.Type.FACTION_POINTS,
                factionId.toString(), ">=", "1")));
        start.addOption(edge);
        dialogue.addNode(start);
        dialogue.addNode(new com.storynpcs.domain.dialogue.DialogueNode("end", "End"));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> concurrentService.deleteFaction(request));
            assertThat(progressionScanEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            var saveStarted = new java.util.concurrent.CountDownLatch(1);
            var dialogueSave = pool.submit(() -> {
                saveStarted.countDown();
                return concurrentService.saveDialogue(dialogueId, dialogue);
            });
            assertThat(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> dialogueSave.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            allowProgressionScan.countDown();
            assertThat(deletion.get(5, java.util.concurrent.TimeUnit.SECONDS).applied()).isTrue();
            assertThat(dialogueSave.get(5, java.util.concurrent.TimeUnit.SECONDS).hasErrors()).isTrue();
        } finally {
            allowProgressionScan.countDown();
            pool.shutdownNow();
        }

        assertThat(concurrentRegistry.getFaction(factionId)).isEmpty();
        assertThat(concurrentRegistry.getDialogue(dialogueId)).isEmpty();
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
