# Adversarial fixture-runner review — 2026-09-23

## Status

`FOLLOW-UP-APPROVED` for the scoped P0-4 CI gate (2026-09-23). The first independent read-only audit found five fail-open paths; its follow-up identified three additional paths. Corrective code and regression tests are present. This scoped approval is not target-parity certification, a P0-1 source-manifest re-review, or approval to merge the entire dirty workspace.

## Findings from the independent auditor

1. A JUnit selector reused by multiple fixtures could multiply one passing test into apparent multi-fixture coverage.
2. `run_catalog` checked fixture links but skipped the complete target-manifest validator; removing a required surface could still return structural `PASS`.
3. Fixture crosswalk validation did not pin role ownership; a `RoleBank` row could be remapped from P7-2 to P6-2 with matching fixture references.
4. JUnit XML suite totals were not reconciled to contained test cases, failures, errors, or skips; a suite could declare `tests="0"` while containing a passing testcase.
5. A nested `<failure>` under an unexpected testcase wrapper was ignored as a pass. Separately, `run_catalog` accepted removed or altered artifact identity, arbitrary provenance strings, and a non-string target symbol.

The false-pass probes for all-mapped fixtures were synthetic. The checked-in fixture catalog remains incomplete and does not currently return an end-to-end pass.

## Follow-up findings from Peirce

6. `run_catalog` used schema-only validation and did not bind source records to the bytes of the supplied target JAR or the decompiled source files. A fabricated class/source path could pass without JAR membership and source-hash verification.
7. JUnit totals counted descendant testcases while the importer only consumed direct testcase children. A failing testcase inside an unexpected XML wrapper could be counted in totals but omitted from imported observations.
8. The operation matrix accepted duplicate operation IDs and did not require every canonical operation ID exactly once or require `symbol == id`.

## Corrective changes

- `validate_catalog` and `build_probe_report` reject selectors assigned to more than one fixture; repeated selectors within one fixture are also rejected.
- `run_catalog` invokes the full manifest validator with registered issue IDs, the canonical issue-surface claims, and catalog fixture IDs. The manifest validator requires the exact target artifact name, SHA-1, SHA-256, and archive-integrity status; exact top-level issue mapping; canonical role-to-issue mapping; surface-specific provenance; safe relative source paths; consistent source/symbol relations; typed non-empty core fields; and valid inventory IDs and source-hash shapes.
- JUnit suite and aggregate totals are reconciled with testcase outcomes. `<failure>`, `<error>`, and `<skipped>` markers nested beneath unexpected testcase wrappers are rejected instead of ignored.
- `run_catalog` now checks the exact target JAR name, SHA-1, and SHA-256, then verifies JAR membership, source-file existence, and each decompiled-source SHA-256. If any provenance input is unavailable, the report is `BLOCKED`; it does not fall back to a provenance-free `PASS`. Corrupt ZIP archives become structured provenance failures.
- The JUnit XML tree rejects testcase elements that are not direct children of a testsuite, preventing totals/importer disagreement.
- Operation-matrix IDs must be known, unique, exactly match their symbols, and cover all 15 canonical IDs once.
- Manifest generation now computes and checks hashes for GUI, packet, command, and persistence-participant source files; research inventory hashes remain mandatory.
- P0-4 acceptance criteria now require selector uniqueness, total reconciliation, exact artifact/provenance validation, role ownership validation, and fail-closed behavior for malformed manifests and nested result markers.

## Verification

### Final CI-gate follow-up by Euler — 2026-09-23

- Reviewed the explicit CI-only declared-blocker mode, catalog blocker validation, workflow invocation, report status semantics, and mutation-test coverage. No reachable CI bypass was found.
- Independently confirmed the CI predicate compares mapped selectors with the catalog and requires every mapped outcome to pass; empty selector IDs must exactly equal the owner-linked blocker IDs; changed owners, missing/skipped/failed selectors, failed provenance, catalog errors, and mismapped selectors are rejected.
- `python -B -m unittest discover -s tools/parity -p "test_*.py"` — **103 passed**.
- Fresh end-to-end CI-mode run — exit 0; 339 JUnit cases across 47 suites, 19 observed, 5 declared blockers, 0 mapped failures; `ci_validation_status=PASS`, overall status and parity remain `BLOCKED` because source provenance and target runtime are unavailable in CI.
- Fresh strict-mode run — exit 1 for the same five unmapped fixtures.
- GitHub reports successful workflow checks for a zero exit code; therefore this is a test-execution green check, not parity certification. See [GitHub Actions: setting an exit code](https://docs.github.com/en/actions/how-tos/create-and-publish-actions/set-exit-codes).

- Before implementation, the six initial regression tests reproduced the original three audit issues; they passed after correction.
- Historical pre-final-provenance baseline: `python -m unittest discover -s tools/parity -p "test_*.py"` — **89 passed**; the then-current truth gate passed.
- Historical focused suite before the latest provenance, nested-testcase, and operation-ID regressions: **64 passed**.
- `python -c ... read_junit_cases(Path("build/test-results/test"))` — accepted the fresh Gradle XML for **337 cases**.
- Historical `build_probe_report/run_catalog/fixture_run_passed` evidence: structural validation passed; execution remained `INCOMPLETE`; 15 mapped and 9 unmapped of 24 fixtures; executable runner predicate `False`; parity `BLOCKED`.
- Latest full Python discovery: **97 tests passed** after correcting two new performance-gap sentences that triggered the truth-gate wording rule.
- Latest truth-gate run passed. The no-provenance harness correctly exits 1 with `status=BLOCKED`, provenance `UNVERIFIED`, parity `BLOCKED`, and five fixtures with empty selector lists (after adding four transparently partial mappings).
- The most recent full Gradle run remains historical evidence: `BUILD SUCCESSFUL`; 47 suites, 337 tests, 0 failures, 0 errors, 1 skipped. A final `./gradlew test` is still required before milestone closeout.

## Residual risks and blockers

- The CI-gate follow-up is approved within its stated scope. Independent review of the regenerated exact-source manifest remains pending; this record does not approve a broader merge or change the missing target-runtime status.
- Five fixture selector lists remain empty P0-4 work; the combat, inventory, marks, and companion selectors now cover only the explicitly scoped partial observations. Structural manifest validity is not test coverage or parity evidence. With no target-source roots supplied, the harness is expected to report `status=BLOCKED`, `validation_status=PASS`, and `source_provenance_status=UNVERIFIED`.
- Exact-source v3 generation has now succeeded twice from the supplied JAR and the clean sibling research repository; see [P0-1 closeout](../P0-1-CLOSEOUT.md) for exact artifact/source hashes. Independent review of that regenerated manifest remains pending.
- Target-runtime behavior for all 24 fixtures remains unavailable; no parity certification is asserted.

## Later exact-source follow-up

The pending P0-1 review noted above was completed against manifest v4 after this CI-gate review. The independent auditor returned `SUPPORTED` for named source-inventory binding and per-file persistence hashing/class membership; see [the P0-1 provenance review](2026-09-23-p0-1-source-provenance.md). This updates only the P0-1 review state: five P0-4 feature fixtures remain blocked, and all target-runtime observations remain unverified.
