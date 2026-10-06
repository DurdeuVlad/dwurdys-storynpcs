package com.storynpcs.runtime.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.P85OrchestrationEvents.NaturalSpawnEvent;
import com.storynpcs.creator.spawn.NaturalSpawnRule;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;

/**
 * Natural-spawn driver (P8-5): periodic weighted evaluation of enabled rules.
 * Each spawned actor carries a {@code storynpcs:naturalspawn:<ruleId>} tag so
 * the per-dimension quota counts only this rule's living actors — no
 * uncontrolled amplification. Placement is deterministic per (rule, tick)
 * through a seeded {@link RandomSource}, same as the spawner driver.
 */
public final class NaturalSpawnRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(NaturalSpawnRuntime.class);

    /** Global evaluation cadence — every 200 ticks per dimension. */
    public static final long EVAL_INTERVAL_TICKS = 200;
    /** Spawn candidates land in a ring beyond minPlayerDistanceBlocks. */
    private static final int CANDIDATE_RADIUS_EXTRA = 24;
    private static final int PLACEMENT_ATTEMPTS = 8;
    private static final String TAG_PREFIX = "storynpcs:naturalspawn:";

    private final Supplier<DefinitionRegistry> registry;
    private final Supplier<StoryNpcsApplicationService> service;
    private final Supplier<EventPublisher> events;

    public NaturalSpawnRuntime(Supplier<DefinitionRegistry> registry,
            Supplier<StoryNpcsApplicationService> service,
            Supplier<EventPublisher> events) {
        this.registry = registry;
        this.service = service;
        this.events = events;
    }

    /** Per-dimension evaluation pass — called from the server tick drain. */
    public int tick(ServerLevel level, long nowTick) {
        if (nowTick % EVAL_INTERVAL_TICKS != 0) {
            return 0;
        }
        var rules = rulesFor(level);
        if (rules.isEmpty() || level.players().isEmpty()) {
            return 0;
        }
        // Weighted pick of ONE eligible rule per eval — seeded per (dim, tick)
        // so selection is deterministic and the rule set cannot amplify into
        // one-spawn-per-rule-per-pass.
        var eligible = new ArrayList<NaturalSpawnRule>();
        for (var rule : rules) {
            int live = countLive(rule, level);
            if (rule.eligible(live)) {
                eligible.add(rule);
            }
        }
        var random = RandomSource.create(
                level.dimension().location().toString().hashCode() * 31L + nowTick);
        var picked = weightedPick(eligible, random);
        if (picked == null) {
            return 0;
        }
        var anchor = pickAnchor(level, picked, random);
        if (anchor == null) {
            return 0;
        }
        var defId = resolveDefinition(picked);
        if (defId == null) {
            return 0;
        }
        return spawn(picked, level, defId, anchor) ? 1 : 0;
    }

    private List<NaturalSpawnRule> rulesFor(ServerLevel level) {
        String dim = level.dimension().location().toString();
        var out = new ArrayList<NaturalSpawnRule>();
        for (var rule : registry.get().getAllNaturalSpawns()) {
            if (rule.getDimensionId() == null
                    || rule.getDimensionId().toString().equals(dim)) {
                out.add(rule);
            }
        }
        return out;
    }

    /** Living actors tagged to this rule in this level — the quota check. */
    private int countLive(NaturalSpawnRule rule, ServerLevel level) {
        int count = 0;
        for (EntityView e : taggedViews(level)) {
            if (e.tags().contains(TAG_PREFIX + rule.getId())) {
                count++;
            }
        }
        return count;
    }

    /** Deterministic weighted pick over eligible rules; {@code null} when the set is empty. */
    public static NaturalSpawnRule weightedPick(List<NaturalSpawnRule> eligible, RandomSource random) {
        int totalWeight = 0;
        for (var rule : eligible) {
            totalWeight += rule.getWeight();
        }
        if (totalWeight <= 0) {
            return null;
        }
        int roll = random.nextInt(totalWeight);
        for (var rule : eligible) {
            roll -= rule.getWeight();
            if (roll < 0) {
                return rule;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    /** Pick a spawn position in the [minDist, minDist+24] ring using the tick-seeded stream. */
    private BlockPos pickAnchor(ServerLevel level, NaturalSpawnRule rule, RandomSource random) {
        var players = level.players();
        var player = players.get(random.nextInt(players.size()));
        double minDist = rule.getMinPlayerDistanceBlocks();
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minDist + random.nextInt(CANDIDATE_RADIUS_EXTRA + 1);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
            var ground = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, level.getMinBuildHeight(), z));
            if (ground.getY() <= level.getMinBuildHeight()) {
                continue;
            }
            if (!level.getWorldBorder().isWithinBounds(ground)
                    || level.getBlockState(ground).isSolidRender(level, ground)) {
                continue;
            }
            if (rule.getBiomeId() != null
                    && !level.getBiome(ground).unwrapKey()
                            .map(k -> k.location().toString().equals(rule.getBiomeId().toString()))
                            .orElse(false)) {
                continue;
            }
            if (player.distanceToSqr(ground.getX(), ground.getY(), ground.getZ())
                    < minDist * minDist) {
                continue;
            }
            return ground;
        }
        return null;
    }

    /** Instantiate the rule's template through the canonical create path. */
    private NamespacedId resolveDefinition(NaturalSpawnRule rule) {
        var template = registry.get().getTemplate(rule.getTemplateId()).orElse(null);
        if (template == null || template.getDefinition() == null) {
            return null;
        }
        NamespacedId defId = NamespacedId.of("storynpcs",
                "naturalspawn/" + rule.getId().getPath().replace('/', '_'));
        if (registry.get().getNpc(defId).isPresent()) {
            return defId;
        }
        var svc = service.get();
        if (svc == null) {
            return null;
        }
        var definition = template.instantiate(defId);
        var request = new com.storynpcs.service.MutationRequest(
                "npc.create", "system", "npc.mutate", defId,
                svc.currentRevision("npc", defId), UUID.randomUUID());
        var result = svc.createNpc(request, definition);
        if (result != null && !result.applied()) {
            LOGGER.warn("Natural-spawn {} could not instantiate template {}: {}",
                    rule.getId(), rule.getTemplateId(), result.formatReport(5));
            return null;
        }
        return defId;
    }

    private boolean spawn(NaturalSpawnRule rule, ServerLevel level,
                          NamespacedId defId, BlockPos pos) {
        var entity = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
        if (entity == null) {
            return false;
        }
        entity.setDefinitionId(defId.toString());
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.random.nextFloat() * 360.0f, 0.0f);
        entity.getPersistentData().putString(TAG_PREFIX + rule.getId(), "1");
        if (!level.addFreshEntity(entity)) {
            LOGGER.warn("Natural-spawn {} rejected by the level", rule.getId());
            return false;
        }
        var pub = events.get();
        if (pub != null) {
            pub.publish(new NaturalSpawnEvent(rule.getId(), entity.getUUID(),
                    pos.toShortString()));
        }
        return true;
    }

    /** Entity-tag view seam — production scans the level's entities. */
    public record EntityView(java.util.Set<String> tags) {}

    private List<EntityView> taggedViews(ServerLevel level) {
        var out = new ArrayList<EntityView>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof StoryNpcEntity) {
                out.add(new EntityView(entity.getPersistentData().getAllKeys().stream()
                        .filter(k -> k.startsWith(TAG_PREFIX))
                        .collect(java.util.stream.Collectors.toSet())));
            }
        }
        return out;
    }
}
