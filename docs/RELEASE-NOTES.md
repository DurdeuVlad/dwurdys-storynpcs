# Release notes — StoryNPCs v0.11.0-beta.2

Status: `BETA` — the parity program's implementation work is merged;
**parity with the target runtime is NOT certified**. Live-server evidence
is now being *recorded* rather than merely awaited — and the recorded
numbers failed thresholds on two scenarios (see below). This document
exists so unsupported claims cannot slip into a release announcement.

## What this build delivers

- All 39 roadmap issues (P0-1 … P11-3) implemented in the local worktree:
  canonical typed operations, managed actor lifecycle, capability
  authorization, versioned YAML definitions, durable persistence with
  recovery, display/combat/AI/targeting/inventory domains, simulation tiers +
  bounded scheduling, directed-graph dialogue + opaque choice tokens, six
  quest repeat modes + mail/team/overflow, faction matrix + roles + jobs +
  transport + companions, transactional trader/bank, creator tools, public
  API + bounded scripting, command/admin contracts, authoring hub + AI patch
  plans, creator docs, and the import contract.
- Evidence artifacts under `docs/parity/reports/`: fixture report
  (41/41 OBSERVED, 0 blocked, 0 failed), compatibility report
  (2,836/2,836 rows mapped to terminal states), three headless benchmark
  artifacts + three live-runtime benchmark artifacts, release-gate report.
- Verified test base: **155 suites / 1,503 JUnit tests / 0 failures**
  plus the Python parity toolchain (162 tests).
- New in beta.2: the entire authoring/player UI migrated onto the shared
  `client/ui` kit (theme tokens, retained-mode widgets, deterministic
  layout engine, post-mutation refresh contract); CustomNPCs SNBT
  text-export import (`CnpcTextExportTranslator`) with a provenance-
  recorded real-export corpus; live-dedicated-server benchmark runs wired
  into the release gate; rendered-screen audit covering 21 screens on a
  real client + server (`docs/beta/screens/AUDIT.md`).
- From beta.1: `/storynpcs beta` tester surface — `beta report` writes a
  self-contained diagnostic snapshot to `world/storynpcs/beta/reports/`,
  `beta feedback <text>` appends to `beta/feedback.log`; both are
  permission-free so any tester can use them (`docs/beta-testing.md`).

## Explicitly blocked / unverified — keep visible

- **Target-runtime parity: BLOCKED.** No CustomNPCs runtime probe exists;
  every behavioral manifest row records `UNVERIFIED_TARGET_RUNTIME`.
  Nothing in this build claims runtime equivalence with the target.
- **Live-server performance: recorded and FAILING.** The live GameTest
  benchmark wrote real MSPT for all three scenarios
  (`benchmark-live-*.json`): siege passed, population missed p99
  (50.9 ms), stress missed p95 and p99 (86.8 / 95.6 ms) on the dev box
  (Windows 11, NeoForge 21.1.248, MC 1.21.1). The release gate reports
  `LIVE_RUNTIME_FAIL` — honest evidence, not a hidden failure. Headless
  artifacts remain `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED` scheduler runs.
- **GUI/packet wire behavior: rendered evidence now exists for 21
  screens** (real client + dedicated server, post-migration re-capture).
  Paths outside the audited journeys remain unverified at runtime.
- **CustomNPCs import: partially supported.** SNBT clone text exports
  import to npc YAML with field-level MIGRATED/UNSUPPORTED accounting;
  dialogs/quests quarantine with re-scope reasons; binary `.dat` world
  stores remain `UNSUPPORTED_NEEDS_EVIDENCE`.
- **Intentional deviations** (recorded in the compatibility map): unified
  authoring hub instead of 149 GUI classes; modernized protocol instead of
  undocumented packet formats; `/storynpcs` typed grammar instead of
  `/noppes`; directed-graph dialogue instead of option arrays; typed-hook
  scripting instead of arbitrary script source.
