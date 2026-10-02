# P4 — Performance foundations progress (P4-1, P4-2, P4-3)

Status: `IN-PROGRESS` for all three issues (local implementation; `SimulationScheduler` is driven from the `StoryNpcs` server tick and goals now consume tier budgets — squad/navigation-request consumption still open).

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
  - `NpcAttackOnSightGoal` coordinates same-faction squads: ally engagement info is collected
    inside the scan the goal already performs (no extra world query); targets engaged by
    allies or already claimed in the shared `(dimension|faction)` coordinator are excluded;
    idle squadmates allocate distinct eligible targets via `SquadCoordinator.coordinate` —
    policy eligibility still gates membership, so the coordinator can never assign a target
    the authored rules reject. Held assignments are reused (no scan-to-scan churn), stale
    claims are pruned via `unassigned`, and removal releases them via
    `releaseSquadAssignment`.
  - `runGameTestServer` fixtures: queued `moveTo` materializes a real path via the drain;
    two same-faction attackers facing two enemies claim distinct targets.
- 9 fixtures covering cap, ordering, cancel paths, determinism, bounds, stale-target detection.

## P4-3 — Certification contract

- `PerformanceContract` — `Environment`/`Workload`/`Metrics`/`BenchmarkReport` records,
  `SCENARIO_THRESHOLDS` for population/siege/stress matching `CUSTOMNPCS_PARITY_CERTIFICATION.md`
  (p95≤35/p99≤45 @500 & 25v25; p95≤45/p99≤50 @2,500; dormant ≤32KiB; queue ≤1,024; zero correctness failures),
  `validate` produces per-metric `Check`s + overall pass/fail, `repeatable` requires ≥3 runs within 10%.
- 5 fixtures covering threshold constants, pass, per-threshold failure, failure reporting, repeatability.

## Explicit limits

- `SimulationScheduler` produces per-actor tier states every 20 server ticks and the
  combat/patrol/follow/sight goals now consume them; `SquadCoordinator` and the
  `PathScheduler` queue are still not wired into entity navigation (P4-2 scope), and
  PERSISTENCE is budgeted but nothing consumes it (entity save is chunk-driven).
- `TierBudgets`/`SimulationTierPolicy` are injectable but have no runtime config
  surface — "configurable" is constructor-level only.
- Non-goal periodic work is unbudgeted: companion wages, social-role scans,
  authored regen, and `NpcJobRuntime` all tick at every tier including DORMANT.
  If dormant CPU bounds become load-bearing, those need capability gates too.
- Tier evaluation cadence is nominally once per second of overworld game time but
  `gameTime` advances once per dimension tick — multiple loaded dimensions shorten
  the interval (evaluation is idempotent, so this is harmless).
- `HeadlessBenchmark` + `SimCertificationBenchmarkTest` produce the `benchmark-*`
  reports in a plain JVM — explicitly labeled `headless-jvm-simulation`, not live
  MSPT; live-server certification evidence remains P4-3 scope (issue #126).

## Verification

`./gradlew cleanTest test --rerun-tasks`: all suites green; dormant-tier GameTest
proves live scheduler→entity→capability wiring. No target-runtime parity claim —
CustomNPCs target runtime evidence remains BLOCKED.
