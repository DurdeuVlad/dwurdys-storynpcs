# P4 — Performance foundations progress (P4-1, P4-2, P4-3)

Status: `DONE-LOCAL` for P4-1 (#62, PR #166), P4-2 (#63, verified no-delta close), P4-3 (#64, contract + headless artifacts + hardened release gate), and #126 live-runtime evidence (this doc's last section — real `runGameTestServer` MSPT artifacts, honest pass/fail per scenario).

## P4-1 — Archetypes, simulation tiers, LOD

- `SimulationTier` {ACTIVE, NEARBY, DISTANT, DORMANT, UNLOADED} + `SimulationTierPolicy`
  — pure-function tier assignment; combat engagement clamps to DISTANT (never dormant).
- `TierBudgets` — per-tier tick periods for sensing/pathing/animation/combat/persistence; `-1` disables.
- `SimulationScheduler` — deterministic evaluation (sorted actor ids), observable `TierTransition`s
  (SPAWNED/DISTANCE/COMBAT_ENGAGE/DESPAWNED), `shouldRun` capability gating, `tierCounts` telemetry.
- `ActorMemoryReport` — separates shared archetype bytes from per-actor incremental bytes
  (the ≤32KiB dormant bound).
- Goal consumption of tier budgets (this slice): `SimulationScheduler.capabilityPeriod`
  returns the effective per-capability period (`-1` disabled/UNLOADED, `1` unevaluated →
  full fidelity, else the tier's budgeted period); `StoryNpcEntity.simulationCapabilityPeriod`
  is the null-safe accessor. Consumers throttle on elapsed game ticks — never AND a
  per-entity countdown with the global `shouldRun` grid, or phases can starve in the
  gap between the two periods (the P3-3 threat-pulse dead-zone pattern).
  - `NpcAttackOnSightGoal` — SENSING: dormant tiers never sight-scan; degraded tiers
    widen the 10-tick scan interval.
  - `NpcMeleeAttackGoal` — PATHING widens repath cadence (disabled → no new paths; the
    in-flight path finishes rather than snapping to a halt); COMBAT widens the authored
    attack cooldown (never faster than authored; disabled → no attacks).
  - `NpcRangedAttackGoal` — SENSING gates the LOS raycast in `canUse`/`canContinueToUse`
    via an elapsed-throttle + cached result keyed on the candidate UUID (a mid-window
    threat flip re-raycasts rather than inheriting the previous target's verdict);
    COMBAT widens volley cadence (disabled → no fire).
  - `NpcPatrolGoal`/`NpcReturnToStartGoal`/`NpcFollowFormationGoal`/`NpcWanderingStrollGoal`
    — PATHING disabled means dormant NPCs stop navigating entirely; a tier downgrade
    mid-navigation halts the in-flight path uniformly (`stop()` → navigation stop);
    patrol/follow recalc cadences widen to the tier period.
  - Net dormant behavior: no sight scans, no path finds, no attacks — the NPC is
    bookkeeping plus its persistence cadence, matching the DORMANT budget contract.
- A `runGameTestServer` fixture injects DORMANT/ACTIVE evaluations against the live
  scheduler and asserts `simulationCapabilityPeriod` disables and restores the goal
  capabilities on a real entity.
- 8 fixtures covering bands, transitions, combat clamp, gating, capabilityPeriod
  semantics, memory separation, aggregation.

## P4-2 — Bounded path scheduling, squad coordination

- `PathScheduler` — bounded queue (default 1024, matching certification bound), priority+tick
  ordered poll, `cancelActor` (unload), `cancelStale` (revision), explicit `QUEUE_FULL` outcome,
  `droppedRequests` metric. Requests carry immutable `PathTarget` snapshots — no live world refs.
- `SquadCoordinator` — deterministic unique-target allocation (no duplicate targets), bounded
  squad (32) and candidate (64) lists, `release`/`unassigned` for retarget cycles.
- Production wiring (this slice):
  - `StoryNpcPathNavigator` intercepts the path-computing `moveTo` overloads and enqueues an
    immutable coordinate snapshot + speed on `mod.getPathScheduler()`; the server-tick drain
    (`PATH_DRAIN_MAX_PER_TICK=64` requests **and** ≤4 ms wall time, whichever hits first)
    executes them on the server thread — no async world access. A queued request reports
    `isDone()==false`/`isInProgress()` so goals never re-issue overlapping requests; a newer
    submission supersedes by request-id; `stop()`/`remove()` cancel; `QUEUE_FULL` drops
    explicitly (counted, never a synchronous fallback that would defeat the bound). Combat
    actors get priority 10 over ambient traffic. Precomputed `moveTo(Path)` calls stay
    synchronous — no pathfinding work to bound. Client-side calls bypass the queue.
    The `tick()` override skips vanilla's `followThePath`/move-control tail while a pending
    request covers an absent or finished `path` — reporting not-done with no live `Path`
    would otherwise dereference null in `path.getNextEntityPos`. A still-live path keeps
    ticking normally until the drain swaps it. Requests carry a monotonic per-navigator
    `revision` and a deterministic `levelKey` (name-UUID of the dimension location), so
    `cancelStale`/`PathTarget` stay honest fields rather than dead payload.
  - `NpcAttackOnSightGoal` coordinates same-faction squads: ally engagement info is collected
    inside the scan the goal already performs (no extra world query); targets engaged by
    allies are excluded and their actor→target claims are **pinned** through
    `coordinate(..., engagedClaims)` so a third scanner can never steal a claim held by an
    ally outside its own scan box; idle squadmates allocate distinct eligible targets —
    policy eligibility still gates membership, so the coordinator can never assign a target
    the authored rules reject. Held assignments are reused (no scan-to-scan churn), stale
    claims are pruned via `unassigned`, and removal releases them via
    `releaseSquadAssignment`.
  - `runGameTestServer` fixtures: queued `moveTo` materializes a real path via the drain
    (absolute-pos target, issued after the spawn landing — `canUpdatePath` requires
    `onGround`, and the drain executes same-tick); two same-faction attackers facing two
    enemies claim distinct targets, with the tier pinned ACTIVE inside `succeedWhen` — a
    playerless GameTest evaluates every NPC DORMANT at the saturated 512-block boundary.
  - Drive-by fix exposed by the new fixtures: `LivingEntity.updateInvisibilityStatus`
    recomputes invisibility from `activeEffects` whenever `effectsDirty` fires, which
    resurfaced hidden-defeat statues — `StoryNpcEntity` now re-pins posture invisibility
    while hidden (latent P3-2 leak the fixtures had never actually run to catch).
- 9 fixtures covering cap, ordering, cancel paths, determinism, bounds, stale-target detection.

- Live configuration (issue #62 close slice): distance bands and all 25
  per-tier capability periods are bounded `RuntimeTunables` keys
  (`sim.band.{active,nearby,distant,dormant}` [1,4096] blocks;
  `sim.budget.<tier>.<capability>` [-1,72000] ticks, -1 disables). The merged
  candidate is validated atomically — an inverted band ordering rejects the
  commit with `SIM_BANDS_NOT_ORDERED` and leaves live config untouched.
  `SimulationTunables` resolves policy/budgets lazily on the view's revision
  (one cached instance per commit — no per-tick re-parse); the scheduler
  resolves policy once per `evaluate()` so a mid-call commit can't split a
  single evaluation. Commits flow through the canonical `config.mutate` op
  on the shared mod instance. `SimulationTunablesTest` covers defaults,
  committed band/period propagation, ordering rejection, bounds rejection,
  and revision caching.

## P4-3 — Certification contract

- `PerformanceContract` — `Environment`/`Workload`/`Metrics`/`BenchmarkReport` records,
  `SCENARIO_THRESHOLDS` for population/siege/stress matching `CUSTOMNPCS_PARITY_CERTIFICATION.md`
  (p95≤35/p99≤45 @500 & 25v25; p95≤45/p99≤50 @2,500; dormant ≤32KiB; queue ≤1,024; zero correctness failures),
  `validate` produces per-metric `Check`s + overall pass/fail, `repeatable` requires ≥3 runs within 10%.
- `PhaseBreakdown` (this slice) records per-phase nanos — evaluate/sensing/path/combat/squad —
  plus an explicit `unattributedNanos` remainder, on every `BenchmarkReport`. Timing uses one
  `nanoTime` pair per phase *per tick* (≈11 calls/tick), not per-actor boundaries, so the
  instrumentation can't dominate the cheap buckets it measures. Each artifact run emits a
  `phase_breakdown` object (per-phase totals, `*_share` of instrumented time,
  `unattributed_ms`, `instrumented_share_of_tick`, and a scope note) so a regression names
  where the time went. JFR/async-profiler would still be worth adding for a *live-server*
  certification (#126) — on the headless harness these counters cover the same surfaces
  without adding an agent dependency, but they can't attribute the live tick's
  uninstrumented remainder (entity AI, chunk IO) the way a real profiler can.
- Artifacts now record `timing_repeatability_observed_pct` — the measured max deviation of the
  3-seed p95 series from its median — as *evidence*; `timing_repeatable_within_10pct` stays `null`
  (wall-clock repeatability remains deliberately unasserted on shared machines; workload
  determinism is asserted via identical `workFingerprint`s per seed).
- The release gate hardens `benchmark_artifacts`: ≥3 runs required per scenario, every run must
  carry a well-formed `phase_breakdown` (dict with numeric evaluate/sensing/path/combat/squad/
  unattributed `_ms` keys), and `timing_repeatability_observed_pct` must be recorded — in
  addition to all threshold checks passing and the `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`
  state.
- 5 fixtures covering threshold constants, pass, per-threshold failure, failure reporting, repeatability.

## Explicit limits

- `SimulationScheduler` produces per-actor tier states every 20 server ticks, the
  combat/patrol/follow/sight goals consume them, and the `PathScheduler`/`SquadCoordinator`
  are now production-wired (navigator interception + sight-goal allocation). The drain's
  wall-time bound is checked *between* requests — it guarantees bounded request count plus
  best-effort 4 ms; a single pathfind can exceed the deadline, and `poll()` is O(n) per
  request (≤64×1024 comparisons worst case, inside the deadline guard). PERSISTENCE is
  budgeted but nothing consumes it (entity save is chunk-driven).
- `TierBudgets`/`SimulationTierPolicy` remain injectable for tests; production
  resolves them live through `SimulationTunables`/`RuntimeTunables` (29 bounded
  keys, atomic validated commits via `config.mutate`).
- Non-goal periodic work is unbudgeted: companion wages, social-role scans,
  authored regen, and `NpcJobRuntime` all tick at every tier including DORMANT.
  If dormant CPU bounds become load-bearing, those need capability gates too.
- Tier evaluation cadence is nominally once per second of overworld game time but
  `gameTime` advances once per dimension tick — multiple loaded dimensions shorten
  the interval (evaluation is idempotent, so this is harmless).
- `HeadlessBenchmark` + `SimCertificationBenchmarkTest` produce the `benchmark-*`
  reports in a plain JVM — explicitly labeled `headless-jvm-simulation`, not live
  MSPT. Live evidence now exists — see the #126 section below.

## #126 — Live-runtime benchmark evidence

- `StoryNpcsLiveBenchmark` (`gametest/` package) is a property-gated GameTest
  that runs the three certification scenarios inside the REAL NeoForge
  dedicated server via the new `runLiveBenchmark` Gradle run type (same
  `gameTestServer` launch target, so no client port is ever bound — the
  documented 25565/25566 concern is structurally avoided). Normal
  `runGameTestServer` runs skip it (`storynpcs.liveBenchmark=true` required).
- Tick timing is measured by a `ServerTickEvent.Pre`/`Post` nanoTime pair —
  wall-clock duration of the actual whole server tick = true live MSPT, not a
  scheduler model. 100 warmup + 400 measured ticks per scenario.
- Workload honesty: scenarios run sequentially in one test (entities discarded
  between them — no cross-contamination); the far band sits in force-loaded
  chunks so dormant NPCs genuinely tick; a mock `ServerPlayer` anchors the
  near band so the production nearest-player distance feed assigns tiers
  exactly as on a real server; NPCs spawn at heightmap surface with
  `NoGravity` (no suffocation/fall deaths); siege uses authored
  `targetFactionIds` on both sides (real attack-on-sight combat).
- Artifacts `docs/parity/reports/benchmark-live-{population,siege,stress}.json`
  carry environment, workload, metrics, threshold checks, spawned/alive
  counts, sampled-tick count, and an explicit `honesty_note` +
  `runtime_kind: storynpcs-live-neoforge-gametest` (never confused with
  target-runtime parity).
- **Measured results (this machine, first run — all NPCs alive, 0 drops):**
  - population (500): p50 4.7 / p95 9.8 / p99 50.9 ms → `LIVE_RUNTIME_FAIL`
    on p99 ≤45 (tail spikes from chunk/GC inside whole-tick wall clock).
  - siege (25v25 combat): p50 0.5 / p95 2.4 / p99 43.5 ms → `LIVE_RUNTIME_PASS`.
  - stress (2,500): p50 53.0 / p95 86.8 / p99 95.6 ms → `LIVE_RUNTIME_FAIL`
    on p95 ≤45 and p99 ≤50.
- The failures are reported, not tuned away: 2,500 loaded entities exceed the
  certified stress threshold on this hardware — vanilla entity tick plus goal
  work is real cost the headless scheduler model never measured. That is a
  performance finding against the certification contract, tracked honestly
  rather than relabeled; target-runtime parity remains BLOCKED either way.

## Verification

`./gradlew cleanTest test --rerun-tasks`: all suites green; dormant-tier GameTest
proves live scheduler→entity→capability wiring. No target-runtime parity claim —
CustomNPCs target runtime evidence remains BLOCKED.
