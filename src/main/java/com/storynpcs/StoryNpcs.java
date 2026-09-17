package com.storynpcs;

import com.mojang.logging.LogUtils;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.command.StoryNpcsCommands;
import com.storynpcs.entity.StoryNpcRegistry;
import com.storynpcs.lifecycle.WorldLifecycleHandler;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.persistence.ProgressionRepository;
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
import org.slf4j.Logger;

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
    private StoryNpcsApplicationService applicationService;

    /**
     * Test / headless constructor.
     */
    public StoryNpcs() {
        instance = this;
        this.registry = new DefinitionRegistry();
        this.loader = new YamlDefinitionLoader(registry);
        this.eventPublisher = new EventPublisher();
        this.lifecycleHandler = new WorldLifecycleHandler(this);
    }

    public StoryNpcs(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
        LOGGER.info("Dwurdy's StoryNPCs initializing...");

        this.registry = new DefinitionRegistry();
        this.loader = new YamlDefinitionLoader(registry);
        this.eventPublisher = new EventPublisher();
        this.lifecycleHandler = new WorldLifecycleHandler(this);

        StoryNpcRegistry.register(modEventBus);
        modEventBus.addListener(StoryNpcsNetwork::register);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onServerStarted);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onServerStopping);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(lifecycleHandler::onPlayerLoggedOut);
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

    public StoryNpcsApplicationService getApplicationService() {
        return applicationService;
    }

    public void setApplicationService(StoryNpcsApplicationService applicationService) {
        this.applicationService = applicationService;
    }
}