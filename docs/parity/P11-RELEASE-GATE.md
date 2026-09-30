# Release gate checklist — P11-3 (#93)

Machine-checked report: [`reports/release-gate-report.json`](reports/release-gate-report.json)
(`tools/parity/release_gate.py`). Current status: **gate=BLOCKED**. Headless benchmark
results and JVM/GameTest observations do not satisfy the target-runtime or issue-closure gates.

## Machine checks (release_gate.py)

- [x] All milestones/issues register with **unique issue IDs** (47 issues, no duplicates).
- [x] Dependency shorthand (`M0-M10`, `P n-n`) **expands to registered issue IDs** — no dangling edges.
- [x] Dependency graph **acyclic** on backward edges; forward ownership references recorded separately.
- [ ] Every issue reaches a completed or user-accepted blocked state — `IN-REVIEW` is not terminal and an unaccepted `BLOCKED` issue does not satisfy closure.
- [x] All **15 operation families** have evidence-backed fixtures — each matrix row has closing issues, catalog fixtures, and non-empty JUnit selectors.
- [x] All **22 domains have no unmapped rows** — compatibility report: 2,836/2,836 terminal, zero `UNKNOWN`.
- [x] All **three headless benchmark scenarios** produce artifacts recording numeric threshold results — `benchmark-{population,siege,stress}.json` with per-seed `threshold_results` and `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`. This does not satisfy target-runtime gates.

## Human checks (this review)

- [x] Full `./gradlew cleanTest test --rerun-tasks --console=plain` via the fixture runner — **81 suites / 753 JUnit cases / BUILD SUCCESSFUL**.
- [x] Python parity suite — **125 tests passed** across evidence/compat/fixture/harness/truth/gate tools.
- [x] Importer workflow — `MigrationImportTest` 18 tests: golden, conflicts, stale-plan isolation, unsupported fields, malformed input, dry-run, rollback, idempotency, and template application.
- [x] Migration path — schema-v0→v1 fixtures for all YAML families; importer applies through the canonical boundary (`ImportSink`).
- [x] Permissions — `CapabilityRegistry` covers every `MutationRequest` kind incl. economy; registry-driven `AuthorizationPolicy` fails closed on unmapped kinds.
- [x] Local NeoForge GameTests — 2/2 in-world tests pass; an intentionally failing assertion makes the task exit non-zero.
- [ ] Target-runtime parity and live client/server gates — unavailable; local GameTests do not establish CustomNPCs parity.
- [x] Documentation — quickstart, migration guide, examples all parse under the real loader (`CreatorDocsExamplesTest`); truth gate `PASS`.
- [x] Adversarial review — final review at [`reviews/2026-09-25-final-adversarial.md`](reviews/2026-09-25-final-adversarial.md); verdict ADOPT with honest residuals.
- [x] Blocked/unsupported probes visible in [`../RELEASE-NOTES.md`](../RELEASE-NOTES.md).

## Explicitly NOT certified (blocks any stronger claim)

- P11-3 completion — blocked until the dependency closure and user-accepted blocker decisions are complete.
- Target-runtime parity — no CustomNPCs probe exists; `UNVERIFIED_TARGET_RUNTIME` everywhere it matters.
- Live-server certification — headless benchmarks and GameTests are not live-server or target-runtime evidence; `timing_repeatable_within_10pct` remains `null`.
- GUI/packet wire behavior at runtime; CustomNPCs world/NBT import (`UNSUPPORTED_NEEDS_EVIDENCE`).
