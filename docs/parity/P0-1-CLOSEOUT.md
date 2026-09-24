# P0-1 closeout — exact CustomNPCs target surface manifest

Status: `DONE-LOCAL` — exact-source v4 generation succeeds twice from the supplied JAR, the pinned clean research checkout, and its Vineflower output; output is byte-identical and the checked-in manifest is regenerated. An independent read-only adversarial audit returned `SUPPORTED` for identity binding and complete persistence-source hashing. Static source inventory still does not prove target-runtime behavior or parity.

## Current v4 state

- `tools/parity/generate_target_surface_manifest.py` emits `manifest_version: 4`, stable `inventory_id` values, `parity_fixture_ids`, explicit `coverage_status`, a domain/operation crosswalk, role-specific owners, closing issue IDs on operation rows, and a SHA-256 for every persistence-store source file.
- The checked-in manifest contains 2,836 inventory rows: 1,606 rows link to resolvable catalog fixtures; 1,230 target-class inventory rows are correctly marked `INVENTORY_ONLY`, not represented as executable fixtures.
- The crosswalk resolves all 22 domains, 15 operation families, and 25 catalog fixture IDs.
- Current regenerated manifest SHA-256: `7a6e3337e19be0dd3c9ac6fd356c563ae9d9ff82ca82518f35a6f076ab27769c`.
- Exact inputs: target JAR SHA-256 `6c28d87b215fc1191488194188ec8a39dd908ae7d2d887b7c9d7d463be0a162c`; clean research repository commit `9f7a921a5c3fbc66199b0d7719bdc0c56081745d`; Vineflower 1.12.0 executable SHA-256 `1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d`.
- The manifest was regenerated twice to separate outputs after the quest owner links were updated; both outputs and the checked-in file have the same SHA-256 above.
- v4 closes the reviewed association gap by checking each GUI, packet, command, persistence-participant, and persistence-store row against its exact record in the pinned research inventory. Verification requires the research HEAD above and clean relevant inventory inputs. Persistence rows contain 82 per-file digests for 81 unique source files across all 18 stores; verification checks every file hash and corresponding class entry in the exact JAR.
- A real exact-source combined CI fixture run returned `validation_status=PASS`, `source_provenance_status=VERIFIED`, no provenance blockers, 25 fixtures across 15 operations/22 domains, 86 mapped selectors across 20 observed fixtures, 5 fixtures with no selector, and `parity_status=BLOCKED` because no target-runtime probe was supplied.
- Fresh exact-source combined CI fixture run — exit 0, Gradle `BUILD SUCCESSFUL` (385 tests/51 suites), 20 mapped fixtures observed, 5 explicitly blocked, 0 mapped failures; source provenance `VERIFIED`, CI validation `PASS`, overall status and target parity remain `BLOCKED`.
- The earlier claim that inventory JSON and Vineflower source inputs were absent was incorrect; the adjacent research repository was overlooked. It is clean at the pinned commit above and was not modified.
- `python -m unittest discover -s tools/parity -p "test_*.py"` — **108 tests passed**, including wrong-source association, all five inventory binding types, revision/worktree pinning, and secondary persistence source hash/class coverage.
- `python tools/parity/check_truth_gate.py` — passed after the final gap-inventory wording correction.
- Historical `./gradlew.bat cleanTest test --rerun-tasks --console=plain` — `BUILD SUCCESSFUL`, 47 suites/339 cases, 0 failures/errors, 1 skipped. Latest full-suite evidence is 385 cases/51 suites above.
- Independent read-only adversarial audit — `SUPPORTED`; it confirmed exact inventory identity binding and full per-file persistence source/hash/JAR checks. No code changes were made by the auditor.
- P0-4 is closed locally as test infrastructure only. Feature-level certification remains blocked: 5 fixtures lack JUnit selectors, four mapped fixtures cover only narrow data/model slices, and every target-runtime outcome remains unverified. The strict combined-runner predicate remains false; the CI-only mode validates mapped JVM tests without promoting fixture coverage or parity.
- The independent adversarial review found and drove fixes for selector reuse, omitted manifest checks, stale JUnit totals, nested outcome markers, altered target identity/provenance, missing source hashes, hidden nested testcases, and incomplete operation IDs. A fresh read-only follow-up remains pending in `parity/reviews/2026-09-23-adversarial-fixture-runner.md`.

## Exact target identity and inventory counts

- Artifact: `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar`
- SHA-256: `6c28d87b215fc1191488194188ec8a39dd908ae7d2d887b7c9d7d463be0a162c`
- SHA-1: `e2f3b58ceb5aac4021d7bfd130e320925581471b`
- Counts: 2,467 archive entries; 1,230 classes; 972 assets; 49 data entries; 149 GUI records; 155 packets; 70 commands; 97 events; 7 roles; 11 jobs; 3 companion jobs; 60 persistence participants; 18 persistence stores; 15 operation rows.

## Historical v2 baseline evidence

The v2 and v3 manifests are historical schemas. The v2 baseline SHA-256 was `23D8D6D3BAEFC41566E0484B2286CC51DCDFBD1B07838383C80992C54510F227`; two v2 generations were byte-identical. v3 regeneration SHA-256 was `c61df09632fb4713022967648a1ca3ac8387c65619f42ec264a6bd707bfd5174`. Neither historical schema includes v4's exact record binding and per-file persistence digests.

The v4 manifest is static target-source inventory only. It does not establish CustomNPCs target-runtime behavior or parity.
