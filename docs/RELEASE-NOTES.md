# Release notes — StoryNPCs parity build (local)

Status: `IN-REVIEW` — implementation complete locally; **parity with the
target runtime is NOT certified**. This document exists so unsupported claims
cannot slip into a release announcement.

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
  (25/25 OBSERVED, 0 blocked, 0 failed), compatibility report
  (2,836/2,836 rows mapped to terminal states), three headless benchmark
  artifacts, release-gate report.
- Verified test base: **81 suites / 710 JUnit tests / 0 failures** (1 skipped)
  plus the Python parity toolchain (122 tests).

## Explicitly blocked / unverified — keep visible

- **Target-runtime parity: BLOCKED.** No CustomNPCs runtime probe exists;
  every behavioral manifest row records `UNVERIFIED_TARGET_RUNTIME`.
  Nothing in this build claims runtime equivalence with the target.
- **Live-server certification: UNVERIFIED.** Benchmarks are headless-JVM
  scheduler runs (`HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`), not live MSPT.
- **GUI/packet wire behavior: unverified at runtime** — domain models and
  protocol contracts are tested headlessly; no rendered-screen or live
  client-server fixtures exist.
- **CustomNPCs world/NBT import: UNSUPPORTED** — declared needs-input until
  a real export sample exists; the importer reports it instead of guessing.
- **Intentional deviations** (recorded in the compatibility map): unified
  authoring hub instead of 149 GUI classes; modernized protocol instead of
  undocumented packet formats; `/storynpcs` typed grammar instead of
  `/noppes`; directed-graph dialogue instead of option arrays; typed-hook
  scripting instead of arbitrary script source.
