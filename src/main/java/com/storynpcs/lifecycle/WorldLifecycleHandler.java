package com.storynpcs.lifecycle;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.runtime.actor.ActorLifecycleService;
import com.storynpcs.runtime.actor.ActorProjectionRegistry;
import com.storynpcs.runtime.actor.ActorStateRepository;
import com.storynpcs.service.StoryNpcsApplicationService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class WorldLifecycleHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldLifecycleHandler.class);

    /** Cap on diagnostic lines shown in-game — the full report stays in the server log. */
    private static final int MAX_DIAGNOSTIC_LINES = 5;

    /** Starter definitions shipped inside the mod jar at data/storynpcs/definitions/. */
    private static final List<String> STARTER_DEFINITIONS = List.of(
            "dialogues/captain_dialogue.yaml",
            "factions/town_guard.yaml",
            "npcs/guard_captain.yaml",
            "quests/bounty_goblins.yaml"
    );

    /** Namespaced id of the seeded example NPC, named in the admin announcement. */
    private static final String STARTER_NPC_ID = "storynpcs:guard_captain";

    /**
     * Marker file inside {@code world/storynpcs/} meaning "starter content was seeded but no
     * operator has been told in-game yet". Written at seed time and deleted the moment the
     * announcement reaches an operator — persisting it in the world dir is what guarantees
     * the first operator who joins after a headless seed gets told exactly once.
     */
    private static final String PENDING_SEED_MARKER = ".starter_seed_pending";

    private final StoryNpcs mod;
    private MinecraftServer currentServer;
    private Path worldDir;

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
            if (mod.getTradeStateRepository() != null) {
                try {
                    mod.getTradeStateRepository().save();
                } catch (Exception e) {
                    LOGGER.warn("Failed to save trader runtime state on world save: {}", e.getMessage());
                }
            }
            MinecraftServer server = serverLevel.getServer();
            if (mod.getActorStateRepository(server) != null && mod.getActorLifecycleService(server) != null) {
                try {
                    mod.getActorStateRepository(server).save(mod.getActorLifecycleService(server).registry());
                } catch (Exception e) {
                    LOGGER.warn("Failed to save logical StoryNPC actors on world save: {}", e.getMessage());
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
        this.worldDir = worldDir;
        Path storyNpcsDir = worldDir.resolve("storynpcs");
        Path progressionDir = storyNpcsDir.resolve("progression");
        Path bankDir = storyNpcsDir.resolve("bank");
        Path tradeDir = storyNpcsDir.resolve("trade");
        Path actorDir = storyNpcsDir.resolve("actors");
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
            if (!Files.exists(tradeDir)) {
                Files.createDirectories(tradeDir);
            }
            if (!Files.exists(actorDir)) {
                Files.createDirectories(actorDir);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create StoryNPCs directories: {}", e.getMessage(), e);
        }

        // First-run effort reduction: an empty definitions dir means a brand-new install,
        // so seed the bundled starter content — the admin gets a working NPC out of the box
        // instead of facing an empty mod. Never overwrites existing YAML.
        boolean seeded = seedStarterDefinitions(definitionsDir);
        if (seeded) {
            // Record the pending announcement before anyone is told: if the server dies
            // between seeding and delivery, the marker survives and the next op who joins
            // still hears about it exactly once.
            writePendingSeedMarker(storyNpcsDir);
            if (notifyOnlineOperators(server) > 0) {
                deletePendingSeedMarker();
            }
        }

        ProgressionRepository progressionRepo = new ProgressionRepository(progressionDir);
        mod.setProgressionRepository(progressionRepo);

        com.storynpcs.persistence.BankRepository bankRepo = new com.storynpcs.persistence.BankRepository(bankDir);
        mod.setBankRepository(bankRepo);
        mod.setTradeStateRepository(new com.storynpcs.persistence.TradeStateRepository(tradeDir));

        // The logical actor scope is a durable world identity (scope.id), not the
        // world directory path — relocating a world must not orphan its actors.
        ActorLifecycleService actorService;
        ActorStateRepository actorRepository;
        try {
            String actorScope = WorldScopeIdentity.resolve(storyNpcsDir, actorDir.resolve("registry.json"));
            actorService = new ActorLifecycleService(
                    new ActorProjectionRegistry(actorScope),
                    mod.getEventPublisher()
            );
            actorRepository = new ActorStateRepository(actorDir.resolve("registry.json"), actorScope);
        } catch (Exception identityFailure) {
            // Fail closed: without a durable scope we cannot prove an empty registry
            // belongs to this world, so every actor-state write is refused.
            LOGGER.error("Could not resolve durable actor scope for {}: {}",
                    storyNpcsDir, identityFailure.getMessage());
            actorService = new ActorLifecycleService(
                    new ActorProjectionRegistry("unresolved:" + worldDir.toAbsolutePath().normalize()),
                    mod.getEventPublisher()
            );
            actorRepository = new ActorStateRepository(actorDir.resolve("registry.json"));
            actorRepository.markBlocked("actor scope identity could not be resolved: "
                    + identityFailure.getMessage());
        }
        mod.setActorLifecycleService(actorService);
        mod.setActorStateRepository(actorRepository);
        mod.registerServerRuntime(server, actorService, actorRepository);
        try {
            actorRepository.restore(actorService.registry());
        } catch (Exception e) {
            LOGGER.warn("Could not restore logical StoryNPC actors: {}", e.getMessage());
        }

        StoryNpcsApplicationService appService = new StoryNpcsApplicationService(
                mod.getRegistry(),
                progressionRepo,
                mod.getEventPublisher(),
                server  // may be null in unit tests — service degrades gracefully
        );
        // VULN-57: give the service a loader reference so deleteNpc can delete YAML files on disk
        appService.setLoader(mod.getLoader());
        appService.setTradeStateRepository(mod.getTradeStateRepository());
        mod.setApplicationService(appService);

        // Load definitions
        try {
            ValidationResult result = mod.getLoader().loadDirectory(definitionsDir);
            // Stored so operators logging in can be told about load errors in-game
            // instead of having to hunt through the console log.
            mod.setLastLoadDiagnostics(result);
            if (result.isValid()) {
                LOGGER.info("StoryNPCs definitions loaded: {}", result.formatReport());
            } else {
                LOGGER.warn("StoryNPCs definitions loaded with warnings/errors:\n{}", result.formatReport());
            }
        } catch (Exception e) {
            LOGGER.error("Error loading definitions from {}: {}", definitionsDir, e.getMessage(), e);
        }
    }

    /**
     * Copies the bundled starter definitions into {@code definitionsDir} — but only when
     * the directory contains no YAML at all, so existing admin content is never touched.
     *
     * @return true when starter content was actually written this run
     */
    private boolean seedStarterDefinitions(Path definitionsDir) {
        try (Stream<Path> existing = Files.walk(definitionsDir)) {
            boolean hasYaml = existing.anyMatch(p ->
                    p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"));
            if (hasYaml) {
                return false; // admin already has content — never overwrite
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan definitions directory for starter seeding: {}", e.getMessage());
            return false;
        }

        int seeded = 0;
        for (String rel : STARTER_DEFINITIONS) {
            String resource = "data/storynpcs/definitions/" + rel;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    LOGGER.warn("Starter definition missing from mod jar: {}", resource);
                    continue;
                }
                Path target = definitionsDir.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
                seeded++;
            } catch (IOException e) {
                LOGGER.warn("Failed to seed starter definition {}: {}", rel, e.getMessage());
            }
        }
        if (seeded > 0) {
            LOGGER.info("Seeded {} starter definitions into {} — try /storynpcs npc spawn {}",
                    seeded, definitionsDir, STARTER_NPC_ID);
        }
        return seeded > 0;
    }

    /**
     * Sends the starter-content announcement to every operator currently online.
     *
     * @return how many operators were notified (0 when no server or no ops online)
     */
    private int notifyOnlineOperators(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        int notified = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                sendStarterAnnouncement(player, STARTER_NPC_ID);
                notified++;
            }
        }
        return notified;
    }

    private void sendStarterAnnouncement(ServerPlayer player, String npcId) {
        player.sendSystemMessage(Component.literal(
                "§a[StoryNPCs] Starter content installed — example NPC ready: §e" + npcId
                        + "§a. Run §e/storynpcs npc spawn " + npcId
                        + "§a to meet them, or §e/storynpcs help§a for all commands."));
    }

    private Path pendingSeedMarkerPath() {
        return worldDir == null ? null : worldDir.resolve("storynpcs").resolve(PENDING_SEED_MARKER);
    }

    private void writePendingSeedMarker(Path storyNpcsDir) {
        Path marker = storyNpcsDir.resolve(PENDING_SEED_MARKER);
        Path tmp = marker.resolveSibling(PENDING_SEED_MARKER + ".tmp");
        try {
            Files.writeString(tmp, STARTER_NPC_ID);
            try {
                Files.move(tmp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write pending seed-announcement marker {}: {}", marker, e.getMessage());
        }
    }

    private void deletePendingSeedMarker() {
        Path marker = pendingSeedMarkerPath();
        if (marker == null) {
            return;
        }
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            LOGGER.warn("Could not delete seed-announcement marker {}: {}", marker, e.getMessage());
        }
    }

    /**
     * Reads and removes the pending seed announcement, if any. Package-private for tests;
     * synchronized so two operators logging in on the same tick cannot both claim it.
     */
    synchronized Optional<String> consumePendingSeedAnnouncement() {
        Path marker = pendingSeedMarkerPath();
        if (marker == null || !Files.exists(marker)) {
            return Optional.empty();
        }
        try {
            String npcId = Files.readString(marker).trim();
            Files.delete(marker);
            return Optional.of(npcId.isEmpty() ? STARTER_NPC_ID : npcId);
        } catch (IOException e) {
            LOGGER.warn("Could not consume seed-announcement marker {}: {}", marker, e.getMessage());
            return Optional.empty();
        }
    }

    public void onServerStopping(ServerStoppingEvent event) {
        handleServerStop(event.getServer());
    }

    public void handleServerStop() {
        handleServerStop(currentServer);
    }

    public void handleServerStop(MinecraftServer stoppingServer) {
        ActorLifecycleService stoppingActorService = mod.getActorLifecycleService(stoppingServer);
        ActorStateRepository stoppingActorRepository = mod.getActorStateRepository(stoppingServer);
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
        if (mod.getTradeStateRepository() != null) {
            try {
                mod.getTradeStateRepository().save();
                LOGGER.info("All StoryNPCs trader runtime state saved successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to save trader runtime state on server stop: {}", e.getMessage(), e);
            }
        }
        if (stoppingActorRepository != null && stoppingActorService != null) {
            try {
                stoppingActorRepository.save(stoppingActorService.registry());
                LOGGER.info("Logical StoryNPC actor identities saved successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to save logical StoryNPC actors on server stop: {}", e.getMessage(), e);
            }
        }
        mod.getFollowerGroup(stoppingServer).clearAll();
        mod.getRuntimeSessions(stoppingServer).clearAll();
        mod.clearServerRuntime(stoppingServer);
        if (stoppingActorService != null && mod.getActorLifecycleService() == stoppingActorService) {
            mod.setActorLifecycleService(null);
        }
        if (stoppingActorRepository != null && mod.getActorStateRepository() == stoppingActorRepository) {
            mod.setActorStateRepository(null);
        }
        if (currentServer == stoppingServer) {
            currentServer = null;
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() != null) {
            handlePlayerLogin(event.getEntity().getUUID());
            notifyOperatorOfLoadErrors(event.getEntity());
            announcePendingSeedToOperator(event.getEntity());
        }
    }

    /**
     * A headless server seeds starter content before anyone can be online, so the chat
     * announcement is parked in a world-dir marker. The first operator who joins claims
     * it (marker deleted before send would still be safe — the send is on the same tick);
     * non-operators and subsequent joins never see it.
     */
    private void announcePendingSeedToOperator(Player player) {
        if (!(player instanceof ServerPlayer sp) || !sp.hasPermissions(2)) {
            return;
        }
        consumePendingSeedAnnouncement().ifPresent(npcId -> sendStarterAnnouncement(sp, npcId));
    }

    public void handlePlayerLogin(UUID uuid) {
        if (uuid == null) return;
        if (mod.getProgressionRepository() != null) {
            try {
                mod.getProgressionRepository().getOrCreate(uuid);
            } catch (RuntimeException unavailable) {
                LOGGER.error("StoryNPCs progression for {} is blocked pending recovery: {}",
                        uuid, unavailable.getMessage());
            }
        }
        if (mod.getBankRepository() != null) {
            try {
                mod.getBankRepository().getOrCreate(uuid);
                if (mod.getApplicationService() != null) {
                    int unresolved = mod.getApplicationService().recoverBankOperations(
                            uuid, mod.getBankRepository());
                    if (unresolved > 0) {
                        LOGGER.warn("{} StoryNPCs bank operation(s) still require recovery for player {}",
                                unresolved, uuid);
                    }
                }
            } catch (RuntimeException unavailable) {
                LOGGER.error("StoryNPCs bank vault for {} is blocked pending recovery: {}",
                        uuid, unavailable.getMessage());
            }
        }
        if (mod.getApplicationService() != null) {
            try {
                int unresolvedTrades = mod.getApplicationService().recoverTradeOperations(uuid);
                if (unresolvedTrades > 0) {
                    LOGGER.warn("{} StoryNPCs trade operation(s) still require recovery for player {}",
                            unresolvedTrades, uuid);
                }
            } catch (RuntimeException failure) {
                LOGGER.error("StoryNPCs trade recovery failed for {}: {}", uuid, failure.getMessage());
            }
        }
        LOGGER.debug("Loaded progression and bank for player {}", uuid);
    }

    /**
     * Startup load failures otherwise live only in the console — an op logging in gets
     * the error count plus the top diagnostics with file:line and the fix path.
     */
    private void notifyOperatorOfLoadErrors(Player player) {
        ValidationResult result = mod.getLastLoadDiagnostics();
        if (result == null || !result.hasErrors()) {
            return;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp) || !sp.hasPermissions(2)) {
            return;
        }
        int errorCount = result.getErrors().size();
        sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c[StoryNPCs] Definition load failed: " + errorCount
                        + " error(s) — previous definitions retained. Fix the files below, then run §e/storynpcs reload"));
        result.getErrors().stream().limit(MAX_DIAGNOSTIC_LINES)
                .forEach(d -> {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7  - " + d));
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7      hint: " + com.storynpcs.domain.common.DiagnosticHints.hintFor(d)));
                });
        int extra = errorCount - MAX_DIAGNOSTIC_LINES;
        if (extra > 0) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§7  …and " + extra + " more — see the server log for the full report"));
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            MinecraftServer server = event.getEntity() instanceof ServerPlayer serverPlayer
                    ? serverPlayer.getServer()
                    : null;
            handlePlayerLogout(server, event.getEntity().getUUID());
        }
    }

    public void handlePlayerLogout(UUID uuid) {
        // Compatibility helper for headless callers without a server context.
        // It deliberately fails closed instead of guessing another server.
        handlePlayerLogout(null, uuid);
    }

    public void handlePlayerLogout(MinecraftServer server, UUID uuid) {
        if (uuid == null) return;

        if (mod.getApplicationService() != null) {
            mod.getApplicationService().closeDialogue(uuid);
        }
        if (server != null) {
            com.storynpcs.network.StoryNpcsNetwork.clearPlayer(server, uuid);
            mod.getRuntimeSessions(server).clearPlayer(uuid);
            mod.getFollowerGroup(server).clearLeader(uuid);
        }

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
