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
    private MinecraftServer currentServer;

    public WorldLifecycleHandler(StoryNpcs mod) {
        this.mod = mod;
    }

    public MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public void onServerStarted(ServerStartedEvent event) {
        this.currentServer = event.getServer();
        initializeServer(event.getServer());
    }

    public void onLevelSave(net.neoforged.neoforge.event.level.LevelEvent.Save event) {
        // VULN-53: Save all online player data on every world auto-save, not just server shutdown
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && serverLevel.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            if (mod.getProgressionRepository() != null) {
                try {
                    mod.getProgressionRepository().saveAll();
                } catch (Exception e) {
                    LOGGER.warn("Failed to save player progressions on world save: {}", e.getMessage());
                }
            }
            if (mod.getBankRepository() != null) {
                try {
                    mod.getBankRepository().saveAll();
                } catch (Exception e) {
                    LOGGER.warn("Failed to save bank vaults on world save: {}", e.getMessage());
                }
            }
        }
    }

    public void initializeServer(MinecraftServer server) {
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        initializeWorld(worldDir, server);
    }

    /** Legacy overload kept for unit tests that call initializeWorld directly without a server instance. */
    public void initializeWorld(Path worldDir) {
        initializeWorld(worldDir, null);
    }

    public void initializeWorld(Path worldDir, MinecraftServer server) {
        Path storyNpcsDir = worldDir.resolve("storynpcs");
        Path progressionDir = storyNpcsDir.resolve("progression");
        Path bankDir = storyNpcsDir.resolve("bank");
        Path definitionsDir = storyNpcsDir.resolve("definitions");

        try {
            if (!Files.exists(definitionsDir)) {
                Files.createDirectories(definitionsDir);
            }
            if (!Files.exists(progressionDir)) {
                Files.createDirectories(progressionDir);
            }
            if (!Files.exists(bankDir)) {
                Files.createDirectories(bankDir);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create StoryNPCs directories: {}", e.getMessage(), e);
        }

        ProgressionRepository progressionRepo = new ProgressionRepository(progressionDir);
        mod.setProgressionRepository(progressionRepo);

        com.storynpcs.persistence.BankRepository bankRepo = new com.storynpcs.persistence.BankRepository(bankDir);
        mod.setBankRepository(bankRepo);

        StoryNpcsApplicationService appService = new StoryNpcsApplicationService(
                mod.getRegistry(),
                progressionRepo,
                mod.getEventPublisher(),
                server  // may be null in unit tests — service degrades gracefully
        );
        // VULN-57: give the service a loader reference so deleteNpc can delete YAML files on disk
        appService.setLoader(mod.getLoader());
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
        if (mod.getBankRepository() != null) {
            try {
                mod.getBankRepository().saveAll();
                LOGGER.info("All StoryNPCs bank vaults saved successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to save bank vaults on server stop: {}", e.getMessage(), e);
            }
        }
        com.storynpcs.domain.role.follower.FollowerGroup.clearAll();
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() != null) {
            handlePlayerLogin(event.getEntity().getUUID());
        }
    }

    public void handlePlayerLogin(UUID uuid) {
        if (uuid == null) return;
        if (mod.getProgressionRepository() != null) {
            mod.getProgressionRepository().getOrCreate(uuid);
        }
        if (mod.getBankRepository() != null) {
            mod.getBankRepository().getOrCreate(uuid);
        }
        LOGGER.debug("Loaded progression and bank for player {}", uuid);
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            handlePlayerLogout(event.getEntity().getUUID());
        }
    }

    public void handlePlayerLogout(UUID uuid) {
        if (uuid == null) return;

        if (mod.getApplicationService() != null) {
            mod.getApplicationService().closeDialogue(uuid);
        }
        com.storynpcs.network.StoryNpcsNetwork.clearPlayer(uuid);
        com.storynpcs.domain.role.follower.FollowerGroup.clearLeader(uuid);

        if (mod.getProgressionRepository() != null) {
            try {
                mod.getProgressionRepository().save(uuid);
                mod.getProgressionRepository().unload(uuid);
                LOGGER.debug("Saved and unloaded progression for player {} on logout", uuid);
            } catch (Exception e) {
                LOGGER.error("Failed to save progression on logout for {}: {}", uuid, e.getMessage());
            }
        }
        if (mod.getBankRepository() != null) {
            try {
                mod.getBankRepository().save(uuid);
                mod.getBankRepository().unload(uuid);
                LOGGER.debug("Saved and unloaded bank vault for player {} on logout", uuid);
            } catch (Exception e) {
                LOGGER.error("Failed to save bank vault on logout for {}: {}", uuid, e.getMessage());
            }
        }
    }
}