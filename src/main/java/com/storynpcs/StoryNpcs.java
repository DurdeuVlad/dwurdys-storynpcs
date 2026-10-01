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
    private final Map<MinecraftServer, FollowerGroup> serverFollowerGroups = new ConcurrentHashMap<>();
    /** World-scoped quest-mail store; installed by the world lifecycle on open. */
    private volatile com.storynpcs.domain.quest.QuestMailStore questMailStore;
    /** Bounded live runtime configuration (P9-4) — shared with the app service. */
    private final com.storynpcs.admin.RuntimeTunables runtimeTunables;
    /** Bounded script dispatch host (P9-2) — per-instance, never static. */
    private final com.storynpcs.script.ScriptScheduler scriptScheduler;
    /** Actor simulation-tier scheduler (P4-1) — per-instance, never static. */
    private final com.storynpcs.sim.SimulationScheduler simulationScheduler;
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
        this.simulationScheduler = new com.storynpcs.sim.SimulationScheduler(
                com.storynpcs.sim.SimulationTierPolicy.defaults(),
                com.storynpcs.sim.TierBudgets.defaults());
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
        this.simulationScheduler = new com.storynpcs.sim.SimulationScheduler(
                com.storynpcs.sim.SimulationTierPolicy.defaults(),
                com.storynpcs.sim.TierBudgets.defaults());

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
        NeoForge.EVENT_BUS.addListener(com.storynpcs.ai.combat.WitnessProtectionManager::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.ai.combat.WitnessProtectionManager::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.entity.StoryNpcHitboxHandler::onEntitySize);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.entity.StoryNpcEntity::onLivingKnockback);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    /**
     * Server tick driver: per-tick script budget window (P9-2) plus the
     * simulation-tier evaluation pass (P4-1) every 20 ticks. Tier evaluation
     * runs here — once per driver pass — so per-entity ticks only consult the
     * resolved capability gates instead of re-scanning the actor set.
     */
    private void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        scriptScheduler.beginTick();
        MinecraftServer server = event.getServer();
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
