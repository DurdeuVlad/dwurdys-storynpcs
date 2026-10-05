package com.storynpcs.runtime.spawner;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.NpcSpawnerLifecycleEvent;
import com.storynpcs.creator.template.SpawnerRule;
import com.storynpcs.creator.template.SpawnerRuntimeState;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.persistence.SpawnerRuntimeStore;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded template-spawner driver (P8-1): anchored {@link SpawnerRule}s are
 * evaluated on a staggered 20-tick cadence — never more than one spawn per
 * rule per interval, never above quota, never outside a loaded chunk.
 *
 * <p>Ownership is durable: every ledger mutation commits through
 * {@link SpawnerRuntimeStore} so a restart enforces quotas against the actors
 * a spawner actually owns (without it, restart would see zero owned entities
 * and over-spawn while prior actors still exist). Entities are bound to their
 * spawner via the {@code TemplateSpawner} persistent-data key; the
 * {@code EntityLeaveLevelEvent} path resolves deaths, unloads, and despawns.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code cleanupOnChunkUnload=true} — an owned actor leaving the level
 *       via chunk unload is discarded (not persisted) and its slot freed; an
 *       owned actor still loaded while the spawner's own anchor chunk is
 *       unloaded is also discarded.</li>
 *   <li>{@code respawnOnDeath=false} — each confirmed death permanently
 *       consumes one quota slot.</li>
 *   <li>An owned entity unresolvable for {@value #MISSING_GRACE_TICKS} ticks is
 *       released — a dead-in-unloaded-chunk actor cannot pin its slot forever.</li>
 *   <li>Spawn definitions are instantiated lazily once per rule under
 *       {@code storynpcs:spawned/<path>} via the canonical create path —
 *       clone-spawns share one definition instead of polluting the registry
 *       per spawn.</li>
 * </ul>
 */
public final class NpcSpawnerRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(NpcSpawnerRuntime.class);

    /** Persistent-data key marking an entity as owned by a template spawner. */
    public static final String OWNER_KEY = "TemplateSpawner";

    private static final int EVAL_PERIOD_TICKS = 20;
    private static final long MISSING_GRACE_TICKS = 400;
    private static final int MAX_PLACEMENT_ATTEMPTS = 8;

    private final Supplier<DefinitionRegistry> registry;
    private final Supplier<StoryNpcsApplicationService> appService;
    private final EventPublisher events;
    private final Supplier<SpawnerRuntimeStore> store;
    /** In-memory state cache — durable store wins on first access. */
    private final Map<String, SpawnerRuntimeState> states = new LinkedHashMap<>();
    private int pruneCountdown;

    public NpcSpawnerRuntime(Supplier<DefinitionRegistry> registry,
                             Supplier<StoryNpcsApplicationService> appService,
                             EventPublisher events,
                             Supplier<SpawnerRuntimeStore> store) {
        this.registry = registry;
        this.appService = appService;
        this.events = events;
        this.store = store;
    }

    /** Per-tick driver — cheap no-op outside each rule's evaluation slot. */
    public void tick(MinecraftServer server) {
        var rules = registry.get().getAllSpawnerRules();
        long now = server.overworld().getGameTime();
        if (--pruneCountdown <= 0) {
            pruneCountdown = EVAL_PERIOD_TICKS * 10;
            pruneStates(rules);
        }
        for (var rule : rules) {
            if (!rule.isEnabled() || !rule.isAnchored()) {
                continue;
            }
            // Deterministic stagger: each rule evaluates once per period on its
            // own slot, so a dense spawner set never bursts in one tick.
            long stagger = Math.floorMod(rule.getId().toString().hashCode(), EVAL_PERIOD_TICKS);
            if (Math.floorMod(now - stagger, EVAL_PERIOD_TICKS) != 0) {
                continue;
            }
            ServerLevel level = levelFor(server, rule);
            if (level == null) {
                continue;
            }
            try {
                evaluate(rule, level, now);
            } catch (RuntimeException e) {
                LOGGER.warn("Spawner {} evaluation failed: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /**
     * Entity-leave hook: resolves the owned-actor ledger for deaths, chunk
     * unloads, and despawns. Deaths free a slot (or permanently consume it
     * under {@code respawnOnDeath=false}); an unload under the cleanup policy
     * discards the actor so it never persists into the chunk data.
     */
    public void onEntityLeaveLevel(
            net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof StoryNpcEntity npc)) {
            return;
        }
        String ownerId = npc.getPersistentData().getString(OWNER_KEY);
        if (ownerId == null || ownerId.isEmpty()) {
            return;
        }
        NamespacedId spawnerId;
        try {
            spawnerId = NamespacedId.of(ownerId);
        } catch (RuntimeException e) {
            return;
        }
        var rule = registry.get().getSpawnerRule(spawnerId).orElse(null);
        var state = stateFor(spawnerId);
        String uuid = npc.getUUID().toString();
        if (!state.getOwnedUuids().contains(uuid)) {
            return;
        }
        Entity.RemovalReason reason = npc.getRemovalReason();
        boolean changed = true;
        if (reason == Entity.RemovalReason.KILLED) {
            state.getOwnedUuids().remove(uuid);
            if (rule == null || !rule.isRespawnOnDeath()) {
                state.setDeaths(state.getDeaths() + 1);
            }
            publish(spawnerId, npc.getUUID(), NpcSpawnerLifecycleEvent.Kind.RELEASED_DEATH);
        } else if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            if (rule != null && rule.isCleanupOnChunkUnload()) {
                npc.discard(); // prevents chunk-data persistence — true despawn
                state.getOwnedUuids().remove(uuid);
                publish(spawnerId, npc.getUUID(),
                        NpcSpawnerLifecycleEvent.Kind.RELEASED_UNLOAD_CLEANUP);
            } else {
                changed = false; // kept — the actor persists and rebinds on reload
            }
        } else {
            state.getOwnedUuids().remove(uuid);
            publish(spawnerId, npc.getUUID(), NpcSpawnerLifecycleEvent.Kind.RELEASED_DESPAWNED);
        }
        if (changed) {
            save(state);
        }
    }

    private void evaluate(SpawnerRule rule, ServerLevel level, long now) {
        BlockPos anchor = new BlockPos(rule.getAnchorX(), rule.getAnchorY(), rule.getAnchorZ());
        var state = stateFor(rule.getId());
        boolean changed = reconcile(rule, state, level, now);

        if (!level.hasChunkAt(anchor)) {
            // Anchor chunk unloaded — no spawns. Under the cleanup policy any
            // owned actor still loaded in a neighbouring chunk despawns too;
            // actors whose own chunk unloaded were resolved by the leave hook.
            if (rule.isCleanupOnChunkUnload()) {
                changed |= discardLoadedOwned(rule.getId(), state, level);
            }
            if (changed) {
                save(state);
            }
            return;
        }

        int effectiveCap = rule.isRespawnOnDeath()
                ? rule.getQuota() : Math.max(0, rule.getQuota() - state.getDeaths());
        if (state.getOwnedUuids().size() >= effectiveCap
                || !rule.shouldSpawn(state.getOwnedUuids().size(), state.getLastSpawnTick(), now)) {
            if (changed) {
                save(state);
            }
            return;
        }

        NamespacedId definitionId = resolveSpawnDefinition(rule, state);
        if (definitionId == null) {
            if (changed) {
                save(state);
            }
            return;
        }
        BlockPos pos = findPlacement(level, anchor, rule);
        if (pos == null) {
            if (changed) {
                save(state);
            }
            return;
        }
        var spawned = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
        if (spawned == null) {
            if (changed) {
                save(state);
            }
            return;
        }
        spawned.setDefinitionId(definitionId.toString());
        spawned.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.random.nextFloat() * 360.0f, 0.0f);
        spawned.getPersistentData().putString(OWNER_KEY, rule.getId().toString());
        if (!level.addFreshEntity(spawned)) {
            LOGGER.warn("Spawner {} spawn rejected by the level", rule.getId());
            if (changed) {
                save(state);
            }
            return;
        }
        state.getOwnedUuids().add(spawned.getUUID().toString());
        state.setLastSpawnTick(now);
        changed = true;
        publish(rule.getId(), spawned.getUUID(), NpcSpawnerLifecycleEvent.Kind.SPAWNED);
        if (changed) {
            save(state);
        }
    }

    /**
     * Ledger reconciliation: living actors reconfirm; unresolvable UUIDs accrue
     * missing-time and are released after the grace bound; anything else frees
     * its slot. Returns whether the state changed.
     */
    private boolean reconcile(SpawnerRule rule, SpawnerRuntimeState state,
                              ServerLevel level, long now) {
        boolean changed = false;
        for (Iterator<String> it = state.getOwnedUuids().iterator(); it.hasNext();) {
            String uuid = it.next();
            Entity entity;
            try {
                entity = level.getEntity(UUID.fromString(uuid));
            } catch (IllegalArgumentException e) {
                it.remove();
                changed = true;
                continue;
            }
            if (entity != null && !entity.isRemoved()) {
                if (state.getMissingSince().remove(uuid) != null) {
                    changed = true;
                }
                continue;
            }
            if (entity != null) { // found but removed — slot frees immediately
                it.remove();
                state.getMissingSince().remove(uuid);
                changed = true;
                continue;
            }
            long since = state.getMissingSince().computeIfAbsent(uuid, k -> now);
            if (now - since > MISSING_GRACE_TICKS) {
                it.remove();
                state.getMissingSince().remove(uuid);
                publish(rule.getId(), UUID.fromString(uuid),
                        NpcSpawnerLifecycleEvent.Kind.RELEASED_MISSING_GRACE);
                changed = true;
            }
        }
        return changed;
    }

    /** Discards owned actors still loaded while the anchor chunk is not. */
    private boolean discardLoadedOwned(NamespacedId spawnerId,
                                       SpawnerRuntimeState state, ServerLevel level) {
        boolean changed = false;
        for (Iterator<String> it = state.getOwnedUuids().iterator(); it.hasNext();) {
            String uuid = it.next();
            Entity entity;
            try {
                entity = level.getEntity(UUID.fromString(uuid));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (entity != null) {
                entity.discard();
                it.remove();
                state.getMissingSince().remove(uuid);
                publish(spawnerId, entity.getUUID(),
                        NpcSpawnerLifecycleEvent.Kind.RELEASED_UNLOAD_CLEANUP);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Lazily instantiates the rule's template into a canonical NPC definition
     * under {@code storynpcs:spawned/<path>}; returns null while the template
     * is unavailable (the spawner stays inert rather than failing).
     */
    private NamespacedId resolveSpawnDefinition(SpawnerRule rule, SpawnerRuntimeState state) {
        if (state.getInstantiatedDefinitionId() != null) {
            var existing = NamespacedId.of(state.getInstantiatedDefinitionId());
            if (registry.get().getNpc(existing).isPresent()) {
                return existing;
            }
        }
        var template = registry.get().getTemplate(rule.getTemplateId()).orElse(null);
        if (template == null || template.getDefinition() == null) {
            return null;
        }
        NamespacedId instId = spawnedIdFor(rule.getId());
        if (registry.get().getNpc(instId).isPresent()) {
            state.setInstantiatedDefinitionId(instId.toString());
            return instId;
        }
        var definition = template.instantiate(instId);
        var service = appService.get();
        if (service == null) {
            return null;
        }
        // Canonical create with the internal "system" actor — same op a command
        // or editor save would take; no registry back door.
        var request = new com.storynpcs.service.MutationRequest(
                "npc.create", "system", "npc.mutate", instId,
                service.currentRevision("npc", instId), UUID.randomUUID());
        var result = service.createNpc(request, definition);
        if (result != null && !result.applied()) {
            LOGGER.warn("Spawner {} could not instantiate template {}: {}",
                    rule.getId(), rule.getTemplateId(), result.formatReport(5));
            return null;
        }
        state.setInstantiatedDefinitionId(instId.toString());
        return instId;
    }

    /** Deterministic spawn-definition id — stable across restarts, collision-safe. */
    public static NamespacedId spawnedIdFor(NamespacedId spawnerId) {
        String path = spawnerId.getPath().replace('/', '_');
        String leaf = "storynpcs".equals(spawnerId.getNamespace())
                ? path : spawnerId.getNamespace() + "_" + path;
        return NamespacedId.of("storynpcs", "spawned/" + leaf);
    }

    /** Deterministic placement: anchor first, then seeded offsets inside the radius. */
    private BlockPos findPlacement(ServerLevel level, BlockPos anchor, SpawnerRule rule) {
        var random = net.minecraft.util.RandomSource.create(
                rule.getId().toString().hashCode() * 31L + level.getGameTime());
        int radius = (int) Math.ceil(rule.getPlacementRadiusBlocks());
        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            BlockPos candidate = attempt == 0 ? anchor : anchor.offset(
                    random.nextInt(radius * 2 + 1) - radius,
                    0,
                    random.nextInt(radius * 2 + 1) - radius);
            var ground = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    candidate);
            if (level.getWorldBorder().isWithinBounds(ground)
                    && !level.getBlockState(ground).isSolidRender(level, ground)) {
                return ground;
            }
        }
        return null;
    }

    private ServerLevel levelFor(MinecraftServer server, SpawnerRule rule) {
        ResourceLocation dim;
        try {
            dim = ResourceLocation.parse(rule.getDimension());
        } catch (RuntimeException e) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dim)) {
                return level;
            }
        }
        return null;
    }

    private SpawnerRuntimeState stateFor(NamespacedId spawnerId) {
        String key = spawnerId.toString();
        var cached = states.get(key);
        if (cached != null) {
            return cached;
        }
        SpawnerRuntimeState state = null;
        var s = store.get();
        if (s != null) {
            try {
                state = s.load(spawnerId).orElse(null);
            } catch (java.io.IOException e) {
                LOGGER.warn("Could not load spawner state {}: {}", key, e.getMessage());
            }
        }
        if (state == null) {
            state = new SpawnerRuntimeState(key);
        }
        states.put(key, state);
        return state;
    }

    /** Drops state records whose rule no longer exists — once per prune pass. */
    private void pruneStates(java.util.List<SpawnerRule> rules) {
        var live = new java.util.HashSet<String>();
        for (var rule : rules) {
            live.add(rule.getId().toString());
        }
        var s = store.get();
        java.util.Set<String> persisted = java.util.Set.of();
        if (s != null) {
            try {
                persisted = new java.util.HashSet<>(s.listIds());
            } catch (RuntimeException e) {
                LOGGER.warn("Could not list spawner states: {}", e.getMessage());
            }
        }
        var candidates = new java.util.HashSet<>(states.keySet());
        candidates.addAll(persisted);
        for (String key : candidates) {
            if (live.contains(key)) {
                continue;
            }
            states.remove(key);
            if (s != null) {
                try {
                    s.delete(NamespacedId.of(key));
                } catch (java.io.IOException e) {
                    LOGGER.warn("Could not delete stale spawner state {}: {}", key, e.getMessage());
                }
            }
            try {
                publish(NamespacedId.of(key), null, NpcSpawnerLifecycleEvent.Kind.STATE_PRUNED);
            } catch (RuntimeException ignored) {}
        }
    }

    private void save(SpawnerRuntimeState state) {
        var s = store.get();
        if (s == null) {
            return;
        }
        try {
            s.save(state);
        } catch (java.io.IOException e) {
            // Durable write lost — in-memory ledger still enforces quotas this
            // run; a restart may over-spawn until ownership re-reconciles.
            LOGGER.warn("Spawner state {} could not persist: {}", state.getSpawnerId(), e.getMessage());
        }
    }

    private void publish(NamespacedId spawnerId, UUID actorUuid,
                         NpcSpawnerLifecycleEvent.Kind kind) {
        var publisher = events;
        if (publisher != null) {
            publisher.publish(new NpcSpawnerLifecycleEvent(spawnerId, actorUuid, kind));
        }
    }
}
