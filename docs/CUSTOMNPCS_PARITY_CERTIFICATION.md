# CustomNPCs parity certification contract

Status: `REVISION-7 / EVIDENCE-REFRESHED` — the third independent current-cycle completeness review returned `FAIL`; its inventory/fixture identity, execution-layer, owner mapping, order, and acceptance-threshold corrections are applied. P0-1 exact-source v4 generation was reproduced from the supplied JAR and pinned research tree after expanding the quest-progression regression scope. P0-4 now has 86 mapped selectors across 20 observed fixtures, with 5 fixture selector lists still empty; all 25 target-runtime probes remain unavailable. P1-1 and P1-2 remain open.

This contract resolves the acceptance ambiguities found by the three independent reviews. It is subordinate to the user goal, repository `AGENTS.md`, the target traceability register, and the issue register.

## Evidence gate

Every parity row has one of these states:

1. `VERIFIED_TARGET_SOURCE` — target symbol/inventory evidence only.
2. `VERIFIED_TARGET_RUNTIME` — behavior observed in the exact supplied JAR runtime.
3. `VERIFIED_STORYNPCS_RUNTIME` — behavior observed in StoryNPCs.
4. `VERIFIED_PARITY` — target and StoryNPCs observations match at the defined boundary.
5. `UNVERIFIED_TARGET_RUNTIME` — target behavior remains unprobed.
6. `INTENTIONAL_DEVIATION` — StoryNPCs intentionally improves or changes behavior, with rationale and migration/documentation impact.
7. `UNKNOWN` — missing evidence; this blocks certification.

`UNVERIFIED_TARGET_RUNTIME` does not block implementation of an issue. It blocks only the corresponding `VERIFIED_PARITY` claim and final certification. A target runtime failure must include the exact cause, attempted command, environment, and next evidence needed.

## Performance contract

### Test environment

- Minecraft/NeoForge version: the repository’s declared 1.21.1 target.
- Java: the repository-supported Java runtime, recorded in every report.
- Dedicated server: 4 vCPU, 8 GiB RAM, fixed CPU governor, no client rendering.
- Simulation distance: 8; view distance: 10; fixed world seed; fixed mod list with versions.
- Each run: 5-minute warmup followed by 10-minute measurement, repeated three times.
- Metrics: MSPT p50/p95/p99, heap after GC, incremental active/dormant actor memory, path queue depth, packet count, entity correctness, dropped requests, and error events.

### Baseline acceptance thresholds

These are the initial release thresholds authorized for the roadmap; any change requires a recorded issue decision and new evidence.

| Scenario | Workload | MSPT threshold | Memory/queue/correctness threshold |
|---|---|---:|---|
| Population | 500 NPCs: 250 active within 64 blocks, 250 dormant within 256 blocks | p95 ≤35ms, p99 ≤45ms | dormant incremental actor memory ≤32KiB; zero lost/duplicated lifecycle events |
| Siege | 25v25 active combat with melee/ranged/threat/path work | p95 ≤35ms, p99 ≤45ms | zero lost/duplicated damage or reward events; path queue ≤1,024 |
| Stress | 2,500 NPCs with configured LOD distribution | p95 ≤45ms, p99 ≤50ms | no unbounded queue/reference growth; zero correctness failures; memory trend stable over 10 minutes |

Failure of a threshold is a failed benchmark, not permission to weaken the feature or silently change the workload.

## Creator UI viewport contract

The minimum supported creator viewport is an 854x480 physical display with Minecraft GUI scale 2, yielding at least a 427x240 logical GUI viewport. Acceptance automation must complete the named workflow at that viewport without clipped or unreachable controls; long forms must use scrolling or pagination. Tests must record physical resolution, GUI scale, and resulting logical dimensions.

## Scripting budget contract

Initial sandbox limits for P9-2 are:

- `tick` hook: at most 1ms wall time and 20,000 VM instructions per invocation.
- Other hooks: at most 5ms wall time and 100,000 VM instructions per invocation.
- Aggregate script scheduler: at most 4ms of script execution per server tick; excess ready work is fairly deferred to later ticks.
- Per script: 1MiB live memory, recursion depth 16, and at most 32 canonical operation calls per hook.

Instruction counters and fake clocks provide deterministic quota tests. Benchmarks also record actual wall time, memory, deferred work, and whether the server remains within the M4 MSPT thresholds. A threshold change requires a recorded issue decision and new benchmark evidence.

## Creator-tool contract

For each target tool family, acceptance must show:

- the creator workflow from selection to preview to commit;
- server permission and actor identity;
- invalid-target and stale-revision behavior;
- multiplayer/session isolation;
- persistence and restart behavior;
- undo/rollback or an explicit irreversible-operation warning;
- YAML, command/API, UI, event, and fixture mapping;
- exact unsupported-field report.

## AI-authoring contract

The first supported AI workflow is an external CLI/API patch-plan workflow, not an autonomous in-game model. It consists of:

1. versioned schema/reference bundle;
2. deterministic `StoryNpcsPatchPlan` with source references and expected revisions;
3. dry-run validator with field-path diagnostics and dependency graph;
4. allowlisted canonical operation application;
5. idempotency, permission, audit, and rollback behavior;
6. golden examples covering a complete NPC, dialogue, quest, faction, role, and combat setup.

An in-game assistant is optional and may only submit the same validated patch plan.

## Import contract

The supplied JAR is an engine artifact, not creator-authored world data. The initial importer supports only explicitly documented inputs:

- StoryNPCs YAML/JSON definitions;
- versioned StoryNPCs template packages;
- CustomNPCs export/world data only after an actual sample is supplied and its format is verified.

No importer may claim to migrate target world data based only on the target engine JAR. Unsupported scripts/assets/fields are reported and quarantined; the source and previous StoryNPCs state remain unchanged on failure.

## Pull-request contract

Every implementation PR must include:

- Intent and affected creator/player/operator outcome;
- Expectation and observable success/failure behavior;
- finite acceptance criteria with evidence links;
- non-code context, target compatibility state, and why-now rationale;
- scope, non-goals, ownership, issue ID, and dependencies;
- tests, live fixtures, security checks, migration/recovery checks, performance impact, rollout/rollback;
- unsupported checks and residual risks;
- confirmation that all mutations still route through `StoryNpcsApplicationService` and no mutable runtime static state was added.

## Three-review / three-fix ledger

| Review | Independent lens | Verdict | Fix applied |
|---|---|---|---|
| Review 1 | Target-domain coverage against 15 operations/22 domains | Fail: broad issues omitted field/surface-specific coverage; jobs, commands, networking, marks, transport and tools were under-specified | Fix 1: added `CUSTOMNPCS_PARITY_TRACEABILITY.md` with target inventory, domain/operation mapping, surface columns, and evidence states |
| Review 2 | Flux issue handoff completeness and repository constraints | Fail: unresolved decisions, non-finite acceptance, weak rollback/security details, incomplete issue metadata, dispatcher placement ambiguity | Fix 2: added `CUSTOMNPCS_PARITY_ISSUE_REGISTER.md` with 42 granular issues and all mandatory handoff sections |
| Review 3 | Product fidelity, CustomNPCs direction, UI/AI/MMO performance | Fail: unsupported claims, unbounded certification, missing importer/AI/network/per-operation recovery contracts, and Citizens drift risk | Fix 3: added this certification contract, explicit thresholds/evidence rules/AI/import/PR gates, and roadmap review ledger |

## Current implementation-flow review cycle

| Review | Independent lens | Verdict | Corrective fix | Durable evidence |
|---|---|---|---|---|
| Review 4 | Research and issue completeness | Fail: fixture symbols/issue IDs were not semantically resolved and several target surfaces were absent from the register | Fixture harness now validates exact manifest symbols and registered issue IDs; catalog mappings were corrected; P8-4/P8-5/P8-6/P9-4/P9-5 were added | `parity/reviews/2026-09-22-research-completeness.md` |
| Review 5 | Architecture and milestone completeness | Fail: mutable static lifecycle state, hardcoded quickstart definitions, incomplete canonical mutation boundaries, and missing M4 scale controls remained | Stable trade listing identities and contract-bound subjects were implemented; unresolved architecture items were reopened rather than certified | `parity/reviews/2026-09-22-architecture.md` |
| Review 6 | Adversarial P2-3 transaction audit | Fail: duplicate transport admission, index-keyed trade state, and crash windows across trade/quest/bank remained | Duplicate request classification and client request-ID reuse were added; listing usage is now durable by stable identity; remaining crash windows stay explicit | `parity/reviews/2026-09-22-adversarial-p2-3.md` |
| Review 7 | Third independent current-cycle plan-completeness audit | Fail: 2,836 inventory IDs masqueraded as fixture IDs; all 24 fixtures required target runtime; role/job owners and one dependency sequence were wrong; scripting/UI limits were not finite; roadmap retained actionable-sounding superseded issues | Fix 7: separate inventory IDs from resolvable fixture refs; validate the 22/15 crosswalk; make target runtime evidence-only; map role/operation owners precisely; block runner success for the nine unmapped JUnit fixtures; correct sequence, UI/scripting thresholds, historical roadmap warning, and P2-1 quickstart YAML acceptance | `parity/reviews/2026-09-23-completeness-review.md` |

## Final handoff rule

The issue register is ready for implementation flow after these three fixes. Final product certification is not complete until P11-3 passes; the plan must never convert static target inventory or green unit tests into a false claim of CustomNPCs parity.
