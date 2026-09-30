# P4 — Performance foundations progress (P4-1, P4-2, P4-3)

Status: `IN-PROGRESS` for all three issues (local implementation; `SimulationScheduler` is driven from the `StoryNpcs` server tick — per-entity goal/navigation consumption still open).

## P4-1 — Archetypes, simulation tiers, LOD

- `SimulationTier` {ACTIVE, NEARBY, DISTANT, DORMANT, UNLOADED} + `SimulationTierPolicy`
  — pure-function tier assignment; combat engagement clamps to DISTANT (never dormant).
- `TierBudgets` — per-tier tick periods for sensing/pathing/animation/combat/persistence; `-1` disables.
- `SimulationScheduler` — deterministic evaluation (sorted actor ids), observable `TierTransition`s
  (SPAWNED/DISTANCE/COMBAT_ENGAGE/DESPAWNED), `shouldRun` capability gating, `tierCounts` telemetry.
- `ActorMemoryReport` — separates shared archetype bytes from per-actor incremental bytes
  (the ≤32KiB dormant bound).
- 6 fixtures covering bands, transitions, combat clamp, gating, memory separation, aggregation.

## P4-2 — Bounded path scheduling, squad coordination

- `PathScheduler` — bounded queue (default 1024, matching certification bound), priority+tick
  ordered poll, `cancelActor` (unload), `cancelStale` (revision), explicit `QUEUE_FULL` outcome,
  `droppedRequests` metric. Requests carry immutable `PathTarget` snapshots — no live world refs.
- `SquadCoordinator` — deterministic unique-target allocation (no duplicate targets), bounded
  squad (32) and candidate (64) lists, `release`/`unassigned` for retarget cycles.
- 9 fixtures covering cap, ordering, cancel paths, determinism, bounds, stale-target detection.

## P4-3 — Certification contract

- `PerformanceContract` — `Environment`/`Workload`/`Metrics`/`BenchmarkReport` records,
  `SCENARIO_THRESHOLDS` for population/siege/stress matching `CUSTOMNPCS_PARITY_CERTIFICATION.md`
  (p95≤35/p99≤45 @500 & 25v25; p95≤45/p99≤50 @2,500; dormant ≤32KiB; queue ≤1,024; zero correctness failures),
  `validate` produces per-metric `Check`s + overall pass/fail, `repeatable` requires ≥3 runs within 10%.
- 5 fixtures covering threshold constants, pass, per-threshold failure, failure reporting, repeatability.

## Explicit limits

- `SimulationScheduler` is driven from the `StoryNpcs` server tick and produces
  per-actor tier states; `SquadCoordinator` and per-entity goal/navigation
  consumption of those tiers remain open.
- No runnable benchmark harness or scenario worlds exist yet; the contract defines the
  pass/fail gate but the measurement environment is not implemented.

## Verification

`./gradlew test`: 63 suites, 537 tests, 0 failures. `git diff --check` clean. No live MC testing.
