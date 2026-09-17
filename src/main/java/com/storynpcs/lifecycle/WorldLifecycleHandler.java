package com.storynpcs.lifecycle;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class WorldLifecycleHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldLifecycleHandler.class);

    private final StoryNpcs mod;

    public WorldLifecycleHandler(StoryNpcs mod) {
        this.mod = mod;
    }

    public void onServerStarted(ServerStartedEvent event) {
        initializeServer(event.getServer());
    }

    public void initializeServer(MinecraftServer server) {
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        initializeWorld(worldDir);
    }

    public void initializeWorld(Path worldDir) {
        Path storyNpcsDir = worldDir.resolve("storynpcs");
        Path progressionDir = storyNpcsDir.resolve("progression");
        Path definitionsDir = storyNpcsDir.resolve("definitions");

        try {
            if (!Files.exists(definitionsDir)) {
                Files.createDirectories(definitionsDir);
            }
            if (!Files.exists(progressionDir)) {
                Files.createDirectories(progressionDir);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create StoryNPCs directories: {}", e.getMessage(), e);
        }

        ProgressionRepository progressionRepo = new ProgressionRepository(progressionDir);
        mod.setProgressionRepository(progressionRepo);

        StoryNpcsApplicationService appService = new StoryNpcsApplicationService(
                mod.getRegistry(),
                progressionRepo,
                mod.getEventPublisher()
        );
        mod.setApplicationService(appService);

        // Load definitions
        try {
            ValidationResult result = mod.getLoader().loadDirectory(definitionsDir);
            if (result.isValid()) {
                LOGGER.info("StoryNPCs definitions loaded: {}", result.formatReport());
            } else {
                LOGGER.warn("StoryNPCs definitions loaded with warnings/errors:\n{}", result.formatReport());
            }
        } catch (Exception e) {
            LOGGER.error("Error loading definitions from {}: {}", definitionsDir, e.getMessage(), e);
        }
    }

    public void onServerStopping(ServerStoppingEvent event) {
        handleServerStop();
    }

    public void handleServerStop() {
        if (mod.getProgressionRepository() != null) {
            try {
                mod.getProgressionRepository().saveAll();
                LOGGER.info("All StoryNPCs player progressions saved successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to save player progressions on server stop: {}", e.getMessage(), e);
            }
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() != null) {
            handlePlayerLogin(event.getEntity().getUUID());
        }
    }

    public void handlePlayerLogin(UUID uuid) {
        if (uuid == null || mod.getProgressionRepository() == null) return;
        mod.getProgressionRepository().getOrCreate(uuid);
        LOGGER.debug("Loaded progression for player {}", uuid);
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            handlePlayerLogout(event.getEntity().getUUID());
        }
    }

    public void handlePlayerLogout(UUID uuid) {
        if (uuid == null || mod.getProgressionRepository() == null) return;
        try {
            mod.getProgressionRepository().save(uuid);
            LOGGER.debug("Saved progression for player {} on logout", uuid);
        } catch (Exception e) {
            LOGGER.error("Failed to save progression on logout for {}: {}", uuid, e.getMessage());
        }
    }
}