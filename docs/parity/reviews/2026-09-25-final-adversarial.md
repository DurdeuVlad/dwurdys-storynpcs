# Final adversarial review — 2026-09-25

Scope: whole program (P0-1 … P11-3), prior to release gate. Method: hunt for
places where the implementation could *lie* — silently, partially, or by
overstating evidence. Prior reviews:
[architecture](2026-09-22-architecture.md),
[fixture-runner adversarial](2026-09-23-adversarial-fixture-runner.md),
[completeness](2026-09-23-completeness-review.md),
[completion-retry follow-up](2026-09-24-p1-1-completion-retry-followup.md).
This is the final consistency review required by P0-1/P0-4/P11-3; earlier
findings were dispositioned in the reviews above and the milestone docs.

## High-risk checks performed

| Check | Result |
|---|---|
| Mutable static runtime singletons | `P1ManagedLifecycleTest` source-scans `src/main/java` for non-final static fields; `StoryNpcs.instance` is the sole allowlist entry (single-assignment volatile bootstrap). Scan reports zero violations. |
| Mutations bypassing the canonical boundary | All adapter surfaces (commands, network packets, API, quickstart) route through `StoryNpcsApplicationService` typed ops or `save*`. Verified by call-site audit during P1-1/P1-4 and the capability registry. |
| Definitions as Java-constructed literals | Quickstart builder path removed; bundled starter worlds copy versioned YAML files; `QuickstartLogicTest` scans the quickstart method for `createNpc`/literal definitions. |
| Mutation without authorization | `CapabilityRegistry` binds every `MutationRequest` kind incl. economy (`bank.*`, `trade.execute`) to a named capability; `AuthorizationPolicy` is registry-driven — an unmapped kind fails closed. |
| Crash-safety theatre | Persistence fault harness injects at `SERIALIZE/TMP_WRITE/TMP_FLUSH/RENAME/INDEX_UPDATE` with `never/always/crash` and asserts complete OR fully-rolled-back outcomes; index update restores both artifacts or quarantines. |
| Idempotency theatre | Journal replay tested for bank/trade/follower/quest; per-leg reward marks; `completeQuest` treats already-COMPLETED as idempotent no-op (verified under 24-way concurrent turn-ins). |
| Importer safety | Dry-run writes nothing; `FAIL` conflict policy aborts pre-write; apply rollback restores REPLACE snapshots (`RESTORED`) and records `ROLLBACK_INCOMPLETE` honestly; scripts/decompiled code/binary sources fail closed (`UNSUPPORTED_NEEDS_EVIDENCE`). |
| Benchmark honesty | Thresholds validated per seed via `PerformanceContract.validate`; identical-seed workload fingerprints asserted deterministic; wall-clock p95 repeatability **not claimed** — sub-ms timings are JVM-noise-dominated; state is `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`. |
| Evidence honesty | Fixture runner validates JUnit selectors against real `TEST-*.xml`; compatibility map expansion reaches 2,836/2,836 terminal rows with zero `UNKNOWN`; every behavioral row keeps `UNVERIFIED_TARGET_RUNTIME`. |
| Truth gate | `check_truth_gate.py` scans docs for banned claims; only allowlisted matches (CERTIFICATION.md contract text + P4 threshold record) pass. |
| Dependency graph | Release gate expands milestone shorthand, checks dangling refs, and cycle-checks backward edges; forward ownership references (e.g. P0-4 fixtures owned by P6-4) recorded separately, not treated as blocking deps. |

**Errata (follow-up):** the mutable-statics row is stale — `StoryNpcs.instance`
has since been removed entirely and `ManagedLifecycleScanTest` now enforces
zero allowlist entries; the "sole allowlist entry" description applied to the
bootstrap state at review time, not the current tree.

## Findings (new, this pass)

1. **Register status drift (resolved).** P3-1 carried `IN-PROGRESS` after the
   projection layer landed; corrected to `IN-REVIEW` with the actual residual
   (editor/network coverage, target runtime parity).
2. **Dependency-cycle false positives (resolved).** Naive prose parsing
   reported three cycles; all were *forward* ownership references
   (P0-4 → feature issues that own its fixtures). Graph now separates
   backward dependency edges (cycle-checked) from forward references.

## Residual risks (honest, accepted)

- **No live anything.** No Minecraft client/server, no CustomNPCs runtime
  probe, no wire-level packet fixture. Parity is structural + headless-behavioral.
- **Importer dry-run guarantees** cover the plan/apply path in-JVM; a
  process-killed apply mid-fan-out could leave partial state (sink-level
  atomicity is per-definition, not per-package) — rollback assumes the JVM
  survives.
- **Two authorized exceptions** in truth-gate-exceptions.json carry banned
  words inside legitimate context — both line-hash-pinned.
- Wall-clock repeatability unmeasured-claim: benchmarks record
  `timing_repeatable_within_10pct` truthfully rather than certifying it.

## Verdict

`ADOPT`: local evidence checklist complete; ship as `IN-REVIEW` /
`HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`. Any release label stronger than
that requires live-server and target-runtime evidence that does not yet
exist.
