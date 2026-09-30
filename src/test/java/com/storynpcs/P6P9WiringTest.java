package com.storynpcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.companion.CompanionProfile;
import com.storynpcs.domain.companion.WageLedger;
import com.storynpcs.domain.job.JobConfig;
import com.storynpcs.domain.job.JobType;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.quest.QuestMail;
import com.storynpcs.domain.quest.QuestMailStore;
import com.storynpcs.domain.transport.TransportLocation;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.AuthorizationPolicy;
import com.storynpcs.service.BankOperationRequest;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;

/**
 * Production-wiring tests for the P6/P8/P9/P11 runtime paths added after the
 * contract-only review: wage charging, quest mail delivery, transports, job
 * validation, YAML family loading, registry revision, authorization scopes,
 * and the import sink. Headless service paths only — live-entity behavior is
 * not simulated here.
 */
class P6P9WiringTest {

    @TempDir
    Path tempDir;

    private DefinitionRegistry registry;
    private StoryNpcsApplicationService service;
    private YamlDefinitionLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DefinitionRegistry();
        var progressionRepository = new ProgressionRepository(tempDir.resolve("progression"));
        service = new StoryNpcsApplicationService(registry, progressionRepository, new EventPublisher());
        Path defsDir = tempDir.resolve("definitions");
        Files.createDirectories(defsDir);
        loader = new YamlDefinitionLoader(registry);
        loader.loadDirectory(defsDir);
        service.setLoader(loader);
    }

    @Test
    void sharedRuntimeTunableAccessExposesOnlyTheReadOnlyView() throws ReflectiveOperationException {
        assertThat(StoryNpcs.class.getMethod("getRuntimeTunables").getReturnType())
                .isEqualTo(com.storynpcs.admin.RuntimeTunablesView.class);
        assertThat(StoryNpcsApplicationService.class.getMethod("runtimeTunables").getReturnType())
                .isEqualTo(com.storynpcs.admin.RuntimeTunablesView.class);
    }

    // ── companion wages (P6-5) ──────────────────────────────────────────────

    @Test
    void companionWage_pausesWhenOwnerOffline_andNeverChargesTwice() {
        var profile = new CompanionProfile();
        profile.setWageAmount(5);
        profile.setWageIntervalTicks(100);
        var ledger = new WageLedger();
        UUID owner = UUID.randomUUID();
        UUID companion = UUID.randomUUID();

        // No server → owner unresolvable → PAUSE unload policy.
        var outcome = service.chargeCompanionWage(owner, companion, profile, ledger, 0, 250);
        assertThat(outcome).isEqualTo(
                StoryNpcsApplicationService.CompanionWageOutcome.OWNER_OFFLINE_PAUSED);

        // DESPAWN unload policy maps to the discard outcome.
        profile.setUnloadPolicy(CompanionProfile.UnloadPolicy.DESPAWN);
        outcome = service.chargeCompanionWage(owner, companion, profile, ledger, 0, 250);
        assertThat(outcome).isEqualTo(
                StoryNpcsApplicationService.CompanionWageOutcome.OWNER_OFFLINE_DESPAWN);

        // Already-charged periods are idempotent regardless of policies.
        ledger.setLastChargedPeriod(2);
        outcome = service.chargeCompanionWage(owner, companion, profile, ledger, 0, 250);
        assertThat(outcome).isEqualTo(
                StoryNpcsApplicationService.CompanionWageOutcome.ALREADY_CHARGED);
    }

    @Test
    void wageLedger_exactlyOncePerPeriod() {
        var ledger = new WageLedger();
        var charged = new java.util.concurrent.atomic.AtomicInteger();
        WageLedger.Payer payer = (id, amount) -> {
            charged.incrementAndGet();
            return true;
        };
        assertThat(ledger.charge(1, 10, payer, UUID.randomUUID()))
                .isEqualTo(WageLedger.ChargeOutcome.CHARGED);
        // Same period: never charged twice, payer not even consulted.
        assertThat(ledger.charge(1, 10, payer, UUID.randomUUID()))
                .isEqualTo(WageLedger.ChargeOutcome.ALREADY_CHARGED);
        assertThat(charged.get()).isEqualTo(1);

        // Insufficient funds leaves the period uncharged — retry allowed.
        var ledger2 = new WageLedger();
        assertThat(ledger2.charge(3, 10, (id, a) -> false, UUID.randomUUID()))
                .isEqualTo(WageLedger.ChargeOutcome.INSUFFICIENT_FUNDS);
        assertThat(ledger2.getLastChargedPeriod()).isLessThan(3);
        assertThat(ledger2.charge(3, 10, (id, a) -> true, UUID.randomUUID()))
                .isEqualTo(WageLedger.ChargeOutcome.CHARGED);

        // Zero-wage periods still consume exactly once.
        var ledger3 = new WageLedger();
        assertThat(ledger3.charge(0, 0, (id, a) -> { throw new AssertionError("payer consulted"); },
                UUID.randomUUID())).isEqualTo(WageLedger.ChargeOutcome.CHARGED);
    }

    // ── quest mail (P5-5 / P2-2 global_data) ────────────────────────────────

    @Test
    void questMailStore_claimIsExactlyOnce_andPendingFiltersClaimed() throws IOException {
        var store = new QuestMailStore(tempDir.resolve("mail"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.open();
        UUID player = UUID.randomUUID();
        var mail = new QuestMail(UUID.randomUUID(), player,
                NamespacedId.of("storynpcs:reward_quest"),
                List.of(com.storynpcs.domain.npc.NpcItemStack.of(
                        NamespacedId.of("minecraft:diamond"), 3, "")),
                50, "INVENTORY_FULL", 100, 0, 0);
        store.enqueue(mail);

        assertThat(store.pendingFor(player)).hasSize(1);
        assertThat(store.claim(mail.mailId(), 200)).isPresent();
        // Second claim is refused — the durable claim mark wins.
        assertThat(store.claim(mail.mailId(), 300)).isEmpty();
        // Claimed mail no longer appears as pending.
        assertThat(store.pendingFor(player)).isEmpty();
    }

    @Test
    void deliverQuestMail_emptyWithoutServerOrStore() {
        UUID player = UUID.randomUUID();
        assertThat(service.deliverQuestMail(player, 32)).isEmpty();
    }

    // ── transports (P6-3) ───────────────────────────────────────────────────

    @Test
    void transport_loadsAndEvaluatesFromRegistry() {
        var factionId = NamespacedId.of("storynpcs:ferrymen");
        registry.registerFaction(new com.storynpcs.domain.faction.Faction(
                factionId, "Ferrymen", 0, -100, 100));
        UUID eligiblePlayer = UUID.randomUUID();
        service.setFactionPoints(eligiblePlayer, factionId, 50);
        var result = new ValidationResult();
        var loaded = loader.loadTransport("""
                id: storynpcs:ferry_dock
                name: Ferry Dock
                dimensionId: minecraft:overworld
                x: 100
                y: 64
                z: -40
                fee: 12
                unlockConditions:
                  - type: FACTION_POINTS
                    target: storynpcs:ferrymen
                    operator: ">="
                    value: "50"
                visibleWhenLocked: true
                """, "ferry.yaml", result);
        assertThat(loaded).isNotNull();
        assertThat(result.hasErrors()).isFalse();
        assertThat(registry.getTransport(NamespacedId.of("storynpcs:ferry_dock"))).isPresent();

        // Locked locations only surface when marked visibleWhenLocked.
        var visible = service.listTransports(UUID.randomUUID());
        assertThat(visible).hasSize(1);

        var hiddenResult = new ValidationResult();
        loader.loadTransport("""
                id: storynpcs:secret_gate
                name: Secret Gate
                dimensionId: minecraft:overworld
                x: 0
                y: 70
                z: 0
                fee: 0
                unlockConditions:
                  - type: FACTION_POINTS
                    target: storynpcs:ferrymen
                    operator: ">="
                    value: "50"
                """, "gate.yaml", hiddenResult);
        assertThat(registry.getTransport(NamespacedId.of("storynpcs:secret_gate"))).isPresent();
        assertThat(service.listTransports(UUID.randomUUID())).hasSize(1);
        assertThat(service.listTransports(eligiblePlayer)).hasSize(2);
    }

    @Test
    void requestTransport_failsClosedWithoutServer() {
        var result = service.requestTransport(UUID.randomUUID(),
                NamespacedId.of("storynpcs:anywhere"));
        assertThat(result.approved()).isFalse();
        assertThat(result.detail()).isEqualTo("SERVER_UNAVAILABLE");
    }

    // ── job config validation (P6-4) ────────────────────────────────────────

    @Test
    void jobConfig_rejectsUnimplementedAndMissingFields() {
        var giver = new JobConfig(JobType.ITEM_GIVER);
        // ITEM_GIVER without itemId must fail validation.
        org.assertj.core.api.Assertions.assertThatThrownBy(giver::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("itemId");
        giver.setItemId(NamespacedId.of("minecraft:bread"));
        giver.validate(); // valid now

        var builder = new JobConfig(JobType.BUILDER);
        org.assertj.core.api.Assertions.assertThatThrownBy(builder::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no implemented handler");
    }

    @Test
    void npcYaml_embedsJobCompanionAndSocialRoles() {
        var result = new ValidationResult();
        var npc = loader.loadNpc("""
                id: storynpcs:multiskill_npc
                display:
                  name: Multiskill
                job:
                  type: HEALER
                  enabled: true
                  tickPeriod: 40
                  effectRadiusBlocks: 8.0
                companion:
                  wageAmount: 7
                  wageIntervalTicks: 24000
                  insufficientFundsPolicy: PAUSE_SERVICE
                bard:
                  buffEffect: minecraft:regeneration
                  effectRadiusBlocks: 10.0
                  cooldownTicks: 600
                healer:
                  healAmount: 3.0
                  rangeBlocks: 6.0
                  targetPolicy: PLAYERS_ONLY
                postman:
                  mailboxCapacity: 16
                  deliveryRangeBlocks: 12.0
                """, "multiskill.yaml", result);
        assertThat(result.hasErrors()).as(result.formatReport()).isFalse();
        assertThat(npc).isNotNull();
        assertThat(npc.getJob().getType()).isEqualTo(JobType.HEALER);
        assertThat(npc.getCompanion().getWageAmount()).isEqualTo(7);
        assertThat(npc.getBard().getCooldownTicks()).isEqualTo(600);
        assertThat(npc.getHealer().getTargetPolicy())
                .isEqualTo(com.storynpcs.domain.role.social.HealerRole.TargetPolicy.PLAYERS_ONLY);
        assertThat(npc.getPostman().getMailboxCapacity()).isEqualTo(16);
    }

    @Test
    void npcYaml_rejectsInvalidJobAtLoad() {
        var result = new ValidationResult();
        var npc = loader.loadNpc("""
                id: storynpcs:bad_job_npc
                display:
                  name: BadJob
                job:
                  type: ITEM_GIVER
                  itemCount: 2
                """, "badjob.yaml", result);
        assertThat(npc).isNull();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("JOB_CONFIG_INVALID"));
    }

    // ── templates (P8-1) ────────────────────────────────────────────────────

    @Test
    void template_loadsValidatesAndInstantiates() {
        var result = new ValidationResult();
        var template = loader.loadTemplate("""
                id: storynpcs:village_guard
                schemaVersion: 1
                description: Basic guard
                tags: [guard, melee]
                definition:
                  id: storynpcs:template_guard_inner
                  display:
                    name: Guard Template
                  stats:
                    maxHealth: 30.0
                """, "guard_template.yaml", result);
        assertThat(result.hasErrors()).as(result.formatReport()).isFalse();
        assertThat(template).isNotNull();
        assertThat(registry.getTemplate(NamespacedId.of("storynpcs:village_guard"))).isPresent();

        // instantiate() yields a deep copy — never the template's own object.
        var clone = template.instantiate(NamespacedId.of("storynpcs:gate_guard"));
        assertThat(clone.getId()).isEqualTo(NamespacedId.of("storynpcs:gate_guard"));
        assertThat(clone).isNotSameAs(template.getDefinition());
        assertThat(clone.getStats().getMaxHealth()).isEqualTo(30.0);
    }

    @Test
    void template_rejectsBadVersionAndMissingDefinition() {
        var result = new ValidationResult();
        assertThat(loader.loadTemplate("""
                id: storynpcs:bad_v
                schemaVersion: 99
                definition: { id: storynpcs:x, name: X }
                """, "bad.yaml", result)).isNull();
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("SCHEMA_VERSION_UNSUPPORTED"));

        var result2 = new ValidationResult();
        assertThat(loader.loadTemplate("""
                id: storynpcs:empty_t
                schemaVersion: 1
                """, "empty.yaml", result2)).isNull();
        assertThat(result2.getErrors()).anyMatch(e -> e.code().equals("TEMPLATE_MISSING_DEFINITION"));
    }

    @Test
    void extensionApiDoesNotExposeTheMutableApplicationService() {
        var methodNames = java.util.Arrays.stream(com.storynpcs.api.StoryNpcsApi.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();

        assertThat(methodNames).doesNotContain("canonical");
        assertThat(java.util.Arrays.stream(com.storynpcs.api.StoryNpcsApi.class.getMethods())
                .noneMatch(method -> method.getReturnType()
                        .equals(StoryNpcsApplicationService.class))).isTrue();
    }

    @Test
    void supportedExtensionResolverExposesOnlyTheReadOnlyFacade() {
        assertThat(com.storynpcs.api.StoryNpcsApiAccess.api(
                (net.minecraft.world.level.LevelAccessor) null)).isEmpty();
        assertThat(com.storynpcs.api.StoryNpcsApiAccess.api(
                (net.minecraft.world.entity.Entity) null)).isEmpty();
        assertThat(com.storynpcs.api.StoryNpcsApiAccess.api(
                (net.minecraft.server.MinecraftServer) null)).isEmpty();
        var apiResolvers = java.util.Arrays.stream(com.storynpcs.api.StoryNpcsApiAccess.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(apiResolvers).hasSize(3);
        assertThat(apiResolvers).allSatisfy(method -> {
            assertThat(method.getName()).isEqualTo("api");
            assertThat(method.getGenericReturnType().getTypeName())
                    .isEqualTo("java.util.Optional<com.storynpcs.api.StoryNpcsApi>");
        });
        assertThat(java.util.Arrays.stream(com.storynpcs.StoryNpcsAccess.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getReturnType))
                .doesNotContain(com.storynpcs.api.StoryNpcsApi.class);
    }

    // ── registry revision (P10-2 staleness primitive) ───────────────────────

    @Test
    void registryRevision_bumpsOnRegisterAndRemove() {
        long base = registry.revision();
        var npc = new NpcDefinition(NamespacedId.of("storynpcs:rev_a"), "A");
        registry.registerNpc(npc);
        assertThat(registry.revision()).isGreaterThan(base);
        long afterRegister = registry.revision();
        registry.removeNpc(NamespacedId.of("storynpcs:rev_a"));
        assertThat(registry.revision()).isGreaterThan(afterRegister);
    }

    // ── authorization data scope (P9-4) ─────────────────────────────────────

    @Test
    void commandActor_crossPlayerRequiresPermission2() {
        UUID operator = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        var selfOp = new BankOperationRequest("command", operator, operator, null,
                BankOperationRequest.Action.WITHDRAW, 0, 0, null, 1, UUID.randomUUID(), 0);
        assertThat(AuthorizationPolicy.evaluate(selfOp).allowed())
                .as("self-bank ops are always permitted").isTrue();

        var crossLow = new BankOperationRequest("command", operator, subject, null,
                BankOperationRequest.Action.WITHDRAW, 0, 0, null, 1, UUID.randomUUID(), 1);
        assertThat(AuthorizationPolicy.evaluate(crossLow).allowed())
                .as("cross-player op with permission <2 must be denied").isFalse();
        assertThat(AuthorizationPolicy.evaluate(crossLow).code())
                .isEqualTo("PERMISSION_DENIED");

        var crossOp = new BankOperationRequest("command", operator, subject, null,
                BankOperationRequest.Action.WITHDRAW, 0, 0, null, 1, UUID.randomUUID(), 2);
        assertThat(AuthorizationPolicy.evaluate(crossOp).allowed())
                .as("cross-player op with permission >=2 is permitted").isTrue();
    }

    // ── import sink (P11-1) ─────────────────────────────────────────────────

    @Test
    void registryImportSink_routesThroughCanonicalSaves() {
        var defsRoot = tempDir.resolve("definitions");
        var sink = new com.storynpcs.migration.RegistryImportSink(service, registry);

        var npc = new NpcDefinition(NamespacedId.of("storynpcs:imported_npc"), "Imported");
        assertThat(sink.contains("npc", npc.getId())).isFalse();
        sink.save("npc", npc.getId(), npc);
        assertThat(sink.contains("npc", npc.getId())).isTrue();
        // Canonical save persisted the YAML file.
        assertThat(defsRoot.resolve("npcs")).isDirectoryContaining(p -> p.toString().endsWith(".yaml"));

        var prior = sink.snapshot("npc", npc.getId());
        // Snapshots are detached serde copies — rollback restores pre-import
        // state, never the (possibly mutated) live object.
        assertThat(prior).isNotSameAs(npc);
        assertThat(((NpcDefinition) prior).getId()).isEqualTo(npc.getId());
        sink.delete("npc", npc.getId());
        assertThat(sink.contains("npc", npc.getId())).isFalse();

        sink.restore("npc", npc.getId(), prior);
        assertThat(sink.contains("npc", npc.getId())).isTrue();
    }

    @Test
    void registryImportSink_templateRoundTrip() {
        var defsRoot = tempDir.resolve("definitions");
        var sink = new com.storynpcs.migration.RegistryImportSink(service, registry);
        var template = new com.storynpcs.creator.template.NpcTemplate();
        template.setId(NamespacedId.of("storynpcs:imp_t"));
        template.setDefinition(new NpcDefinition(NamespacedId.of("storynpcs:t_inner"), "T"));
        sink.saveTemplate(template);
        var templateSnapshot = sink.snapshotTemplate(NamespacedId.of("storynpcs:imp_t"));
        assertThat(templateSnapshot).isNotSameAs(template);
        assertThat(templateSnapshot.getId()).isEqualTo(template.getId());
        assertThat(defsRoot.resolve("templates")).isDirectoryContaining(p -> p.toString().contains(".yaml"));
        sink.deleteTemplate(NamespacedId.of("storynpcs:imp_t"));
        assertThat(sink.snapshotTemplate(NamespacedId.of("storynpcs:imp_t"))).isNull();
    }

    // ── script scheduler wiring (P9-2) ──────────────────────────────────────

    @Test
    void scriptScheduler_dispatchRunsRunnerAndTracksBudget() {
        var scheduler = new com.storynpcs.script.ScriptScheduler();
        var scriptId = UUID.randomUUID();
        scheduler.register(scriptId, UUID.randomUUID());
        scheduler.beginTick();
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        var outcome = scheduler.dispatch(scriptId, com.storynpcs.script.ScriptHook.INTERACT,
                () -> ran.set(true));
        assertThat(ran).isTrue();
        assertThat(outcome).isInstanceOf(
                com.storynpcs.script.ScriptScheduler.DispatchOutcome.Completed.class);
        // Unregistered script dispatch is skipped — hooks can't fire for ghosts.
        var ghost = scheduler.dispatch(UUID.randomUUID(), com.storynpcs.script.ScriptHook.TICK,
                () -> { throw new AssertionError("ghost ran"); });
        assertThat(ghost).isInstanceOf(
                com.storynpcs.script.ScriptScheduler.DispatchOutcome.Skipped.class);
    }

    // ── simulation scheduler wiring (P4-1) ──────────────────────────────────

    @Test
    void simulationScheduler_gatesCapabilitiesByTier() {
        var scheduler = new com.storynpcs.sim.SimulationScheduler(
                com.storynpcs.sim.SimulationTierPolicy.defaults(),
                com.storynpcs.sim.TierBudgets.defaults());
        var near = UUID.randomUUID();
        var far = UUID.randomUUID();
        scheduler.evaluate(List.of(
                new com.storynpcs.sim.SimulationScheduler.ActorInput(near, 10.0, false),
                new com.storynpcs.sim.SimulationScheduler.ActorInput(far, 400.0, false)));
        var nearTier = scheduler.stateOf(near).tier();
        var farTier = scheduler.stateOf(far).tier();
        assertThat(farTier.ordinal()).isGreaterThanOrEqualTo(nearTier.ordinal());
        // Combat actors can never degrade into dormancy.
        scheduler.evaluate(List.of(
                new com.storynpcs.sim.SimulationScheduler.ActorInput(far, 400.0, true)));
        assertThat(scheduler.stateOf(far).inCombat()).isTrue();
    }
}
