package com.storynpcs;

import com.mojang.logging.LogUtils;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.command.StoryNpcsCommands;
import com.storynpcs.entity.StoryNpcRegistry;
import com.storynpcs.lifecycle.WorldLifecycleHandler;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.runtime.actor.ActorLifecycleService;
import com.storynpcs.runtime.actor.ActorProjectionRegistry;
import com.storynpcs.runtime.actor.ActorStateRepository;
import com.storynpcs.runtime.session.RuntimeSessionRegistry;
import com.storynpcs.domain.role.follower.FollowerGroup;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod(StoryNpcs.MOD_ID)
public class StoryNpcs {
    public static final String MOD_ID = "storynpcs";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final DefinitionRegistry registry;
    private final YamlDefinitionLoader loader;
    private final EventPublisher eventPublisher;
    private final WorldLifecycleHandler lifecycleHandler;
    private ProgressionRepository progressionRepository;
    private com.storynpcs.persistence.BankRepository bankRepository;
    private com.storynpcs.persistence.TradeStateRepository tradeStateRepository;
    private StoryNpcsApplicationService applicationService;
    private ActorLifecycleService actorLifecycleService;
    private ActorStateRepository actorStateRepository;
    private final RuntimeSessionRegistry runtimeSessions;
    private final FollowerGroup followerGroup;
    private final Map<MinecraftServer, ActorLifecycleService> serverActorServices = new ConcurrentHashMap<>();
    private final Map<MinecraftServer, ActorStateRepository> serverActorRepositories = new ConcurrentHashMap<>();
    private final Map<MinecraftServer, RuntimeSessionRegistry> serverRuntimeSessions = new ConcurrentHashMap<>();

    private final Map<MinecraftServer, com.storynpcs.service.NameGenerationService>
            serverNameServices = new ConcurrentHashMap<>();
    private final Map<MinecraftServer, FollowerGroup> serverFollowerGroups = new ConcurrentHashMap<>();
    /** World-scoped quest-mail store; installed by the world lifecycle on open. */
    private volatile com.storynpcs.domain.quest.QuestMailStore questMailStore;
    private volatile com.storynpcs.domain.quest.TeamProgressionStore teamProgressionStore;
    /** World-scoped spawner ledger (P8-1); installed by the world lifecycle on open. */
    private volatile com.storynpcs.persistence.SpawnerRuntimeStore spawnerRuntimeStore;
    private volatile com.storynpcs.persistence.WorldToolBindingStore worldToolBindingStore;
    private volatile com.storynpcs.runtime.worldtool.WorldToolExecutor worldToolExecutor;
    /** P8-5 durable ledgers + runtime drivers — per-instance, never static. */
    private volatile com.storynpcs.persistence.SceneTimerStore sceneTimerStore;
    private volatile com.storynpcs.persistence.LinkedNpcStore linkedNpcStore;
    private final com.storynpcs.runtime.orchestration.TimerRuntime timerRuntime;
    private final com.storynpcs.runtime.orchestration.LinkedNpcRuntime linkedNpcRuntime;
    private final com.storynpcs.runtime.orchestration.SceneRuntime sceneRuntime;
    private final com.storynpcs.runtime.orchestration.NaturalSpawnRuntime naturalSpawnRuntime;
    /** Bounded live runtime configuration (P9-4) — shared with the app service. */
    private final com.storynpcs.admin.RuntimeTunables runtimeTunables;
    /** Bounded script dispatch host (P9-2) — per-instance, never static. */
    private final com.storynpcs.script.ScriptScheduler scriptScheduler;
    /** Actor simulation-tier scheduler (P4-1) — per-instance, never static. */
    private final com.storynpcs.sim.SimulationScheduler simulationScheduler;
    /**
     * Bounded path-request queue (P4-2). Navigators submit immutable target
     * snapshots; {@link #drainPathRequests} executes a bounded count/time
     * budget per server tick on the server thread — no async world access.
     */
    private final com.storynpcs.sim.PathScheduler pathScheduler;
    /** Bounded schematic build executor (P8-2/#149) — per-instance, never static. */
    private final com.storynpcs.service.SchematicBuildService schematicBuildService;
    /** Bounded template-spawner driver (P8-1) — per-instance, never static. */
    private final com.storynpcs.runtime.spawner.NpcSpawnerRuntime spawnerRuntime;
    /**
     * Squad target coordinators keyed {@code "dimension|factionId"} (P4-2).
     * Bounded by live faction count; entries with no live assignments are
     * reclaimed opportunistically so the map cannot grow unboundedly.
     */
    private final java.util.Map<String, com.storynpcs.sim.SquadCoordinator> squadCoordinators
            = new java.util.LinkedHashMap<>();
    /** Diagnostics from the most recent definitions load — surfaced to ops in-game on login. */
    private com.storynpcs.domain.common.ValidationResult lastLoadDiagnostics;

    /**
     * Test / headless constructor. The returned instance is self-contained —
     * callers hold the reference; nothing is published to any global channel.
     */
    private StoryNpcs() {
        this.registry = new DefinitionRegistry();
        this.loader = new YamlDefinitionLoader(registry);
        this.eventPublisher = new EventPublisher();
        this.lifecycleHandler = new WorldLifecycleHandler(this);
        this.actorLifecycleService = new ActorLifecycleService(
                new ActorProjectionRegistry("test"),
                eventPublisher
        );
        this.runtimeSessions = new RuntimeSessionRegistry();
        this.followerGroup = new FollowerGroup();
        this.runtimeTunables = new com.storynpcs.admin.RuntimeTunables();
        this.scriptScheduler = new com.storynpcs.script.ScriptScheduler(runtimeTunables.readOnlyView());
        var simTunables = new com.storynpcs.sim.SimulationTunables(runtimeTunables.readOnlyView());
        this.simulationScheduler = new com.storynpcs.sim.SimulationScheduler(
                simTunables::policy, simTunables::budgets);
        this.pathScheduler = new com.storynpcs.sim.PathScheduler();
        this.schematicBuildService = com.storynpcs.service.SchematicBuildService
                .create(runtimeTunables.readOnlyView());
        this.spawnerRuntime = new com.storynpcs.runtime.spawner.NpcSpawnerRuntime(
                () -> registry, this::getApplicationService, eventPublisher,
                () -> spawnerRuntimeStore);
        this.timerRuntime = new com.storynpcs.runtime.orchestration.TimerRuntime();
        this.linkedNpcRuntime = new com.storynpcs.runtime.orchestration.LinkedNpcRuntime();
        this.sceneRuntime = new com.storynpcs.runtime.orchestration.SceneRuntime(
                () -> registry, this::getApplicationService, () -> eventPublisher);
        this.naturalSpawnRuntime = new com.storynpcs.runtime.orchestration.NaturalSpawnRuntime(
                () -> registry, this::getApplicationService, () -> eventPublisher);
    }

    public static StoryNpcs createForTesting() {
        return new StoryNpcs();
    }

    public StoryNpcs(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Dwurdy's StoryNPCs initializing...");

        this.registry = new DefinitionRegistry();
        this.loader = new YamlDefinitionLoader(registry);
        this.eventPublisher = new EventPublisher();
        this.lifecycleHandler = new WorldLifecycleHandler(this);
        this.actorLifecycleService = new ActorLifecycleService(
                new ActorProjectionRegistry("server-uninitialized"),
                eventPublisher
        );
        this.runtimeSessions = new RuntimeSessionRegistry();
        this.followerGroup = new FollowerGroup();
        this.runtimeTunables = new com.storynpcs.admin.RuntimeTunables();
        this.scriptScheduler = new com.storynpcs.script.ScriptScheduler(runtimeTunables.readOnlyView());
        var simTunables = new com.storynpcs.sim.SimulationTunables(runtimeTunables.readOnlyView());
        this.simulationScheduler = new com.storynpcs.sim.SimulationScheduler(
                simTunables::policy, simTunables::budgets);
        this.pathScheduler = new com.storynpcs.sim.PathScheduler();
        this.schematicBuildService = com.storynpcs.service.SchematicBuildService
                .create(runtimeTunables.readOnlyView());
        this.spawnerRuntime = new com.storynpcs.runtime.spawner.NpcSpawnerRuntime(
                () -> registry, this::getApplicationService, eventPublisher,
                () -> spawnerRuntimeStore);
        this.timerRuntime = new com.storynpcs.runtime.orchestration.TimerRuntime();
        this.linkedNpcRuntime = new com.storynpcs.runtime.orchestration.LinkedNpcRuntime();
        this.sceneRuntime = new com.storynpcs.runtime.orchestration.SceneRuntime(
                () -> registry, this::getApplicationService, () -> eventPublisher);
        this.naturalSpawnRuntime = new com.storynpcs.runtime.orchestration.NaturalSpawnRuntime(
                () -> registry, this::getApplicationService, () -> eventPublisher);

        StoryNpcRegistry.register(modEventBus);
        com.storynpcs.item.StoryNpcsItems.register(modEventBus);
        com.storynpcs.command.StoryNpcsArgumentTypes.register(modEventBus);
        StoryNpcsAttachments.register(modEventBus);
        modEventBus.addListener(StoryNpcsNetwork::register);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.storynpcs.client.StoryNpcsClient::registerRenderers);
        }

        // Publish this instance onto every loaded level — server dimensions and
        // the client world — as a lookup handle. Consumers resolve the mod from
        // the lifecycle objects they already hold via StoryNpcsAccess; no
        // mutable static singleton exists.
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onServerStarted);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onServerStopping);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onLevelSave); // VULN-53: save on world auto-save
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(spawnerRuntime::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.ai.combat.WitnessProtectionManager::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.ai.combat.WitnessProtectionManager::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.entity.StoryNpcHitboxHandler::onEntitySize);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.entity.StoryNpcEntity::onLivingKnockback);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    /**
     * Server tick driver: per-tick script budget window (P9-2) plus the
     * simulation-tier evaluation pass (P4-1), nominally once per second of
     * overworld game time. {@code getGameTime} advances once per dimension
     * tick, so with multiple loaded dimensions the {@code %20} gate fires
     * more often than every 20 server ticks — harmless here because
     * {@code evaluate} is idempotent, but the cadence is not exact. Tier
     * evaluation runs in this driver — once per pass — so per-entity ticks
     * only consult the resolved capability gates instead of re-scanning the
     * actor set.
     */
    private void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        scriptScheduler.beginTick();
        MinecraftServer server = event.getServer();
        schematicBuildService.tick();
        spawnerRuntime.tick(server);
        // P8-5: durable timer drain (overworld clock — timers schedule against it)
        timerRuntime.tick(server.overworld().getGameTime());
        for (var level : server.getAllLevels()) {
            sceneRuntime.tick(level, level.getGameTime());
            naturalSpawnRuntime.tick(level, level.getGameTime());
            linkedNpcRuntime.tick(uuid -> actorView(level, uuid), level.getGameTime());
        }
        drainPathRequests(server);
        var appService = getApplicationService();
        if (appService != null) {
            // P6-3: cross-dimension arrival verifications expire per authored
            // timeout — cheap no-op when the queue is empty.
            appService.tickTransportVerifications(server);
        }
        if (server.overworld().getGameTime() % 20 != 0) {
            return;
        }
        var inputs = new java.util.ArrayList<com.storynpcs.sim.SimulationScheduler.ActorInput>();
        for (var level : server.getAllLevels()) {
            var worldBorder = level.getWorldBorder();
            var box = new net.minecraft.world.phys.AABB(
                    worldBorder.getMinX(), level.getMinBuildHeight(), worldBorder.getMinZ(),
                    worldBorder.getMaxX(), level.getMaxBuildHeight(), worldBorder.getMaxZ());
            for (var entity : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(
                            com.storynpcs.entity.StoryNpcEntity.class), box, e -> true)) {
                var nearest = level.getNearestPlayer(entity, 512.0);
                double dist = nearest != null ? Math.sqrt(nearest.distanceToSqr(entity)) : 512.0;
                boolean inCombat = entity.getThreatManager().getCurrentTarget().isPresent();
                inputs.add(new com.storynpcs.sim.SimulationScheduler.ActorInput(
                        entity.getUUID(), dist, inCombat));
            }
        }
        simulationScheduler.evaluate(inputs);
    }

    /**
     * P4-2: drains the bounded path-request queue on the server thread —
     * at most {@link #PATH_DRAIN_MAX_PER_TICK} requests or
     * {@link #PATH_DRAIN_MAX_NANOS} per tick, whichever is hit first, so a
     * burst of repaths amortizes instead of stalling a single tick. World
     * access stays on the server thread; requests carry immutable coordinate
     * snapshots, and stale requests (entity gone, superseded submission) are
     * dropped explicitly.
     *
     * <p>The time bound is checked <em>between</em> requests: a single
     * {@code createPath} may exceed the budget on its own, so the guarantee
     * is bounded <em>count</em> plus best-effort wall time, not a hard
     * per-request latency cap.
     */
    private void drainPathRequests(MinecraftServer server) {
        long deadline = System.nanoTime() + PATH_DRAIN_MAX_NANOS;
        int remaining = PATH_DRAIN_MAX_PER_TICK;
        while (remaining-- > 0 && System.nanoTime() < deadline) {
            var opt = pathScheduler.poll();
            if (opt.isEmpty()) {
                return;
            }
            var request = opt.get();
            for (var level : server.getAllLevels()) {
                var entity = level.getEntity(request.actorId());
                if (entity instanceof com.storynpcs.entity.StoryNpcEntity npc
                        && npc.getNavigation()
                                instanceof com.storynpcs.ai.pathing.StoryNpcPathNavigator nav) {
                    nav.executePending(request.requestId());
                    break;
                }
            }
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        StoryNpcsCommands.register(event);
    }

    /**
     * Publishes this mod instance onto every level as it loads — the overworld
     * and each dimension on the server, and the client world on the client —
     * so level-scoped consumers can resolve it through {@link StoryNpcsAccess}.
     * The handle lives and dies with the level itself: a world unload discards
     * the reference with it.
     */
    private void onLevelLoad(net.neoforged.neoforge.event.level.LevelEvent.Load event) {
        if (event.getLevel() instanceof net.neoforged.neoforge.attachment.IAttachmentHolder holder) {
            holder.setData(StoryNpcsAttachments.MOD_HANDLE, this);
        }
    }

    public DefinitionRegistry getRegistry() {
        return registry;
    }

    public YamlDefinitionLoader getLoader() {
        return loader;
    }

    public EventPublisher getEventPublisher() {
        return eventPublisher;
    }

    public WorldLifecycleHandler getLifecycleHandler() {
        return lifecycleHandler;
    }

    public ProgressionRepository getProgressionRepository() {
        return progressionRepository;
    }

    public void setProgressionRepository(ProgressionRepository progressionRepository) {
        this.progressionRepository = progressionRepository;
    }

    public com.storynpcs.persistence.BankRepository getBankRepository() {
        return bankRepository;
    }

    public void setBankRepository(com.storynpcs.persistence.BankRepository bankRepository) {
        this.bankRepository = bankRepository;
    }

    public com.storynpcs.persistence.TradeStateRepository getTradeStateRepository() {
        return tradeStateRepository;
    }

    public void setTradeStateRepository(com.storynpcs.persistence.TradeStateRepository tradeStateRepository) {
        this.tradeStateRepository = tradeStateRepository;
    }

    public com.storynpcs.domain.quest.QuestMailStore getQuestMailStore() {
        return questMailStore;
    }

    public void setQuestMailStore(com.storynpcs.domain.quest.QuestMailStore questMailStore) {
        this.questMailStore = questMailStore;
    }

    public com.storynpcs.domain.quest.TeamProgressionStore getTeamProgressionStore() {
        return teamProgressionStore;
    }

    public void setTeamProgressionStore(com.storynpcs.domain.quest.TeamProgressionStore store) {
        this.teamProgressionStore = store;
    }

    public com.storynpcs.script.ScriptScheduler getScriptScheduler() {
        return scriptScheduler;
    }

    /** Read-only view of the shared live tunable store. */
    public com.storynpcs.admin.RuntimeTunablesView getRuntimeTunables() {
        return runtimeTunables.readOnlyView();
    }

    public com.storynpcs.sim.SimulationScheduler getSimulationScheduler() {
        return simulationScheduler;
    }

    /** Max path requests executed per server tick — bounds per-tick path work. */
    private static final int PATH_DRAIN_MAX_PER_TICK = 64;
    /** Max wall time spent draining path requests per tick (4 ms). */
    private static final long PATH_DRAIN_MAX_NANOS = 4_000_000L;
    /** Bound on distinct (dimension, faction) squads — stale empties evict first. */
    private static final int MAX_SQUAD_COORDINATORS = 64;

    public com.storynpcs.sim.PathScheduler getPathScheduler() {
        return pathScheduler;
    }

    /**
     * Squad coordinator for {@code "dimension|factionId"} — deterministic
     * unique-target allocation across one faction's NPCs in one dimension.
     * The map is bounded: empty-assignment entries are reclaimed on growth
     * past {@link #MAX_SQUAD_COORDINATORS}.
     */
    public com.storynpcs.sim.SquadCoordinator squadCoordinator(String dimensionKey,
            com.storynpcs.domain.common.NamespacedId factionId) {
        var coordinator = squadCoordinators.computeIfAbsent(
                dimensionKey + "|" + factionId, k -> new com.storynpcs.sim.SquadCoordinator());
        if (squadCoordinators.size() > MAX_SQUAD_COORDINATORS) {
            squadCoordinators.values().removeIf(c -> c.assignments().isEmpty());
        }
        return coordinator;
    }

    /** Drops any squad assignment the actor held (despawn/unload/faction change). */
    public void releaseSquadAssignment(java.util.UUID actorUuid) {
        squadCoordinators.values().forEach(c -> c.release(actorUuid));
    }

    /**
     * P9-1 read-only extension facade over the definition registry. Mutation
     * calls stay unavailable until the capability-grant contract is implemented.
     */
    public com.storynpcs.api.StoryNpcsApi getApi() {
        var service = getApplicationService();
        if (service == null) {
            return null; // world not yet loaded — no registry-backed view exists
        }
        return new com.storynpcs.api.StoryNpcsApi(registry);
    }

    public StoryNpcsApplicationService getApplicationService() {
        return applicationService;
    }

    public void setApplicationService(StoryNpcsApplicationService applicationService) {
        if (applicationService != null) {
            applicationService.setRuntimeTunables(runtimeTunables);
            applicationService.setSpawnerDeleteListener(spawnerRuntime::resetState);
        }
        this.applicationService = applicationService;
    }

    public ActorLifecycleService getActorLifecycleService() {
        return actorLifecycleService;
    }

    public ActorLifecycleService getActorLifecycleService(MinecraftServer server) {
        return server == null ? actorLifecycleService : serverActorServices.get(server);
    }

    public void setActorLifecycleService(ActorLifecycleService actorLifecycleService) {
        this.actorLifecycleService = actorLifecycleService;
    }

    public ActorStateRepository getActorStateRepository() {
        return actorStateRepository;
    }

    public void setActorStateRepository(ActorStateRepository actorStateRepository) {
        this.actorStateRepository = actorStateRepository;
    }

    public ActorStateRepository getActorStateRepository(MinecraftServer server) {
        return server == null ? actorStateRepository : serverActorRepositories.get(server);
    }

    public void registerServerRuntime(
            MinecraftServer server,
            ActorLifecycleService actorService,
            ActorStateRepository actorRepository
    ) {
        if (server == null) {
            return;
        }
        if (actorService != null) {
            serverActorServices.put(server, actorService);
        }
        if (actorRepository != null) {
            serverActorRepositories.put(server, actorRepository);
        }
        serverRuntimeSessions.computeIfAbsent(server, ignored -> new RuntimeSessionRegistry());
        serverFollowerGroups.computeIfAbsent(server, ignored -> new FollowerGroup());
    }

    public void clearServerRuntime(MinecraftServer server) {
        if (server == null) {
            return;
        }
        RuntimeSessionRegistry sessions = serverRuntimeSessions.remove(server);
        if (sessions != null) {
            sessions.clearAll();
        }
        FollowerGroup groups = serverFollowerGroups.remove(server);
        if (groups != null) {
            groups.clearAll();
        }
        serverActorServices.remove(server);
        serverActorRepositories.remove(server);
        serverNameServices.remove(server);
    }

    /**
     * Name-dictionary catalog + seeded generator (#123), loaded per server
     * from bundled resources plus creator overrides — never a static.
     */
    public com.storynpcs.service.NameGenerationService getNameGenerationService(
            MinecraftServer server) {
        if (server == null) {
            return com.storynpcs.service.NameGenerationService.load(null);
        }
        return serverNameServices.computeIfAbsent(server,
                com.storynpcs.service.NameGenerationService::load);
    }

    public com.storynpcs.persistence.SpawnerRuntimeStore getSpawnerRuntimeStore() {
        return spawnerRuntimeStore;
    }

    public void setSpawnerRuntimeStore(com.storynpcs.persistence.SpawnerRuntimeStore store) {
        this.spawnerRuntimeStore = store;
    }

    public com.storynpcs.persistence.WorldToolBindingStore getWorldToolBindingStore() {
        return worldToolBindingStore;
    }

    public void setWorldToolBindingStore(com.storynpcs.persistence.WorldToolBindingStore store) {
        this.worldToolBindingStore = store;
        this.worldToolExecutor = null; // rebuilt lazily against the new store
    }

    /** P8-3 world-tool activation service — built lazily against the world store. */
    public com.storynpcs.runtime.worldtool.WorldToolExecutor getWorldToolExecutor(
            net.minecraft.server.MinecraftServer server) {
        var executor = worldToolExecutor;
        if (executor == null) {
            executor = new com.storynpcs.runtime.worldtool.WorldToolExecutor(
                    eventPublisher, worldToolBindingStore,
                    uuid -> {
                        var store = getApplicationService() != null
                                ? getApplicationService().getQuestMailStore() : null;
                        if (store == null) {
                            return java.util.List.of();
                        }
                        try {
                            return store.pendingFor(uuid);
                        } catch (java.io.IOException e) {
                            return java.util.List.of();
                        }
                    },
                    getRuntimeSessions(server));
            worldToolExecutor = executor;
        }
        return executor;
    }

    public com.storynpcs.runtime.spawner.NpcSpawnerRuntime getSpawnerRuntime() {
        return spawnerRuntime;
    }

    // ── P8-5 orchestration runtimes ─────────────────────────────────────────

    public com.storynpcs.runtime.orchestration.TimerRuntime getTimerRuntime() {
        return timerRuntime;
    }

    public com.storynpcs.runtime.orchestration.LinkedNpcRuntime getLinkedNpcRuntime() {
        return linkedNpcRuntime;
    }

    public com.storynpcs.runtime.orchestration.SceneRuntime getSceneRuntime() {
        return sceneRuntime;
    }

    public com.storynpcs.runtime.orchestration.NaturalSpawnRuntime getNaturalSpawnRuntime() {
        return naturalSpawnRuntime;
    }

    public com.storynpcs.persistence.SceneTimerStore getSceneTimerStore() {
        return sceneTimerStore;
    }

    /** Install the durable timer ledger + rehydrate scheduled timers (P8-5). */
    public void setSceneTimerStore(com.storynpcs.persistence.SceneTimerStore store) {
        this.sceneTimerStore = store;
        if (store == null) {
            timerRuntime.clear();
            return;
        }
        try {
            timerRuntime.attach(store, eventPublisher);
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to rehydrate scene timers", e);
        }
    }

    public com.storynpcs.persistence.LinkedNpcStore getLinkedNpcStore() {
        return linkedNpcStore;
    }

    /** Install the durable link ledger + rehydrate actor links (P8-5). */
    public void setLinkedNpcStore(com.storynpcs.persistence.LinkedNpcStore store) {
        this.linkedNpcStore = store;
        if (store == null) {
            linkedNpcRuntime.clear();
            return;
        }
        try {
            linkedNpcRuntime.attach(store, eventPublisher);
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to rehydrate NPC links", e);
        }
    }

    /** Live-entity seam for the link driver: resolves a UUID to a follow view. */
    private com.storynpcs.runtime.orchestration.LinkedNpcRuntime.ActorView actorView(
            net.minecraft.server.level.ServerLevel level, java.util.UUID uuid) {
        var entity = level.getEntity(uuid);
        if (!(entity instanceof com.storynpcs.entity.StoryNpcEntity npc) || npc.isRemoved()) {
            return null;
        }
        return new com.storynpcs.runtime.orchestration.LinkedNpcRuntime.ActorView() {
            @Override
            public net.minecraft.world.phys.Vec3 position() {
                return npc.position();
            }

            @Override
            public double distanceTo(com.storynpcs.runtime.orchestration.LinkedNpcRuntime.ActorView other) {
                return npc.position().distanceTo(other.position());
            }

            @Override
            public void moveToward(com.storynpcs.runtime.orchestration.LinkedNpcRuntime.ActorView target) {
                var pos = target.position();
                npc.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.0);
            }
        };
    }

    public com.storynpcs.service.SchematicBuildService getSchematicBuildService() {
        return schematicBuildService;
    }

    public RuntimeSessionRegistry getRuntimeSessions() {
        return runtimeSessions;
    }

    public RuntimeSessionRegistry getRuntimeSessions(MinecraftServer server) {
        if (server == null) {
            return runtimeSessions;
        }
        // Never resurrect an entry after clearServerRuntime — a re-inserted
        // dead-server key would retain the entire stopped MinecraftServer.
        // Shutdown-time cleanup callers get an ephemeral registry so their
        // clear/no-op semantics are preserved without mutating shared state.
        RuntimeSessionRegistry registry = serverRuntimeSessions.get(server);
        return registry != null ? registry : new RuntimeSessionRegistry();
    }

    public FollowerGroup getFollowerGroup() {
        return followerGroup;
    }

    public FollowerGroup getFollowerGroup(MinecraftServer server) {
        if (server == null) {
            return followerGroup;
        }
        FollowerGroup group = serverFollowerGroups.get(server);
        return group != null ? group : new FollowerGroup();
    }

    public com.storynpcs.domain.common.ValidationResult getLastLoadDiagnostics() {
        return lastLoadDiagnostics;
    }

    public void setLastLoadDiagnostics(com.storynpcs.domain.common.ValidationResult lastLoadDiagnostics) {
        this.lastLoadDiagnostics = lastLoadDiagnostics;
    }
}
