# Independent follow-up review — P0-4 fixture runner

Status: `APPROVED` for report invalidation and fail-closed runner evidence handling. This does not approve target-runtime parity.

## Review scope

- `tools/parity/run_storynpcs_fixtures.py`
- `tools/parity/test_storynpcs_fixture_runner.py`
- `tools/parity/fixture_harness.py` probe import and evidence expansion
- `docs/parity/fixture-catalog.json` selector-to-coverage mappings
- `.github/workflows/ci.yml` parity invocation and artifact upload

## Findings and remediation

The read-only review found and drove fixes for four ways the runner could misstate or retain evidence:

1. Caller-provided probe JSON could claim target observations, comparison results, or parity evidence. The importer now accepts StoryNPCs observations only and always preserves the unavailable-target baseline.
2. Skipped or missing mapped JUnit cases could be counted as successful evidence. They now block the mapped fixture and fail the operational run.
3. Reused XML could survive a failed/skipped Gradle run, and timezone-less suite timestamps produced a false stale rejection. The runner now invokes `cleanTest test --rerun-tasks`, reads only the standard Gradle test-results directory after a successful Gradle exit, and has no timestamp interpretation or alternate JUnit path. Nonzero Gradle exits and adapter exceptions produce failure-only artifacts without ingesting XML.
4. Report-path aliases and partial output deletion could leave stale success artifacts. The runner checks lexical and resolved paths plus existing file identity before unlinking; it removes the authoritative report first and writes it first on failure. On a secondary-output invalidation failure, the authoritative artifact becomes `FAIL` and Gradle is not started.

The catalog now maps 46 exact JUnit selectors across 15 fixtures, with explicit observed and uncovered scope for each. Nine fixtures have no semantically matching StoryNPCs JUnit case. Partial JVM test passes are expressly not treated as full fixture acceptance or live Minecraft/CustomNPCs evidence.

## Verification

- Independent auditor rechecked forged evidence, skipped/missing selectors, stale XML routes, partial write/delete failures, same-file and hardlink aliases, and a symlink-parent alias with a nonexistent output leaf. Verdict: `APPROVED` for this scope.
- Regression tests cover target-evidence forgery, skipped/missing/malformed/empty JUnit results, Gradle/adapter failure artifact replacement, same-path and hardlink non-mutation, symlink-parent alias rejection, and partial secondary-output deletion.
- Latest parity tooling test suite: 65 tests passed.
- Latest `python tools/parity/run_storynpcs_fixtures.py`: Gradle passed 323 cases across 47 suites; 15 partial StoryNPCs observations, 9 unmapped blockers, 0 mapped failures; target parity `BLOCKED`.
- Truth gate and structural fixture harness passed; the harness reports 24 fixtures, 15 operation families, and 22 domains while retaining target-runtime blockers.
- `git diff --check` passed; only existing working-tree CRLF notices were emitted.

## Residual risks and assumptions

- All 24 CustomNPCs runtime probes remain unverified. This runner observes StoryNPCs JVM tests only.
- Same-file output arguments are rejected without mutation and Gradle is not launched; any existing artifact then remains from the prior invocation, not the rejected run.
- If the filesystem denies both invalidation and failure-report writes, the runner aborts before Gradle and prints the preflight error; no software path can replace a file the OS refuses to modify.
- The review was read-only. It did not run Minecraft, launch the attached CustomNPCs mod, or verify UI/client-server behavior.
