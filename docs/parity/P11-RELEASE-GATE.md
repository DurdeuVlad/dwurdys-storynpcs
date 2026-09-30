# Release gate checklist — P11-3 (#93)

Machine-checked report: [`reports/release-gate-report.json`](reports/release-gate-report.json)
(`tools/parity/release_gate.py`). Current status: **gate=PASS** for the local
evidence checklist — with `HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED` as the
certified state. This checklist mirrors every gate item plus the human items
the tool cannot judge.

## Machine checks (release_gate.py)

- [x] All milestones/issues register with **unique issue IDs** (47 issues, no duplicates).
- [x] Dependency shorthand (`M0-M10`, `P n-n`) **expands to registered issue IDs** — no dangling edges.
- [x] Dependency graph **acyclic** on backward edges; forward ownership references recorded separately.
- [x] Every issue reaches a **terminal local status** — all 47 are `IN-REVIEW`/`DONE-LOCAL` via register status or progress/closeout doc; none lack a local status. `IN-REVIEW` certifies local implementation under review — it does **not** certify issue closure or target parity.
- [x] All **15 operation families** have evidence-backed fixtures — each matrix row has closing issues, catalog fixtures, and non-empty JUnit selectors.
- [x] All **22 domains have no unmapped rows** — compatibility report: 2,836/2,836 terminal, zero `UNKNOWN`.
- [x] All **three certification benchmark scenarios** produce artifacts recording numeric threshold results — `benchmark-{population,siege,stress}.json` with per-seed `threshold_results` and `certification_state`.

## Human checks (this review)

- [x] `./gradlew test` — **78 suites / 682 tests / 0 failures / 1 skipped** (last full run).
- [x] Python parity suite — **122 tests** across evidence/compat/fixture/harness/truth/gate tools.
- [x] Importer workflow — `MigrationImportTest` 12 tests: golden, conflicts, unsupported fields, malformed, dry-run, rollback, idempotency, script/asset reporting.
- [x] Migration path — schema-v0→v1 fixtures for all YAML families; importer applies through the canonical boundary (`ImportSink`).
- [x] Permissions — `CapabilityRegistry` covers every `MutationRequest` kind incl. economy; registry-driven `AuthorizationPolicy` fails closed on unmapped kinds.
- [x] Client/server behavior — headless coverage only; live client/server explicitly unverified (see below).
- [x] Documentation — quickstart, migration guide, examples all parse under the real loader (`CreatorDocsExamplesTest`); truth gate `PASS`.
- [x] Adversarial review — final review at [`reviews/2026-09-25-final-adversarial.md`](reviews/2026-09-25-final-adversarial.md); verdict ADOPT with honest residuals.
- [x] Blocked/unsupported probes visible in [`../RELEASE-NOTES.md`](../RELEASE-NOTES.md).

## Explicitly NOT certified (blocks any stronger claim)

- Target-runtime parity — no CustomNPCs probe exists; `UNVERIFIED_TARGET_RUNTIME` everywhere it matters.
- Live-server certification — benchmarks are headless-JVM; `timing_repeatable_within_10pct` recorded, not claimed.
- GUI/packet wire behavior at runtime; CustomNPCs world/NBT import (`UNSUPPORTED_NEEDS_EVIDENCE`).
