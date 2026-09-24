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

    private static StoryNpcs instance;
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
    /** Diagnostics from the most recent definitions load — surfaced to ops in-game on login. */
    private com.storynpcs.domain.common.ValidationResult lastLoadDiagnostics;

    /**
     * Test / headless constructor.
     */
    private StoryNpcs() {
        instance = this;
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
    }

    public static StoryNpcs createForTesting() {
        return new StoryNpcs();
    }

    public StoryNpcs(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
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

        StoryNpcRegistry.register(modEventBus);
        com.storynpcs.item.StoryNpcsItems.register(modEventBus);
        com.storynpcs.command.StoryNpcsArgumentTypes.register(modEventBus);
        modEventBus.addListener(StoryNpcsNetwork::register);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.storynpcs.client.StoryNpcsClient::registerRenderers);
        }

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onServerStarted);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onServerStopping);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onLevelSave); // VULN-53: save on world auto-save
        NeoForge.EVENT_BUS.addListener(com.storynpcs.ai.combat.WitnessProtectionManager::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(com.storynpcs.ai.combat.WitnessProtectionManager::onLivingDeath);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        StoryNpcsCommands.register(event);
    }

    public static StoryNpcs getInstance() {
        return instance;
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

    public StoryNpcsApplicationService getApplicationService() {
        return applicationService;
    }

    public void setApplicationService(StoryNpcsApplicationService applicationService) {
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
        return server == null
                ? runtimeSessions
                : serverRuntimeSessions.computeIfAbsent(server, ignored -> new RuntimeSessionRegistry());
    }

    public FollowerGroup getFollowerGroup() {
        return followerGroup;
    }

    public FollowerGroup getFollowerGroup(MinecraftServer server) {
        return server == null
                ? followerGroup
                : serverFollowerGroups.computeIfAbsent(server, ignored -> new FollowerGroup());
    }

    public com.storynpcs.domain.common.ValidationResult getLastLoadDiagnostics() {
        return lastLoadDiagnostics;
    }

    public void setLastLoadDiagnostics(com.storynpcs.domain.common.ValidationResult lastLoadDiagnostics) {
        this.lastLoadDiagnostics = lastLoadDiagnostics;
    }
}
