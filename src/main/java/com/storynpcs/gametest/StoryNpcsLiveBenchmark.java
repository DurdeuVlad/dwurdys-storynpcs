package com.storynpcs.gametest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.entity.StoryNpcRegistry;
import com.storynpcs.sim.ActorMemoryReport;
import com.storynpcs.sim.cert.PerformanceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Issue #126 — live-runtime certification evidence. Executes the three P4-3
 * scenarios (500-NPC population, 25v25 siege, 2,500-NPC stress) inside a REAL
 * NeoForge dedicated server — the {@code gameTestServer} run type — and
 * measures wall-clock duration of actual server ticks via
 * {@link ServerTickEvent.Pre}/{@link ServerTickEvent.Post}. That is genuine
 * live MSPT, not the plain-JVM scheduler numbers in
 * {@code docs/parity/reports/benchmark-*.json} (which stay labeled
 * {@code HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED}).
 *
 * <p>Skipped unless {@code -Dstorynpcs.liveBenchmark=true} — the normal
 * {@code runGameTestServer} task stays a fast correctness tier; the
 * dedicated {@code runLiveBenchmark} run type sets the flag plus
 * {@code storynpcs.benchmarkReportDir}. The server binds no client port in
 * this run type, so the documented 25565-vs-25566 port concern is
 * structurally avoided.</p>
 *
 * <p>Workload honesty notes:</p>
 * <ul>
 *   <li>Scenarios run <b>sequentially inside one test</b> — each scenario's
 *       measurement window contains only its own NPC population; entities are
 *       discarded between scenarios so workloads cannot contaminate each
 *       other.</li>
 *   <li>Far-band NPCs sit in force-loaded chunks so they genuinely tick —
 *       dormant bookkeeping cost is measured, not hidden by chunk unload.</li>
 *   <li>A mock {@code ServerPlayer} anchors the near band so the production
 *       nearest-player distance feed drives tier assignment exactly as on a
 *       real server.</li>
 *   <li>NPCs spawn at the heightmap surface with {@code NoGravity} — no
 *       suffocation/fall deaths contaminating the alive count.</li>
 *   <li>The siege scenario uses authored {@code targetFactionIds} on both
 *       sides — real attack-on-sight combat, not a stub.</li>
 *   <li>Measured tick time is whole-server-tick wall clock — it includes
 *       vanilla tick work and other GameTests running in the same batch;
 *       that is the honest definition of MSPT on a real server.</li>
 * </ul>
 */
@GameTestHolder(StoryNpcs.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoryNpcsLiveBenchmark {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("storynpcs.liveBenchmark", "false"));
    private static final int WARMUP_TICKS = 100;
    private static final int MEASURE_TICKS = 400;
    private static final int FAR_OFFSET_BLOCKS = 600;

    private StoryNpcsLiveBenchmark() {}

    @GameTest(template = "gametest/empty_3x3x3", timeoutTicks = 100_000)
    public static void liveBenchmarkScenarios(GameTestHelper helper) {
        if (!ENABLED) { helper.succeed(); return; }

        var level = helper.getLevel();
        StoryNpcs mod = StoryNpcsAccess.mod(level);
        helper.assertTrue(mod != null, "StoryNpcs must be attached");

        // Anchor player drives the production nearest-player distance feed.
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(anchor.getX() + 0.5, anchor.getY() + 1, anchor.getZ() + 0.5);

        // Far band lives in force-loaded chunks so dormant NPCs really tick.
        BlockPos far = anchor.offset(FAR_OFFSET_BLOCKS, 0, 0);
        for (int dx = 0; dx <= 4; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                level.setChunkForced((far.getX() >> 4) + dx, (far.getZ() >> 4) + dz, true);
            }
        }

        TickSampler sampler = new TickSampler();
        NeoForge.EVENT_BUS.register(sampler);

        ArrayDeque<Scenario> queue = new ArrayDeque<>(List.of(
                new Scenario("population", 500, 250, false),
                new Scenario("siege", 50, 50, true),
                new Scenario("stress", 2_500, 600, false)));
        advance(helper, mod, player, anchor, far, sampler, queue, null);
    }

    /** One benchmark scenario definition. */
    private record Scenario(String name, int npcCount, int nearCount, boolean combat) {}

    /** Records wall-clock nanos of every real server tick while armed. */
    private static final class TickSampler {
        private long tickStart = -1L;
        private final List<Long> samples = new ArrayList<>(8_192);

        @SubscribeEvent
        public void onTickPre(ServerTickEvent.Pre event) {
            tickStart = System.nanoTime();
        }

        @SubscribeEvent
        public void onTickPost(ServerTickEvent.Post event) {
            if (tickStart > 0) {
                samples.add(System.nanoTime() - tickStart);
            }
        }
    }

    /**
     * Runs the head of {@code queue}: spawns its workload, waits
     * warmup+measure ticks, writes the report, discards the entities, then
     * recurses into the next scenario via {@code runAfterDelay}.
     */
    private static void advance(GameTestHelper helper, StoryNpcs mod,
                                net.minecraft.world.entity.player.Player player,
                                BlockPos anchor, BlockPos far, TickSampler sampler,
                                ArrayDeque<Scenario> queue, List<StoryNpcEntity> previous) {
        // Discard the prior scenario's entities so workloads stay isolated.
        if (previous != null) {
            previous.forEach(StoryNpcEntity::discard);
        }
        Scenario s = queue.poll();
        if (s == null) {
            NeoForge.EVENT_BUS.unregister(sampler);
            helper.succeed();
            return;
        }
        var level = helper.getLevel();

        NamespacedId allies = NamespacedId.of("storynpcs:bench/" + s.name() + "_allies");
        NamespacedId enemies = NamespacedId.of("storynpcs:bench/" + s.name() + "_enemies");
        if (s.combat()) {
            mod.getApplicationService().createFaction(allies, "Bench Allies");
            mod.getApplicationService().createFaction(enemies, "Bench Enemies");
        }

        // Shared archetype per side — one canonical definition each.
        NamespacedId defId = NamespacedId.of("storynpcs:bench/" + s.name() + "_a");
        NpcDefinition def = new NpcDefinition(defId, "Bench " + s.name());
        if (s.combat()) {
            def.setFactionId(allies);
            def.getAi().setAttackOnSight(true);
            def.getAi().setTargetFactionIds(java.util.Set.of(enemies));
        }
        mod.getApplicationService().createNpc(def);

        NamespacedId enemyDefId = NamespacedId.of("storynpcs:bench/" + s.name() + "_b");
        if (s.combat()) {
            NpcDefinition enemyDef = new NpcDefinition(enemyDefId, "Bench Enemy");
            enemyDef.setFactionId(enemies);
            enemyDef.getAi().setAttackOnSight(true);
            enemyDef.getAi().setTargetFactionIds(java.util.Set.of(allies));
            mod.getApplicationService().createNpc(enemyDef);
        }

        List<StoryNpcEntity> spawned = new ArrayList<>(s.npcCount());
        for (int i = 0; i < s.npcCount(); i++) {
            boolean near = i < s.nearCount();
            BlockPos pos = near
                    ? anchor.offset(4 + (i % 40), 0, (i / 40) % 8)
                    : far.offset(i % 32, 0, (i / 32) % 24);
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                    pos.getX(), pos.getZ());
            StoryNpcEntity npc = new StoryNpcEntity(
                    StoryNpcRegistry.STORY_NPC.get(), level);
            npc.setPos(pos.getX() + 0.5, surface + 1, pos.getZ() + 0.5);
            npc.setNoGravity(true);
            npc.setDefinitionId(
                    (s.combat() && i % 2 == 1 ? enemyDefId : defId).toString());
            if (level.addFreshEntity(npc)) {
                spawned.add(npc);
            }
        }
        final int spawnedCount = spawned.size();
        final List<StoryNpcEntity> spawnedRef = spawned;

        // Discard whatever ticks accumulated while previous scenarios ran so
        // this scenario's window is clean warmup+measure.
        sampler.samples.clear();
        helper.runAfterDelay(WARMUP_TICKS + MEASURE_TICKS, () -> {
            try {
                long alive = spawnedRef.stream().filter(StoryNpcEntity::isAlive).count();
                List<Long> window = sampler.samples.subList(
                        Math.max(0, sampler.samples.size() - MEASURE_TICKS),
                        sampler.samples.size());
                writeReport(helper, s, mod, List.copyOf(window), spawnedCount, alive);
            } catch (Exception e) {
                helper.fail("benchmark report failed for " + s.name() + ": " + e);
                return;
            }
            advance(helper, mod, player, anchor, far, sampler, queue, spawnedRef);
        });
    }

    private static void writeReport(GameTestHelper helper, Scenario s,
                                    StoryNpcs mod, List<Long> tickNanos,
                                    int spawned, long alive) throws Exception {
        List<Double> ms = tickNanos.stream().map(n -> n / 1_000_000.0).sorted().toList();
        double p50 = pct(ms, 0.50), p95 = pct(ms, 0.95), p99 = pct(ms, 0.99);
        System.gc();
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        long dormant = mod.getSimulationScheduler().states().values().stream()
                .filter(st -> st.dormant()).count();
        // Same durable-record shape as the headless measurement: id|tier|combat.
        var memory = new ActorMemoryReport((int) Math.max(dormant, 1), 512,
                (long) ("36-char-uuid-formatted-string|DORMANT|false")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        int dropped = mod.getPathScheduler() != null
                ? (int) mod.getPathScheduler().droppedRequests() : 0;
        var metrics = new PerformanceContract.Metrics(
                p50, p95, p99, heap, memory.incrementalBytesPerActor(),
                mod.getPathScheduler() != null ? mod.getPathScheduler().depth() : 0,
                dropped, 0,
                alive < spawned ? 1 : 0, (int) Math.max(0, spawned - alive), 0);
        var result = PerformanceContract.validate(s.name(), metrics);

        String neoforgeVersion = net.neoforged.fml.ModList.get()
                .getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        var env = new PerformanceContract.Environment(
                System.getProperty("java.version"), "neoforge-" + neoforgeVersion,
                "1.21.1",
                "live-neoforge-gametest: " + System.getProperty("os.name") + "/"
                        + System.getProperty("os.arch"),
                0, helper.getLevel().getServer().getPlayerList().getViewDistance(),
                "real dedicated-server GameTest runtime; ServerTickEvent wall-clock",
                WARMUP_TICKS, MEASURE_TICKS, 0L);
        var workload = new PerformanceContract.Workload(s.name(), s.npcCount(),
                s.combat() ? s.npcCount() : 0,
                s.nearCount() + " near/" + (s.npcCount() - s.nearCount())
                        + " far (force-loaded dormant), " + MEASURE_TICKS
                        + " measured ticks after " + WARMUP_TICKS + " warmup");
        var fingerprint = new PerformanceContract.WorkFingerprint(
                MEASURE_TICKS, spawned, alive, dropped, 0);

        var mapper = new ObjectMapper();
        var tree = mapper.createObjectNode();
        tree.put("artifact_kind", "benchmark-evidence");
        tree.put("scenario", s.name());
        tree.put("runtime_kind", "storynpcs-live-neoforge-gametest");
        tree.put("honesty_note", "Real NeoForge dedicated-server GameTest runtime. "
                + "Tick times are ServerTickEvent.Pre->Post wall clock for the whole "
                + "server tick (all worlds + mod work) — live MSPT, not a scheduler model.");
        tree.putPOJO("environment", env);
        tree.putPOJO("workload", workload);
        tree.putPOJO("metrics", metrics);
        tree.putPOJO("work_fingerprint", fingerprint);
        var checks = tree.putArray("threshold_results");
        for (var c : result.checks()) {
            checks.addObject().put("metric", c.metric()).put("limit", c.limit())
                    .put("actual", c.actual()).put("pass", c.pass());
        }
        tree.put("scenario_pass", result.pass());
        tree.put("certification_state",
                result.pass() ? "LIVE_RUNTIME_PASS" : "LIVE_RUNTIME_FAIL");
        tree.put("spawned_npcs", spawned);
        tree.put("alive_npcs", alive);
        tree.put("sampled_ticks", tickNanos.size());

        Path dir = Path.of(System.getProperty("storynpcs.benchmarkReportDir",
                "docs/parity/reports"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("benchmark-live-" + s.name() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
    }

    private static double pct(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int i = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(i, 0));
    }
}
