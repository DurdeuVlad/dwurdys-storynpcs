package com.storynpcs;

import com.mojang.logging.LogUtils;
import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import com.storynpcs.yaml.YamlDefinitionLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.nio.file.Path;

@Mod(StoryNpcs.MOD_ID)
public class StoryNpcs {
    public static final String MOD_ID = "storynpcs";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static StoryNpcs instance;
    private final DefinitionRegistry registry;
    private final YamlDefinitionLoader loader;
    private final EventPublisher eventPublisher;
    private ProgressionRepository progressionRepository;
    private StoryNpcsApplicationService applicationService;

    public StoryNpcs(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
        LOGGER.info("Dwurdy's StoryNPCs initializing...");

        this.registry = new DefinitionRegistry();
        this.loader = new YamlDefinitionLoader(registry);
        this.eventPublisher = new EventPublisher();

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
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

    public ProgressionRepository getProgressionRepository() {
        return progressionRepository;
    }

    public StoryNpcsApplicationService getApplicationService() {
        return applicationService;
    }

    private void onServerStarting(ServerStartingEvent event) {
        Path worldDir = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        Path progressionDir = worldDir.resolve("storynpcs").resolve("progression");
        this.progressionRepository = new ProgressionRepository(progressionDir);
        this.applicationService = new StoryNpcsApplicationService(registry, progressionRepository, eventPublisher);

        // Load content definitions from world storynpcs directory
        Path definitionsDir = worldDir.resolve("storynpcs").resolve("definitions");
        try {
            var result = loader.loadDirectory(definitionsDir);
            LOGGER.info("Loaded StoryNPCs definitions: {}", result.formatReport());
        } catch (Exception e) {
            LOGGER.error("Failed to load StoryNPCs definitions: {}", e.getMessage(), e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (progressionRepository != null) {
            progressionRepository.saveAll();
        }
    }
}
