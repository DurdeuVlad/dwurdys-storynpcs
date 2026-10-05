# #126 — Live-runtime benchmark evidence (CLOSEOUT)

Status: **DONE** — real NeoForge dedicated-server benchmark evidence produced
and recorded honestly, including failing results.

## What was delivered

- `src/main/java/com/storynpcs/gametest/StoryNpcsLiveBenchmark.java` — a
  property-gated GameTest (`-Dstorynpcs.liveBenchmark=true`) that runs the
  three P4-3 certification scenarios inside the real `gameTestServer` run
  type. Skipped on ordinary `runGameTestServer` runs so the correctness tier
  stays fast.
- `build.gradle` — new `liveBenchmark` run type on the same `gameTestServer`
  launch target plus the flag and an absolute `storynpcs.benchmarkReportDir`
  pointing at `docs/parity/reports`. No client port is bound in this run
  type; the 25565-vs-25566 concern from the issue is structurally avoided.
- `docs/testing.md` — documents the run as Tier 2b.
- `docs/parity/reports/benchmark-live-{population,siege,stress}.json` —
  machine-readable artifacts with environment (Java/NeoForge/MC/hardware/
  distances/warmup/run), workload, p50/p95/p99 MSPT, heap, dormant-memory,
  queue depth/drops, correctness counters, threshold checks, spawned vs
  alive NPC counts, and an explicit `honesty_note`.

## How the measurement works

A `ServerTickEvent.Pre`/`Post` `nanoTime` pair records the wall-clock
duration of every real server tick while armed. That is genuine live MSPT —
whole-tick cost including vanilla entity tick, goal work, scheduler drain —
not the plain-JVM scheduler model the headless `benchmark-*.json` artifacts
measure (those stay labeled `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`).

Workload design, for honesty:

- Scenarios run **sequentially inside one test**; entities are discarded
  between scenarios so workloads cannot contaminate each other (an earlier
  concurrent-batch run misattributed ~3,050 NPCs to every scenario and was
  discarded).
- The far band is placed in **force-loaded chunks** so dormant NPCs
  genuinely tick — dormant bookkeeping cost is measured, not hidden by
  chunk unload.
- A mock `ServerPlayer` anchors the near band so the production
  nearest-player distance feed drives tier assignment exactly as on a real
  server.
- NPCs spawn at the heightmap surface with `NoGravity` — the first
  iteration lost actors to wall suffocation; now alive == spawned for all
  scenarios.
- The siege scenario uses authored `targetFactionIds` on both sides — real
  attack-on-sight combat, not a stub.
- 100 warmup + 400 measured ticks per scenario; the report window is the
  last 400 samples.

## Measured results (Windows 11 / amd64, Java 21.0.12, NeoForge 21.1.248)

| scenario  | spawned/alive | p50   | p95   | p99   | verdict            |
|-----------|---------------|-------|-------|-------|--------------------|
| population| 500/500       | 4.7ms | 9.8ms | 50.9ms| LIVE_RUNTIME_FAIL  |
| siege     | 50/50         | 0.5ms | 2.4ms | 43.5ms| LIVE_RUNTIME_PASS  |
| stress    | 2,500/2,500   | 53.0ms| 86.8ms| 95.6ms| LIVE_RUNTIME_FAIL  |

- population passes p50/p95 comfortably but fails the p99 ≤45ms bound —
  whole-tick wall clock includes chunk-load/GC tail spikes.
- siege passes all thresholds.
- stress fails p95 ≤45ms and p99 ≤50ms: 2,500 loaded entities cost more
  than the certified bound on this hardware. Vanilla entity tick plus goal
  scheduling at that population is real cost the headless model never
  carried. This is a genuine performance finding against the certification
  contract — recorded, not tuned away. Queue drops: 0; correctness
  failures: 0; all spawned NPCs alive.

## Honesty boundaries

- `LIVE_RUNTIME_PASS`/`FAIL` certifies StoryNPCs' own runtime on this
  machine only. It is **not** CustomNPCs target-runtime parity — target
  parity remains BLOCKED until authorized target-runtime evidence exists.
- Single-run evidence; the contract's ≥3-run repeatability claim is not
  asserted.
- Whole-tick measurement includes other concurrently running GameTests'
  work in the same batch — the honest definition of MSPT on a real server,
  recorded in each artifact's `honesty_note`.

## Verification

- `./gradlew runLiveBenchmark` — BUILD SUCCESSFUL, all 10 tests passed
  (9 correctness + benchmark), exit 0, no manual interaction.
- `./gradlew test` — green; fixture suite: 1,207 cases, 41 probes, 0 failed.
- `release_gate.py` — `benchmark_artifacts` PASS; overall gate BLOCKED only
  by legitimate open work (M10 issues, P11-3, target-runtime evidence).
